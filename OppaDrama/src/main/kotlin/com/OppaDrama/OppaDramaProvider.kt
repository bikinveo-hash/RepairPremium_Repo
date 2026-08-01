package com.OppaDrama

import android.media.MediaCodecList
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class MovieVersionData(
    val url: String,
    val versionName: String
)

class OppaDramaProvider : MainAPI() {
    override var mainUrl = "http://45.11.57.192"
    override var name = "OPPADRAMA"
    override var lang = "id"
    
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override var hasMainPage = true
    override var hasQuickSearch = true

    // Set 'true' saat proses troubleshooting, 'false' untuk rilis produksi
    private val DEBUG = false

    // Routing TurboVIP lewat proxy lokal (LocalProxy)
    private val USE_PROXY = true

    // DIAGNOSTIK SEMENTARA untuk mencari sebab duplikasi Video Trek.
    private val FL_DIAG = true

    // Pembatas Concurrency: Maksimal 3 request paralel bersamaan
    private val concurrencySemaphore = Semaphore(3)

    // Header Cookie Anti-Bot
    private val headersMap = mapOf(
        "Cookie" to "user_is_human=true",
        "User-Agent" to USER_AGENT
    )

    private fun logDebug(message: String) {
        if (DEBUG) {
            println("[OppaDrama Debug] $message")
        }
    }

