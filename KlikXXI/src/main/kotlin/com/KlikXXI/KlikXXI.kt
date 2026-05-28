package com.KlikXXI

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KlikXXI : MainAPI() {
    override var mainUrl = "https://klikxxi.me"
    override var name    = "KlikXXI"
    override val hasMainPage       = true
    override var lang              = "id"
    override val hasDownloadSupport = true
    override val supportedTypes    = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "KlikXXI"

    // ─────────────────────────────────────────────────────────────────────────
    //  MAIN PAGE
    // ─────────────────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/?s=&search=advanced&post_type=movie&index=&orderby=&genre=&movieyear=&country=&quality=" to "Latest Movies",
        "$mainUrl/tv"                     to "TV Series",
        "$mainUrl/category/action/"       to "Action",
        "$mainUrl/category/adventure/"    to "Adventure",
        "$mainUrl/category/crime/"        to "Crime",
        "$mainUrl/category/drama/"        to "Drama",
        "$mainUrl/category/korea/"        to "Korea",
        "$mainUrl/category/fantasy/"      to "Fantasy",
        "$mainUrl/category/horror/"       to "Horror",
        "$mainUrl/category/india-series/" to "India Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("?") ->
                if (page <= 1) request.data
                else request.data.replace("/?", "/page/$page/?")
            else ->
                if (page <= 1) request.data
                else "${request.data.removeSuffix("/")}/page/$page/"
        }
        val items = app.get(url).document
            .select("article.item, article.item-infinite, div.gmr-item-modulepost")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SEARCH
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
            .select("article.item, article.item-infinite")
            .mapNotNull { it.toSearchResult() }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOAD (detail halaman film/series)
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc   = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text()
            ?.replace("Streaming Film", "")?.trim() ?: ""

        val posterRaw = doc
            .selectFirst(".gmr-movie-data img, .content-thumbnail img, figure img")
            ?.let { it.attr("data-lazy-src").ifEmpty { it.attr("src") } }
        val poster = fixUrlNull(posterRaw)?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")

        val tags    = doc.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val year    = doc.selectFirst(".gmr-moviedata:contains(Year) a")?.text()?.toIntOrNull()
        val plot    = doc.selectFirst(".entry-content-single p, .gmr-movie-content")?.text()
        val rating  = doc.selectFirst(".gmr-rating-item")
            ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()

        val epElements = doc.select(".gmr-season-episodes a.button-shadow")
        return if (epElements.isNotEmpty()) {
            val episodes = epElements.mapNotNull {
                val href   = it.attr("href")
                val epName = it.text()
                if (epName.contains("Batch", true)) return@mapNotNull null
                newEpisode(href) {
                    this.name    = epName
                    this.season  = Regex("""S(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = Regex("""Eps(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                }
            }.reversed()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = plot
                this.tags = tags; this.score = Score.from10(rating)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = plot
                this.tags = tags; this.score = Score.from10(rating)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOAD LINKS
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isDataJob: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "══ loadLinks ══ $data")

        val doc    = app.get(data).document
        val ajaxId = doc.selectFirst(".gmr-server-wrap, #muvipro_player_content_id")
            ?.attr("data-id")

        if (ajaxId.isNullOrBlank()) {
            Log.e(TAG, "❌ data-id tidak ditemukan di halaman.")
            return false
        }
        Log.d(TAG, "✅ data-id: $ajaxId")

        val servers = doc
            .select("ul.muvipro-player-tabs li a, .gmr-player-nav li a")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (href.startsWith("#p")) href.removePrefix("#p") else null
            }.distinct()

        Log.d(TAG, "✅ ${servers.size} server tab ditemukan: $servers")

        if (servers.isEmpty()) {
            Log.e(TAG, "❌ Tidak ada server tab.")
            return false
        }

        servers.forEach { serverNum ->
            Log.d(TAG, "→ Tab p$serverNum")

            val ajaxResponse = try {
                app.post(
                    url     = "$mainUrl/wp-admin/admin-ajax.php",
                    data    = mapOf(
                        "action"  to "muvipro_player_content",
                        "tab"     to "p$serverNum",
                        "post_id" to ajaxId
                    ),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
            } catch (e: Exception) {
                Log.e(TAG, "❌ AJAX gagal p$serverNum: ${e.message}")
                return@forEach
            }

            // Ekstrak src iframe — coba kutip tunggal dulu, lalu kutip ganda
            val rawSrc =
                Regex("""(?i)src='([^"']+)""").find(ajaxResponse)?.groupValues?.get(1)
                    ?: Regex("""(?i)src="([^"']+)""").find(ajaxResponse)?.groupValues?.get(1)

            if (rawSrc == null) {
                Log.w(TAG, "⚠️  Tidak ada iframe src di p$serverNum")
                return@forEach
            }

            val iframeUrl = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
            Log.d(TAG, "   iframe: $iframeUrl")

            when {
                // ── STRP2P + UPNS.ONE ───────────────────────────────────────
                // Menangkap semua domain alias Strp2p yang dikonfirmasi:
                //   • klikxxi.strp2p.site  (domain utama)
                //   • klikxxi.upns.one     (domain alias, API identik)
                // Format URL yang didukung Strp2p.kt:
                //   • /e/{videoId}   (format lama)
                //   • /#{videoId}    (format baru — hash fragment)
                //
                // WAJIB pakai getSafeUrl(), bukan getUrl() langsung!
                // getSafeUrl = suspend wrapper dari ExtractorApi base class,
                // aman dipanggil dari dalam forEach di konteks suspend ini.
                iframeUrl.contains("strp2p.site", ignoreCase = true)
                        || iframeUrl.contains("upns.one", ignoreCase = true) -> {
                    Log.d(TAG, "   → Strp2p extractor")
                    Strp2p().getSafeUrl(iframeUrl, data, subtitleCallback, callback)
                    Log.d(TAG, "   ← Strp2p done")
                }

                // ── HGCLOUD ─────────────────────────────────────────────────
                iframeUrl.contains("hgcloud.to", ignoreCase = true) -> {
                    Log.d(TAG, "   → HGCloud extractor")
                    try {
                        val fileId     = iframeUrl.substringAfter("/e/")
                        val playerUrl  = "https://masukestin.com/e/$fileId"
                        val html       = app.get(playerUrl,
                            headers = mapOf("Referer" to "https://hgcloud.to/")).text
                        val unpacked   = getAndUnpack(html)
                        val masterUrl  = extractHlsUrl(unpacked) ?: run {
                            Log.w(TAG, "   HGCloud: HLS URL tidak ada, fallback")
                            loadExtractor(iframeUrl, data, subtitleCallback, callback)
                            return@forEach
                        }
                        parseMasterM3u8(masterUrl, playerUrl, name,
                            extractQualityLabels(unpacked), callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "   HGCloud error: ${e.message}, fallback")
                        loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    }
                }

                // ── SERVER LAIN → fallback framework ────────────────────────
                else -> {
                    Log.d(TAG, "   → loadExtractor fallback")
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS — HGCloud M3U8 parsing
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractHlsUrl(js: String): String? {
        listOf("hls4", "hls3", "hls2").forEach { key ->
            val url = (Regex(""""$key"\s*:\s*"([^"]+)"""").find(js)
                ?: Regex("""'$key'\s*:\s*'([^']+)'""").find(js))
                ?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return@forEach
            return when {
                url.startsWith("http") -> url
                url.startsWith("//")   -> "https:$url"
                url.startsWith("/")    -> "https://masukestin.com$url"
                else                   -> "https://masukestin.com/$url"
            }
        }
        return null
    }

    private fun extractQualityLabels(js: String): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        val block  = Regex("""['"]qualityLabels['"]\s*:\s*\{([^}]+)\}""")
            .find(js)?.groupValues?.get(1) ?: return result
        Regex(""""(\d+)"\s*:\s*"(\d+)p"""").findAll(block).forEach { m ->
            result[m.groupValues[1].toIntOrNull() ?: return@forEach] =
                m.groupValues[2].toIntOrNull() ?: return@forEach
        }
        return result
    }

    private suspend fun parseMasterM3u8(
        masterUrl: String, referer: String, sourceName: String,
        qualityLabels: Map<Int, Int>, callback: (ExtractorLink) -> Unit
    ) {
        val content = try {
            app.get(masterUrl, headers = mapOf("Referer" to referer)).text
        } catch (e: Exception) {
            callback(newExtractorLink(sourceName, "HGCloud", masterUrl, ExtractorLinkType.M3U8) {
                this.referer = referer; this.quality = Qualities.Unknown.value })
            return
        }

        if (!content.contains("#EXT-X-STREAM-INF")) {
            callback(newExtractorLink(sourceName, "HGCloud", masterUrl, ExtractorLinkType.M3U8) {
                this.referer = referer; this.quality = Qualities.Unknown.value })
            return
        }

        val baseUrl = masterUrl.substringBeforeLast("/")
        var pendingH = -1

        content.lines().forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    pendingH = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull()
                        ?: (if (bw > 0) qualityLabels[bw / 1000] ?: -1 else -1)
                }
                !line.startsWith("#") && line.isNotBlank() && pendingH != -1 -> {
                    val variantUrl = when {
                        line.trim().startsWith("http") -> line.trim()
                        line.trim().startsWith("/")    -> "https://masukestin.com${line.trim()}"
                        else                           -> "$baseUrl/${line.trim()}"
                    }
                    val label = if (pendingH > 0) "${pendingH}p" else "HGCloud"
                    callback(newExtractorLink(sourceName, "HGCloud $label", variantUrl,
                        ExtractorLinkType.M3U8) {
                        this.referer = referer
                        this.quality = if (pendingH > 0) pendingH else Qualities.Unknown.value
                    })
                    pendingH = -1
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPER — SearchResponse builder
    // ─────────────────────────────────────────────────────────────────────────
    private fun Element.toSearchResult(): SearchResponse? {
        val a     = selectFirst(".entry-title a") ?: return null
        val title = a.text()
        val href  = a.attr("href")
        val poster = fixUrlNull(
            selectFirst("img")?.let {
                it.attr("data-lazy-src").ifEmpty { it.attr("src") }
            }
        )?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        val quality = getQualityFromString(
            selectFirst(".gmr-quality-item, .quality, .qualitylabel, span.quality")?.text())
        val score = Score.from10(
            selectFirst(".gmr-rating-item, .rating, .star-rating")
                ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull())
        val isTv = selectFirst(".gmr-numbeps") != null || href.contains("/tv/")
        return if (isTv)
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster; this.quality = quality; this.score = score }
        else
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster; this.quality = quality; this.score = score }
    }
}
