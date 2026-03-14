package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new32.ngefilm.site" // DOMAIN TERBARU
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    // --- CONFIG & SECRET KEYS ---
    private val UA_BROWSER = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val RPM_KEY = "6b69656d7469656e6d75613931316361" 
    private val RPM_IV = "313233343536373839306f6975797472"

    private fun Element.getImageAttr(): String? {
        var url = this.attr("data-src").ifEmpty { this.attr("src") }
        if (url.isEmpty()) {
            val srcset = this.attr("srcset")
            if (srcset.isNotEmpty()) {
                url = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: ""
            }
        }
        return if (url.isNotEmpty()) {
            httpsify(url).replace(Regex("-\\d+x\\d+"), "")
        } else null
    }

    private val categories = listOf(
        Pair("Upload Terbaru", ""), 
        Pair("Indonesia Movie", "/country/indonesia"),
        Pair("Indonesia Series", "/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=indonesia&quality="),
        Pair("Drakor", "/?s=&search=advanced&post_type=tv&index=&orderby=&genre=drama&movieyear=&country=korea&quality="),
        Pair("VivaMax", "/country/philippines"),
        Pair("Movies", "/country/canada"),
        Pair("Ahok Movie", "/country/china")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homeItems = coroutineScope {
            categories.map { (title, urlPath) ->
                async {
                    val finalUrl = if (urlPath.isEmpty()) {
                        "$mainUrl/page/$page/"
                    } else if (urlPath.contains("?")) {
                        val split = urlPath.split("?")
                        "$mainUrl/page/$page/?${split[1]}"
                    } else {
                        "$mainUrl$urlPath/page/$page/"
                    }

                    try {
                        val document = app.get(finalUrl).document
                        val items = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
                        if (items.isNotEmpty()) HomePageList(title, items) else null
                    } catch (e: Exception) { null }
                }
            }.awaitAll().filterNotNull()
        }
        return newHomePageResponse(homeItems, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: ""
        val qualityText = this.selectFirst(".gmr-quality-item")?.text()?.trim() ?: "HD"
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = this@toSearchResult.selectFirst(".content-thumbnail img")?.getImageAttr()
            addQuality(qualityText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
            .select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        val plotText = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim() 
            ?: document.selectFirst("div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
        val yearText = document.selectFirst(".gmr-moviedata a[href*='year']")?.text()?.toIntOrNull()
        val ratingText = document.selectFirst("[itemprop='ratingValue']")?.text()?.trim()
        val tagsList = document.select(".gmr-moviedata a[href*='genre']").map { it.text() }
        val actorsList = document.select("[itemprop='actors'] a").map { it.text() }
        val trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")

        val epElements = document.select(".gmr-listseries a").filter { it.attr("href").contains("/eps/") }
        val isSeries = epElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        if (isSeries) {
            val episodes = epElements.mapNotNull { 
                newEpisode(it.attr("href")) { 
                    this.name = it.attr("title").removePrefix("Permalink ke ")
                    this.episode = Regex("(\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) { 
                this.posterUrl = poster
                this.plot = plotText
                this.year = yearText
                this.score = Score.from10(ratingText)
                this.tags = tagsList
                this.actors = actorsList.map { ActorData(Actor(it)) }
                if (!trailerUrl.isNullOrEmpty()) this.trailers.add(TrailerData(trailerUrl, null, false))
            }
        } else {
            return newMovieLoadResponse(title, url, type, url) { 
                this.posterUrl = poster
                this.plot = plotText
                this.year = yearText
                this.score = Score.from10(ratingText)
                this.tags = tagsList
                this.actors = actorsList.map { ActorData(Actor(it)) }
                if (!trailerUrl.isNullOrEmpty()) this.trailers.add(TrailerData(trailerUrl, null, false))
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val playerLinks = document.select(".muvipro-player-tabs a").mapNotNull { it.attr("href") }.toMutableList()
        if (playerLinks.isEmpty()) playerLinks.add(data)

        coroutineScope {
            playerLinks.distinct().mapIndexed { index, playerUrl ->
                async {
                    try {
                        val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                        val pageContent = app.get(fixedUrl, headers = mapOf("User-Agent" to UA_BROWSER)).text 
                        var handled = false

                        // [1] UNIVERSAL RPM LIVE & P2PPLAY (SERVER 1 & 5)
                        Regex("""(?i)src=["'](https?://([^/]+(?:rpmlive\.online|p2pplay\.pro)).*?(?:id=|/v/|/e/|#)([a-zA-Z0-9_-]+)[^"']*)["']""").findAll(pageContent).forEach {
                            handled = true
                            extractRpm(it.groupValues[3], it.groupValues[2], callback)
                        }

                        // [2] UNIVERSAL XVIDEOSHARING / CLONE IMAXSTREAMS (SERVER 3 & 4)
                        Regex("""(?i)(?:src|href)\s*=\s*["'](https://[^"']*/(?:e|embed)/[a-zA-Z0-9_-]+)["']""").findAll(pageContent).forEach {
                            val targetUrl = it.groupValues[1]
                            // Ditambahkan deteksi "hgcloud" dan domain dinamis
                            if (targetUrl.contains(Regex("""(?i)hglink|vibuxer|masukestin|cybervynx|niramirus|smoothpre|hgcloud"""))) {
                                handled = true
                                val isEmbed = targetUrl.contains("/embed/")
                                val videoId = targetUrl.split("/e/", "/embed/").last().substringBefore("?").trim('/')
                                val domain = java.net.URI(targetUrl).host
                                val directUrl = "https://$domain/${if (isEmbed) "embed" else "e"}/$videoId"
                                
                                extractXVideoSharing(directUrl, domain, videoId, callback)
                            }
                        }

                        // [3] KRAKENFILES
                        Regex("""(?i)src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""").findAll(pageContent).forEach { 
                            handled = true
                            extractKrakenManual(it.groupValues[1], callback) 
                        }

                        // [4] XSHOTCOK / HXFILE / SHORT.ICU
                        Regex("""(?i)src=["'](https://[^"']*(?:xshotcok|hxfile|short\.icu|mixdrop|newer\.stream)[^"']*)["']""").findAll(pageContent).forEach { 
                            handled = true
                            val url = it.groupValues[1]
                            if (url.contains("short.icu")) {
                                val id = url.substringAfterLast("/").substringBefore("?")
                                loadExtractor("https://abyss.to/?v=$id", subtitleCallback, callback)
                            } else {
                                // Ekstraktor Xshotcok diserahkan ke sistem universal Cloudstream
                                loadExtractor(url, subtitleCallback, callback)
                            }
                        }

                        // [5] FALLBACK JIKA TIDAK ADA YANG COCOK
                        if (!handled) {
                            val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(pageContent)
                            if (iframeMatch != null) {
                                loadExtractor(iframeMatch.groupValues[1], subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }.awaitAll()
        }
        return true
    }

    // --- UNIVERSAL XVIDEOSHARING LOGIC (API AJAX BYPASS) ---
    private suspend fun extractXVideoSharing(url: String, domain: String, videoId: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, headers = mapOf(
                "User-Agent" to UA_BROWSER,
                "Referer" to mainUrl, 
                "Origin" to "https://$domain",
                "Upgrade-Insecure-Requests" to "1"
            ))
            val doc = response.text
            val cookies = response.cookies
            
            val unpackedJs = multiUnpack(doc)
            var linkM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)

            if (linkM3u8 == null) {
                val hashStr = Regex("""(?:hash|'hash'|"hash"|name="hash")\s*[:=]\s*["']([^"']+)["']""").find(unpackedJs)?.groupValues?.get(1)
                if (hashStr != null) {
                    val apiUrl = "https://$domain/dl?op=view&file_code=$videoId&hash=$hashStr&embed=1&referer=$domain"
                    val apiRes = app.get(apiUrl, headers = mapOf(
                        "User-Agent" to UA_BROWSER,
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest"
                    ), cookies = cookies).text 
                    linkM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(apiRes)?.groupValues?.get(1)
                }
            }

            if (linkM3u8 != null) {
                var m3u8Asli = linkM3u8.replace("\\/", "/")
                if (m3u8Asli.startsWith("/")) m3u8Asli = "https://$domain$m3u8Asli"
                
                val serverName = domain.split(".").first().replaceFirstChar { it.uppercase() }
                callback.invoke(
                    newExtractorLink(
                        serverName,
                        "$serverName (Server)",
                        m3u8Asli,
                        ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "User-Agent" to UA_BROWSER,
                            "Referer" to "https://$domain/",
                            "Origin" to "https://$domain"
                        )
                    }
                )
            }
        } catch (e: Exception) {}
    }

    // --- UNIVERSAL RPM & P2PPLAY (AES DECRYPTION) ---
    private suspend fun extractRpm(id: String, host: String, callback: (ExtractorLink) -> Unit) {
        try {
            val h = mapOf("Host" to host, "User-Agent" to UA_BROWSER, "Referer" to "https://$host/", "Origin" to "https://$host", "X-Requested-With" to "XMLHttpRequest")
            val refDomain = mainUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            val videoApi = "https://$host/api/v1/video?id=$id&w=1920&h=1080&r=$refDomain"
            
            val encryptedRes = app.get(videoApi, headers = h).text
            val jsonStr = decryptAES(encryptedRes)
            
            val serverName = if (host.contains("p2pplay")) "P2PPlay" else "RPM Live"
            
            Regex(""""source"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1)?.let { link ->
                callback.invoke(newExtractorLink(serverName, serverName, link.replace("\\/", "/"), ExtractorLinkType.M3U8) { this.referer = "https://$host/" })
            }
            Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1)?.let { link ->
                callback.invoke(newExtractorLink("$serverName (Backup)", "$serverName (Backup)", "https://$host" + link.replace("\\/", "/"), ExtractorLinkType.M3U8) { this.referer = "https://$host/" })
            }
        } catch (e: Exception) {}
    }

    // --- KRAKENFILES ---
    private suspend fun extractKrakenManual(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val text = app.get(url, headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to mainUrl)).text
            val videoUrl = Regex("""<source[^>]+src=["'](https:[^"']+)["']""").find(text)?.groupValues?.get(1) ?: Regex("""src=["'](https:[^"']+/play/video/[^"']+)["']""").find(text)?.groupValues?.get(1)
            videoUrl?.let { clean ->
                callback.invoke(newExtractorLink("Krakenfiles", "Krakenfiles", clean.replace("&amp;", "&").replace("\\", ""), ExtractorLinkType.VIDEO) { this.referer = url; this.headers = mapOf("User-Agent" to UA_BROWSER) })
            }
        } catch (e: Exception) {}
    }

    // --- AES DECRYPTION ENGINE ---
    private fun decryptAES(hexText: String): String {
        if (hexText.isEmpty() || hexText.startsWith("{")) return hexText
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keyBytes = hexToBytes(RPM_KEY)
            val ivBytes = hexToBytes(RPM_IV)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            
            val decodedHex = hexToBytes(hexText.replace(Regex("[^0-9a-fA-F]"), ""))
            String(cipher.doFinal(decodedHex), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }
    
    private fun hexToBytes(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    // --- MULTI JAVASCRIPT UNPACKER ---
    private fun multiUnpack(html: String): String {
        var unpacked = html
        try {
            val packRegex = Regex("""\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            packRegex.findAll(html).forEach { match ->
                var p = match.groupValues[1]
                val a = match.groupValues[2].toInt()
                val c = match.groupValues[3].toInt()
                val k = match.groupValues[4].split("|")
                fun toBase(num: Int, base: Int): String {
                    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    var res = ""; var n = num; if (n == 0) return "0"
                    while (n > 0) { res = chars[n % base] + res; n /= base }
                    return res
                }
                for (i in c - 1 downTo 0) {
                    if (k.getOrNull(i)?.isNotEmpty() == true) {
                        p = p.replace(Regex("""\b${toBase(i, a)}\b"""), k[i])
                    }
                }
                unpacked += "\n" + p
            }
        } catch (e: Exception) {}
        return unpacked
    }
}