    private fun getQualityFromString(quality: String?): Int {
        return when {
            quality.isNullOrBlank() -> Qualities.Unknown.value
            quality.contains("2160") || quality.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // Katalog Halaman Utama
    override val mainPage = mainPageOf(
        "$mainUrl/series/?status=&type=&order=update" to "Latest Update",
        "$mainUrl/series/?status=Ongoing&type=Drama&order=update" to "Drama Ongoing",
        "$mainUrl/series/?status=Completed&type=Drama&order=update" to "Completed Drama",
        "$mainUrl/series/?type=TV+Show&order=update" to "Variety Show",
        "$mainUrl/series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "$mainUrl/series/?country%5B%5D=china&type=Drama&order=update" to "Drama China",
        "$mainUrl/series/?country%5B%5D=japan&type=Drama&order=update" to "Drama Jepang",
        "$mainUrl/series/?country%5B%5D=thailand&type=Drama&order=update" to "Drama Thailand",
        "$mainUrl/series/?country%5B%5D=taiwan&type=Drama&order=update" to "Drama Taiwan",
        "$mainUrl/series/?country%5B%5D=philippines&type=Drama&order=update" to "Drama Philippines",
        "$mainUrl/series/?country%5B%5D=usa&type=Drama&order=update" to "Drama Western",
        "$mainUrl/series/?type=Movie&order=update" to "All Movies",
        "$mainUrl/series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Korean Movie",
        "$mainUrl/series/?country%5B%5D=japan&type=Movie&order=update" to "Japan Movie",
        "$mainUrl/series/?country%5B%5D=china&type=Movie&order=update" to "Chinese Movie",
        "$mainUrl/series/?country%5B%5D=thailand&type=Movie&order=update" to "Thailand Movie",
        "$mainUrl/series/?country%5B%5D=taiwan&type=Movie&order=update" to "Taiwan Movie",
        "$mainUrl/series/?country%5B%5D=philippines&type=Movie&order=update" to "Philippines Movie",
        "$mainUrl/series/?country%5B%5D=india&type=Movie&order=update" to "India Movie",
        "$mainUrl/series/?country%5B%5D=united-states&type=Movie&order=update" to "Western Movie"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}&page=$page"
        } else {
            request.data
        }

        logDebug("getMainPage -> Fetching $url")
        val doc = app.get(url, headers = headersMap).document
        val items = doc.select(".listupd .bsx, .bs .bsx").mapNotNull { element ->
            toSearchResponse(element)
        }

        logDebug("getMainPage -> Found ${items.size} items for category '${request.name}'")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        logDebug("search -> Searching for query: $query")
        val doc = app.get(searchUrl, headers = headersMap).document
        
        val results = doc.select(".listupd .bsx, .bs .bsx").mapNotNull { element ->
            toSearchResponse(element)
        }
        logDebug("search -> Found ${results.size} search results")
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    /**
     * Rapikan judul: entity HTML sudah didekode Jsoup, sisanya perapian spasi
     * dan pembuangan imbuhan SEO yang sering menempel di judul WordPress.
     */
    private fun cleanTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace('\u00A0', ' ')
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?i)^(nonton|streaming|download)\s+"""), "")
            .replace(Regex("""(?i)\s*(subtitle\s+indonesia|sub\s+indo)\s*${'$'}"""), "")
            .trim()
            .trim('-', '|', ':')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * Ambil URL poster dari sebuah <img>.
     *
     * Tema situs memakai <img src> biasa (tanpa lazy-load), tapi atribut lazy
     * tetap diperiksa agar tahan kalau tema berubah. Parameter ?resize / ?fit
     * dibuang supaya Hero memakai gambar resolusi penuh, bukan versi 246x350.
     */
    private fun posterFrom(img: Element?): String? {
        if (img == null) return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { attr -> img.attr(attr).takeIf { it.isNotBlank() } }
            ?: img.attr("srcset").substringBefore(" ").takeIf { it.isNotBlank() }
            ?: return null
        val clean = raw.trim().substringBefore("?resize=").substringBefore("?fit=")
        return if (isInvalidImage(clean)) null else fixUrl(clean)
    }

    /**
     * Satu kartu daftar. Markup situs:
     *
     *   <a href title="Judul">
     *     <div class="limit">
     *       <div class="typez Drama|Movie|TV Show">..</div>
     *       <div class="bt"><span class="epx">Ep 13</span><span class="sb Sub">Sub</span></div>
     *       <img src="...">
     *     </div>
     *     <div class="tt tts">Judul<h2>Judul</h2></div>
     *   </a>
     *
     * Judul diambil dari <h2> di dalam .tt, bukan dari .text() milik <a>, karena
     * teks <a> ikut menelan badge ("Drama Ongoing Sub Judul ...").
     *
     * Badge .epx / .sb sengaja diabaikan agar poster tampil bersih.
     */
    private fun toSearchResponse(element: Element): SearchResponse? {
        val anchor = element.selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        if (href.isBlank()) return null

        val title = cleanTitle(element.selectFirst(".tt h2")?.text())
            ?: cleanTitle(anchor.attr("title"))
            ?: cleanTitle(element.selectFirst("img")?.attr("alt"))
            ?: return null

        val poster = posterFrom(element.selectFirst("img"))

        // Badge tipe: <div class="typez ...">
        val typeText = element.selectFirst(".typez")?.text()?.trim().orEmpty()
        val tvType = when {
            typeText.equals("Movie", true) -> TvType.Movie
            typeText.equals("TV Show", true) -> TvType.TvSeries
            else -> TvType.AsianDrama
        }

        // Badge "Sub" sengaja tidak dipasang: hampir seluruh konten situs ini
        // bersubtitle, jadi badge tersebut tidak menambah informasi dan hanya
        // meramaikan poster. Tipe tetap dipakai agar kategori CloudStream benar.
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        logDebug("load -> Opening URL: $url")
        val doc = app.get(url, headers = headersMap).document

        // 1. Ekstraksi Parent URL dari Breadcrumb
        val breadcrumbLinks = doc.select(".ts-breadcrumb ol li a, .ts-breadcrumb a, .breadcrumb a")
        val parentUrl = breadcrumbLinks.map { it.attr("href") }.firstOrNull { link ->
            val fixed = fixUrl(link)
            fixed.isNotBlank() && 
            fixed != mainUrl && 
            fixed != "$mainUrl/" && 
            fixed != url && 
            !fixed.contains("/category/") && 
            !fixed.contains("/series/?")
        }?.let { fixUrl(it) } ?: url

        logDebug("load -> Resolved Parent URL: $parentUrl (Input URL: $url)")

        // 2. Judul Utama
        val title = cleanTitle(
            doc.selectFirst(".infolimit h2, h1.entry-title, h1[itemprop=name], h1.title")?.text()
        ) ?: cleanTitle(doc.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: "OPPADRAMA"

        // 3. Poster Utama
        // Rantai selector diperlebar: tema memakai .bigcontent/.single-info untuk
        // thumb, dan WordPress selalu menandai gambar unggulan dengan
        // class wp-post-image / itemprop=image. og:image jadi jaring terakhir.
        val cleanPoster = posterFrom(
            doc.selectFirst(
                ".bigcontent .thumb img, .single-info .thumb img, .thumb img, " +
                    ".megavid .tb img, .poster img, img.wp-post-image, img[itemprop=image]"
            )
        ) ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() && !isInvalidImage(it) }
        logDebug("load -> poster resolved: $cleanPoster")

        // 4. Sinopsis / Plot
        val plotElement = doc.selectFirst(".single-info .desc.mindes, .desc.mindes, .entry-content .desc, .desc")
        plotElement?.select(".colap")?.remove()
        
        var plot = plotElement?.text()?.trim()
        if (plot != null && (plot.startsWith("Download dan nonton", ignoreCase = true) || plot.startsWith("Tonton streaming", ignoreCase = true))) {
            plot = doc.select(".entry-content p, .desc p")
                .map { it.text().trim() }
                .firstOrNull { !it.startsWith("Download dan nonton", ignoreCase = true) && !it.startsWith("Tonton streaming", ignoreCase = true) }
        }

        // 5. Metadata
        val ratingText = doc.selectFirst(".rating strong, .num, [itemprop=ratingValue]")?.text()?.replace("Rating", "")?.trim()
        val statusText = doc.selectFirst(".spe span:contains(Status)")?.text() ?: ""
        val typeText = doc.selectFirst(".spe span:contains(Tipe)")?.text() ?: ""
        val epxText = doc.selectFirst(".epx")?.text() ?: ""
        val genres = doc.select(".genxed a, .genre a, .spe span:contains(Genres) a").map { it.text().trim() }
        val actors = doc.select(".spe span:contains(Artis) a, .spe span:contains(Pemeran) a, .cast a").map { it.text().trim() }

        // 6. Deteksi Movie vs TV Series
        val isMovie = typeText.contains("Movie", ignoreCase = true) || 
                      epxText.contains("Movie", ignoreCase = true) ||
                      title.startsWith("Movie ", ignoreCase = true) || 
                      url.contains("/movie-", ignoreCase = true)

        // 7. Ekstraksi Trailer YouTube
        val trailerUrl = doc.selectFirst("iframe[src*=youtube], iframe[src*=youtu.be], a.popup-youtube, a[href*=youtube.com], a[href*=youtu.be]")?.let { el ->
            el.attr("src").ifEmpty { el.attr("href") }
        }

        return if (isMovie) {
            val versionElements = doc.select(".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li")
            val movieVersions = if (versionElements.isNotEmpty()) {
                versionElements.mapNotNull { li ->
                    val aTag = li.selectFirst("a") ?: return@mapNotNull null
                    val verUrl = fixUrl(aTag.attr("href"))
                    val verName = cleanTitle(li.selectFirst(".playinfo h4, .epl-title")?.text())
                        ?: cleanTitle(aTag.attr("title"))
                        ?: cleanTitle(aTag.text())
                        ?: ""
                    MovieVersionData(verUrl, verName)
                }
            } else {
                listOf(MovieVersionData(url, title))
            }

            logDebug("load -> Identified as MOVIE with ${movieVersions.size} version(s)")

            newMovieLoadResponse(
                name = title,
                url = parentUrl,
                type = TvType.Movie,
                dataUrl = movieVersions.toJson()
            ) {
                this.posterUrl = cleanPoster
                this.plot = plot
                this.score = Score.from10(ratingText)
                this.tags = genres
                this.addActors(actors)
                this.addTrailer(trailerUrl)
            }
        } else {
            val episodeElements = doc.select(
                ".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li, #singlepisode .episodelist ul li"
            )

            val episodeList = episodeElements.mapNotNull { li ->
                val aTag = li.selectFirst("a") ?: return@mapNotNull null
                val epUrl = fixUrl(aTag.attr("href"))
                val epTitle = cleanTitle(li.selectFirst(".epl-title, .playinfo h4, .lchx")?.text())
                    ?: cleanTitle(aTag.attr("title"))
                    ?: cleanTitle(aTag.text())
                
                val epNum = Regex("""(?i)(?:Eps|Episode|Ep)\s*(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }.reversed()

            logDebug("load -> Identified as TV SERIES with ${episodeList.size} episode(s)")

            newTvSeriesLoadResponse(
                name = title,
                url = parentUrl,
                type = TvType.AsianDrama,
                episodes = if (episodeList.isNotEmpty()) episodeList else listOf(
                    newEpisode(url) {
                        this.name = title
                        this.episode = 1
                    }
                )
            ) {
                this.posterUrl = cleanPoster
                this.plot = plot
                this.score = Score.from10(ratingText)
                this.showStatus = if (statusText.contains("Ongoing", ignoreCase = true)) {
                    ShowStatus.Ongoing
                } else {
                    ShowStatus.Completed
                }
                this.tags = genres
                this.addActors(actors)
                this.addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        logDebug("loadLinks -> Incoming payload: $data")

        val versionList = tryParseJson<List<MovieVersionData>>(data) 
            ?: listOf(MovieVersionData(data, ""))

        // Deduplikasi Thread-Safe
        val visitedEmbedUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        val safeSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (visitedSubtitleUrls.add(sub.url)) {
                logDebug("Subtitle emitted: [${sub.lang}] -> ${sub.url}")
                subtitleCallback(sub)
            }
        }

        // Post-Extractor Deduplication
        val safeLinkCallback: (ExtractorLink) -> Unit = { link ->
            if (visitedStreamUrls.add(link.url)) {
                logDebug("Source emitted: [${link.source}] ${link.name} -> ${link.url}")
                callback(link)
            } else {
                logDebug("Stream URL skipped (Duplicate Stream Link): ${link.url}")
            }
        }

        coroutineScope {
            versionList.map { version ->
                async {
                    concurrencySemaphore.withPermit {
                        val pageUrl = version.url
                        val labelSuffix = if (version.versionName.isNotBlank()) " (${version.versionName})" else ""
                        logDebug("Processing Mode/Version: $pageUrl | Label: $labelSuffix")

                        runCatching {
                            val res = app.get(pageUrl, headers = headersMap)
                            val doc = res.document

                            val mirrorOptions = doc.select("select.mirror option, select[name=mirror] option")
                            logDebug("Found ${mirrorOptions.size} mirror option(s) on $pageUrl")

                            for (option in mirrorOptions) {
                                val base64Value = option.attr("value").trim()
                                var serverName = option.text().trim()

                                if (base64Value.isBlank() || serverName.contains("Pilih Server", ignoreCase = true)) {
                                    continue
                                }

                                if (labelSuffix.isNotBlank()) {
                                    serverName += labelSuffix
                                }

                                runCatching {
                                    val decodedHtml = base64Decode(base64Value)
                                    val iframeDoc = Jsoup.parse(decodedHtml)
                                    val iframeElements = iframeDoc.select("iframe[src], IFRAME[SRC]")

                                    for (iframe in iframeElements) {
                                        val rawSrc = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                        if (rawSrc.isNotBlank()) {
                                            val fixedUrl = fixUrl(rawSrc)

                                            if (!visitedEmbedUrls.add(fixedUrl)) {
                                                logDebug("Embed URL skipped (Pre-Extractor Duplicate): $fixedUrl")
                                                continue
                                            }

                                            val countBefore = visitedStreamUrls.size

                                            // 1. Penanganan TurboVIP
                                            if (fixedUrl.contains("emturbovid.com") || fixedUrl.contains("turboviplay.com")) {
                                                logDebug("Processing TurboVIP: $fixedUrl")
                                                extractTurboVipWithFallback(fixedUrl, pageUrl, serverName, safeSubtitleCallback, safeLinkCallback)
                                            } 
                                            // 2. Penanganan Hydrax
                                            else if (fixedUrl.contains("abyss") || fixedUrl.contains("hydrax")) {
                                                val mediaId = extractMediaId(fixedUrl)
                                                LocalProxy.obs("HYDRAX", "in=$fixedUrl id=$mediaId")

                                                val hxOk = try {
                                                    extractHydrax(fixedUrl, pageUrl, serverName, safeLinkCallback)
                                                } catch (e: Exception) {
                                                    LocalProxy.obs("HX-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                                                    false
                                                }

                                                if (!hxOk && visitedStreamUrls.size == countBefore) {
                                                    LocalProxy.obs("HYDRAX-GAGAL", "Ekstraksi HydraX tidak menghasilkan stream")
                                                }
                                            } 
                                            // 3. Penanganan FileLions / Minochinos
                                            else if (fixedUrl.contains("minochinos.com") || fixedUrl.contains("vidhide") || fixedUrl.contains("filelions")) {
                                                val flOk = try {
                                                    extractFileLions(fixedUrl, pageUrl, serverName, safeLinkCallback)
                                                } catch (e: Exception) {
                                                    LocalProxy.obs("FL-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                                                    false
                                                }

                                                if (!flOk && visitedStreamUrls.size == countBefore) {
                                                    val mediaId = extractMediaId(fixedUrl)
                                                    LocalProxy.obs("FL-FALLBACK", "in=$fixedUrl id=$mediaId")
                                                    if (!mediaId.isNullOrBlank()) {
                                                        loadExtractor("https://vidhidepro.com/v/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                        if (visitedStreamUrls.size == countBefore) {
                                                            loadExtractor("https://vidhidepro.com/d/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                        }
                                                    }
                                                    if (visitedStreamUrls.size == countBefore) {
                                                        loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    }
                                                }
                                            } else {
                                                // General Fallback
                                                loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                            }
                                        }
                                    }
                                }.onFailure { err ->
                                    logDebug("Failed to decode Base64 mirror: ${err.message}")
                                }
                            }

                            // Secondary Fallback: Iframe Default di DOM (#pembed)
                            if (visitedStreamUrls.isEmpty()) {
                                val defaultIframes = doc.select(".player-embed iframe, #pembed iframe, .mvelement iframe")
                                for (iframe in defaultIframes) {
                                    val src = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                    if (src.isNotBlank()) {
                                        val fixedUrl = fixUrl(src)
                                        if (visitedEmbedUrls.add(fixedUrl)) {
                                            logDebug("Attempting loadExtractor for DOM Iframe: $fixedUrl")
                                            if (fixedUrl.contains("emturbovid.com") || fixedUrl.contains("turboviplay.com")) {
                                                extractTurboVipWithFallback(fixedUrl, pageUrl, "TurboVIP", safeSubtitleCallback, safeLinkCallback)
                                            } else {
                                                loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                            }
                                        }
                                    }
                                }
                            }
                        }.onFailure { err ->
                            logDebug("Failed to process page $pageUrl: ${err.message}")
                        }
                    }
                }
            }.awaitAll()
        }

        logDebug("loadLinks -> Extraction completed. Total unique stream URLs emitted: ${visitedStreamUrls.size}")
        return visitedStreamUrls.isNotEmpty()
    }

    // Ekstraksi TurboVIP
    private suspend fun extractTurboVipWithFallback(
        url: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val doc = app.get(url, referer = referer, headers = headersMap).document
            
            var m3u8Url = doc.selectFirst("#video_player[data-hash]")?.attr("data-hash")?.trim()

            if (!m3u8Url.isNullOrBlank()) {
                logDebug("[TurboVIP Level 1 Success] Found M3U8 via data-hash: $m3u8Url")
            } else {
                logDebug("[TurboVIP Level 1 Failed] Trying Level 2 JS Unpacker")
                val html = doc.html()
                val unpacked = getAndUnpack(html)
                m3u8Url = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""file:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
            }

            if (!m3u8Url.isNullOrBlank()) {
                val fixedM3u8 = fixUrl(m3u8Url)

                val servedUrl = if (USE_PROXY) {
                    LocalProxy.proxyUrl(fixedM3u8) ?: fixedM3u8
                } else {
                    fixedM3u8
                }
                val viaProxy = servedUrl != fixedM3u8
                logDebug("[TurboVIP] serve=$servedUrl viaProxy=$viaProxy")

                callback(
                    newExtractorLink(
                        source = serverName,
                        name = if (viaProxy) "$serverName [proxy]" else serverName,
                        url = servedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        if (viaProxy) {
                            this.referer = ""
                        } else {
                            this.referer = "https://turboviplay.com/"
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    }
                )
                true
            } else {
                logDebug("[TurboVIP Level 2 Failed] Trying Level 4 loadExtractor built-in")
                val altUrl = if (url.contains("/t/")) url.replace("/t/", "/v/") else url
                loadExtractor(altUrl, referer = url, subtitleCallback, callback)
            }
        }.getOrElse { false }
    }

    // ======================================================================
    // Dukungan AV1 perangkat. Dipakai agar track av1 hanya ditawarkan bila
    // perangkat benar-benar punya decodernya (bundle player juga menyaring
    // av1 berdasarkan kemampuan browser).
    // ======================================================================
    private val deviceSupportsAv1: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/av01", true) }
            }
        }.getOrDefault(false)
    }

    /** Satu entri `sources` hasil dekripsi config Hydrax. */
    private data class HydraxSource(
        val label: String,
        val codec: String,
        val resId: String?,
        val size: Long?,
        val sub: String?,
        val url: String?,
        val path: String?
    )

    /**
     * MD5 dengan seed "satu byte per digit".
     *
     * Ini meniru perilaku pustaka md5 di bundle player: expandKey(size) dipanggil
     * dengan Number, dan modul md5 hanya melakukan toString() tanpa stringToBytes,
     * sehingga bytesToWords membaca tiap karakter lalu meng-koersinya jadi angka
     * ('7' menjadi 7, bukan 0x37).
     */
    private fun md5HexOfDigits(size: Long): String {
        val digits = size.toString()
        val seed = ByteArray(digits.length) { i -> (digits[i] - '0').toByte() }
        return MessageDigest.getInstance("MD5").digest(seed)
            .joinToString("") { "%02x".format(it) }
    }

    /** AES/CTR/NoPadding. CTR bersifat simetris; dipakai untuk enkripsi token. */
    private fun aesCtr(keyHex: String, data: ByteArray): ByteArray {
        val keyBytes = keyHex.toByteArray(Charsets.UTF_8)      // 32 byte -> AES-256
        val ivBytes = keyBytes.copyOfRange(0, 16)              // counter = 16 byte pertama
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes)
        )
        return cipher.doFinal(data)
    }

    /** base64 alfabet standar, tanpa padding dan tanpa newline. */
    private fun b64NoPad(data: ByteArray): String =
        android.util.Base64.encodeToString(
            data,
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )

    /**
     * Bangun URL jalur mp4_native Hydrax.
     *
     *   plain   = "/mp4/{md5_id}/{res_id}/{size}?v={slug}"
     *   keyHex  = md5(seed digit dari size) dalam hex, 32 karakter
     *   ct      = AES/CTR encrypt(plain), kunci = keyHex sebagai ASCII
     *   token   = base64(base64(ct) tanpa '=') tanpa '='
     *   URL     = https://{host}/sora/{size}/{token}
     */
    private fun buildSoraUrl(
        md5Id: String,
        resId: String,
        size: Long,
        slug: String,
        host: String,
        type: String = "mp4"
    ): String {
        val plain = "/$type/$md5Id/$resId/$size?v=$slug"
        val ct = aesCtr(md5HexOfDigits(size), plain.toByteArray(Charsets.UTF_8))
        val token = b64NoPad(b64NoPad(ct).toByteArray(Charsets.US_ASCII))
        return "https://$host/sora/$size/$token"
    }

    /**
     * Urutan preferensi kandidat dalam satu resolusi yang sama.
     *
     *  1. Buang av1 bila perangkat tidak punya decodernya.
     *  2. Dahulukan h264. av1 hanya dipakai bila tidak ada h264 untuk resolusi itu.
     *  3. Dalam codec yang sama, berkas terbesar dipilih (bitrate lebih tinggi).
     *
     * Mengembalikan daftar terurut, bukan satu nilai, supaya pemanggil bisa
     * mencoba kandidat berikutnya kalau pembangunan URL gagal.
     */
    private fun hydraxPreference(group: List<HydraxSource>): List<HydraxSource> {
        val playable = group.filter { !it.codec.equals("av1", true) || deviceSupportsAv1 }
        return playable.sortedWith(
            compareBy<HydraxSource> { if (it.codec.equals("av1", true)) 1 else 0 }
                .thenByDescending { it.size ?: 0L }
        )
    }

    /**
     * Extractor Hydrax.
     *
     * HX-1..HX-4 (ambil HTML, blob base64, dekripsi config AES-CTR) tidak berubah.
     * HX-5 mengurai sources, HX-6 memilih satu kandidat per resolusi lalu membangun
     * URL dengan dua jalur:
     *   1. /sora/  bila source punya size + sub + domain yang cocok  -> file utuh
     *   2. url + path (perilaku lama) sebagai cadangan
     */
    private suspend fun extractHydrax(
        embedUrl: String,
        pageUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ---------------- HX-1 ----------------
        val html = try {
            app.get(
                embedUrl,
                referer = pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: Exception) {
            LocalProxy.obs("HX-1-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        LocalProxy.obs("HX-1-OK", "html=${html.length} char")
        if (html.isBlank()) return false

        // ---------------- HX-2 ----------------
        val b64 = Regex("""["']([A-Za-z0-9+/=]{200,})["']""")
            .find(html)?.groupValues?.get(1)
        if (b64.isNullOrBlank()) {
            LocalProxy.obs("HX-2-GAGAL", "blob base64 config tidak ditemukan")
            return false
        }
        LocalProxy.obs("HX-2-OK", "b64=${b64.length} char")

        // ---------------- HX-3 ----------------
        val rawBytes = try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            LocalProxy.obs("HX-3-GAGAL", "base64 decode: ${e.message}")
            return false
        }

        val jsonObj = try {
            JSONObject(String(rawBytes, Charsets.ISO_8859_1))
        } catch (e: Exception) {
            LocalProxy.obs("HX-3-JSON-ERROR", "Parsing JSONObject gagal: ${e.message}")
            return false
        }

        val slug = jsonObj.optString("slug").takeIf { it.isNotBlank() }
            ?: extractMediaId(embedUrl) ?: ""
        val md5id = jsonObj.optString("md5_id").takeIf { it.isNotBlank() }
        val userId = jsonObj.optString("user_id").takeIf { it.isNotBlank() }
        val mediaStr = jsonObj.optString("media").takeIf { it.isNotBlank() }

        LocalProxy.obs(
            "HX-3-OK",
            "json=${rawBytes.size}B slug=$slug md5_id=$md5id user_id=$userId media_len=${mediaStr?.length ?: 0}"
        )

        // ---------------- HX-4 (Proses Dekripsi Field 'media') ----------------
        if (userId.isNullOrBlank() || md5id.isNullOrBlank() || mediaStr.isNullOrBlank()) {
            LocalProxy.obs("HX-4-GAGAL", "Parameter terenkripsi tidak lengkap (userId=$userId, md5id=$md5id, media=${mediaStr != null})")
            return false
        }

        val decryptedJson = try {
            val hashInput = "$userId:$slug:$md5id".toByteArray(Charsets.UTF_8)
            val md5Hash = MessageDigest.getInstance("MD5").digest(hashInput)
            val keyHex = md5Hash.joinToString("") { "%02x".format(it) }

            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )

            String(cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8)
        } catch (e: Exception) {
            LocalProxy.obs("HX-4-DECRYPT-ERROR", "${e.javaClass.simpleName}: ${e.message}")
            return false
        }

        LocalProxy.obs("HX-4-DECRYPT-OK", "decrypted_len=${decryptedJson.length}")

        // ---------------- HX-5 (Parsing hasil dekripsi) ----------------
        val decryptedObj = try {
            JSONObject(decryptedJson)
        } catch (e: Exception) {
            LocalProxy.obs("HX-5-JSON-ERROR", "Parsing decrypted JSON gagal: ${e.message}")
            return false
        }

        val mp4Obj = decryptedObj.optJSONObject("mp4")
        val sourcesArr = mp4Obj?.optJSONArray("sources")
            ?: decryptedObj.optJSONArray("sources")

        if (sourcesArr == null || sourcesArr.length() == 0) {
            LocalProxy.obs(
                "HX-5-GAGAL",
                "sources kosong (len=${decryptedJson.length}): ${decryptedJson.take(300)}"
            )
            return false
        }

        val parsed = ArrayList<HydraxSource>()
        for (i in 0 until sourcesArr.length()) {
            val o = sourcesArr.optJSONObject(i) ?: continue
            LocalProxy.obs("HX-5-SOURCE[$i]", o.toString())
            parsed.add(
                HydraxSource(
                    label = o.optString("label", null)?.takeIf { it.isNotBlank() } ?: "HD",
                    codec = o.optString("codec", null)?.takeIf { it.isNotBlank() } ?: "",
                    resId = o.optString("res_id", null)?.takeIf { it.isNotBlank() && it != "null" },
                    size = o.optString("size", null)?.toLongOrNull(),
                    sub = o.optString("sub", null)?.takeIf { it.isNotBlank() && it != "null" },
                    url = o.optString("url", null)?.takeIf { it.isNotBlank() && it != "null" },
                    path = o.optString("path", null)?.takeIf { it.isNotBlank() && it != "null" }
                        ?: o.optString("file", null)?.takeIf { it.isNotBlank() && it != "null" }
                        ?: o.optString("src", null)?.takeIf { it.isNotBlank() && it != "null" }
                )
            )
        }

        // Daftar domain CDN, dipakai untuk mencocokkan field `sub` tiap source
        val domainsArr = mp4Obj?.optJSONArray("domains")
            ?: decryptedObj.optJSONArray("domains")
        val domains = ArrayList<String>()
        for (i in 0 until (domainsArr?.length() ?: 0)) {
            val d = domainsArr?.optString(i)
            if (!d.isNullOrBlank()) domains.add(d)
        }

        LocalProxy.obs(
            "HX-5-OK",
            "sources=${parsed.size} domains=${domains.size} av1_support=$deviceSupportsAv1"
        )

        // ---------------- HX-6 (Pilih satu per resolusi, lalu emit) ----------------
        // Satu resolusi = satu baris di daftar Sumber CloudStream, karena satu
        // ExtractorLink selalu dirender sebagai satu entri.
        var linksEmitted = 0
        val emitted = HashSet<String>()

        val byLabel = parsed.groupBy { it.label }
            .toList()
            .sortedByDescending { (label, _) -> getQualityFromString(label) }

        for ((label, group) in byLabel) {
            val order = hydraxPreference(group)

            if (order.isEmpty()) {
                LocalProxy.obs(
                    "HX-6-SKIP-AV1",
                    "label=$label semua kandidat av1, perangkat tidak punya decodernya"
                )
                continue
            }
            if (group.size > 1) {
                LocalProxy.obs(
                    "HX-6-DEDUPE",
                    "label=$label kandidat=${group.size} codec=${group.map { it.codec }} " +
                        "-> dipilih codec=${order.first().codec} size=${order.first().size}"
                )
            }

            var chosen: HydraxSource? = null
            var fullUrl: String? = null

            for (src in order) {
                // --- Jalur 1: /sora/ (mp4_native). Mengembalikan file utuh. ---
                if (src.resId != null && src.size != null && src.sub != null) {
                    val host = domains.firstOrNull { it.contains(src.sub) }
                    if (host != null) {
                        fullUrl = try {
                            buildSoraUrl(md5id, src.resId, src.size, slug, host)
                        } catch (e: Exception) {
                            LocalProxy.obs("HX-6-SORA-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                            null
                        }
                        if (fullUrl != null) {
                            LocalProxy.obs(
                                "HX-6-SORA",
                                "label=$label codec=${src.codec} res_id=${src.resId} size=${src.size} host=$host"
                            )
                        }
                    } else {
                        LocalProxy.obs("HX-6-SORA-SKIP", "domain untuk sub='${src.sub}' tidak ditemukan")
                    }
                }

                // --- Jalur 2: url + path (perilaku lama, dipertahankan sebagai cadangan) ---
                if (fullUrl == null) {
                    val bUrl = src.url
                        ?: mp4Obj?.optString("url", null)?.takeIf { it.isNotBlank() && it != "null" }
                        ?: decryptedObj.optString("url", null)?.takeIf { it.isNotBlank() && it != "null" }
                    val pPath = src.path

                    if (pPath != null && (pPath.startsWith("http://") || pPath.startsWith("https://"))) {
                        fullUrl = unescapeJs(pPath)
                    } else if (!bUrl.isNullOrBlank() && !pPath.isNullOrBlank()) {
                        fullUrl = "${unescapeJs(bUrl).trimEnd('/')}/${unescapeJs(pPath).trimStart('/')}"
                    }
                    if (fullUrl != null) {
                        LocalProxy.obs("HX-6-LEGACY", "label=$label (cadangan url+path)")
                    }
                }

                if (fullUrl != null) {
                    chosen = src
                    break
                }
            }

            val finalUrl = fullUrl
            if (chosen == null || finalUrl.isNullOrBlank() || !emitted.add(finalUrl)) continue

            val qualityVal = getQualityFromString(label)

            LocalProxy.obs(
                "HX-6-EMIT-DETAILS",
                "source=$serverName | name=$serverName | quality=$label ($qualityVal) | " +
                    "codec=${chosen.codec} | type=VIDEO | referer=https://abyssplayer.com/ | " +
                    "UA=$USER_AGENT | url=$finalUrl"
            )

            callback(
                newExtractorLink(
                    source = serverName,
                    name = serverName,
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityVal
                    this.referer = "https://abyssplayer.com/"
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            linksEmitted++
        }

        if (linksEmitted == 0) {
            LocalProxy.obs(
                "HX-6-GAGAL",
                "Tidak ada URL yang bisa dibangun (len=${decryptedJson.length}): ${decryptedJson.take(300)}"
            )
            return false
        }

        LocalProxy.obs(
            "HX-6-EMIT-DIRECT",
            "Sukses emit $linksEmitted quality track under single server $serverName"
        )
        return true
    }

    private fun unpackDeanEdwards(packed: String): String? {
        val m = Regex(
            """\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(packed) ?: return null

        val payload = unescapeJs(m.groupValues[1])
        val radix = m.groupValues[2].toIntOrNull() ?: return null
        val count = m.groupValues[3].toIntOrNull() ?: return null
        val words = m.groupValues[4].split("|")
        if (radix < 2 || radix > 62 || count <= 0) return null

        val map = HashMap<String, String>(count * 2)
        for (i in 0 until count) {
            val tok = baseN(i, radix)
            val w = words.getOrNull(i)
            map[tok] = if (w.isNullOrEmpty()) tok else w
        }
        return Regex("""\b[0-9A-Za-z]+\b""").replace(payload) { mr ->
            map[mr.value] ?: mr.value
        }
    }

    private fun baseN(num: Int, radix: Int): String {
        if (num == 0) return "0"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % radix])
            n /= radix
        }
        return sb.toString()
    }

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    // Extractor FileLions
    private suspend fun extractFileLions(
        embedUrl: String,
        pageUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(
                embedUrl,
                referer = pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: Exception) {
            LocalProxy.obs("FL-1-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        LocalProxy.obs("FL-1-OK", "html=${html.length} char")
        if (html.isBlank()) return false

        val kandidat = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\s*\)?\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).map { it.value }.toList()

        if (kandidat.isEmpty()) {
            LocalProxy.obs("FL-2-GAGAL", "blok packed tidak ditemukan")
            return false
        }
        LocalProxy.obs(
            "FL-2-OK",
            "blok=${kandidat.size} ukuran=${kandidat.map { it.length }}"
        )

        var code: String? = null
        var links = LinkedHashMap<String, String>()
        for ((idx, blok) in kandidat.withIndex()) {
            val hasil = unpackDeanEdwards(blok)
            if (hasil.isNullOrBlank()) {
                LocalProxy.obs("FL-3-SKIP", "blok[$idx] unpack gagal")
                continue
            }
            val l = LinkedHashMap<String, String>()
            Regex(""""(\w+)"\s*:\s*"((?:https?://|/)[^"]+)"""").findAll(hasil).forEach {
                l[it.groupValues[1]] = it.groupValues[2]
            }
            if (l.isEmpty()) {
                Regex("""(\w+)\s*:\s*"((?:https?://|/)[^"]+)"""").findAll(hasil).forEach {
                    l[it.groupValues[1]] = it.groupValues[2]
                }
            }
            LocalProxy.obs(
                "FL-3-OK",
                "blok[$idx] unpacked=${hasil.length} char links=${l.keys}"
            )
            if (l.values.any { it.contains(".m3u8") || it.contains(".mp4") }) {
                code = hasil
                links = l
                break
            }
        }
        if (code == null) {
            LocalProxy.obs("FL-3-GAGAL", "tidak ada blok yang menghasilkan URL media")
            return false
        }

        val kandidatUrl = listOf("hls4", "hls2").firstNotNullOfOrNull { k ->
            links[k]?.takeIf { it.contains(".m3u8") || it.contains(".txt") }
                ?.let { k to it }
        }
        if (kandidatUrl == null) {
            LocalProxy.obs("FL-4-BERHENTI", "hls4/hls2 tidak ada (links: ${links.keys})")
            return false
        }
        val (labelDipakai, urlMentah) = kandidatUrl
        val hls2 = runCatching {
            java.net.URL(java.net.URL(embedUrl), urlMentah).toString()
        }.getOrElse { urlMentah }

        val host = runCatching { java.net.URL(hls2).host }.getOrNull() ?: "?"
        val servedUrl = LocalProxy.proxyUrl(hls2, clean = true) ?: hls2
        val viaProxy = servedUrl != hls2
        LocalProxy.obs(
            "FL-4-EMIT",
            "label=$labelDipakai host=$host clean=$viaProxy tersedia=${links.keys} url=$hls2"
        )
        callback(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = servedUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = ""
            }
        )
        LocalProxy.obs("FL-4-OK", "emit=1 ($labelDipakai)")

        if (FL_DIAG) {
            try {
                val pl = app.get(hls2, headers = mapOf("User-Agent" to USER_AGENT)).text
                val resDari = { tag: String ->
                    Regex("""$tag:[^\n]*""").findAll(pl).map { mr ->
                        Regex("""RESOLUTION=(\d+x\d+)""")
                            .find(mr.value)?.groupValues?.get(1) ?: "?"
                    }.toList()
                }
                val normal = resDari("#EXT-X-STREAM-INF")
                val iframe = resDari("#EXT-X-I-FRAME-STREAM-INF")
                LocalProxy.obs(
                    "FL-5-MASTER",
                    "bytes=${pl.length} normal=${normal.size}$normal " +
                        "iframe=${iframe.size}$iframe total_entri=${normal.size + iframe.size}"
                )
            } catch (e: Exception) {
                LocalProxy.obs("FL-5-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return true
    }

    private fun extractMediaId(url: String): String? {
        val base = url.substringBefore('#')
        Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(base)?.let { return it.groupValues[1] }
        val seg = base.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return if (seg.matches(Regex("""[A-Za-z0-9_-]{4,}"""))) seg else null
    }

    private fun isInvalidImage(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        return lower.contains("logo") ||
               lower.contains("oppadrama") ||
               lower.contains("cropped-site-icon") ||
               lower.contains("loading.gif") ||
               lower.contains("gravatar.com") ||
               lower.contains("placeholder") ||
               lower.contains("no-image") ||
               lower.startsWith("data:")
    }
}
