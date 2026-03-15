package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.coroutineScope

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new32.ngefilm.site" 
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
        val homeItems = mutableListOf<HomePageList>()
        for (cat in categories) {
            val (title, urlPath) = cat
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
                if (items.isNotEmpty()) homeItems.add(HomePageList(title, items))
            } catch (e: Exception) { }
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

        for (playerUrl in playerLinks.distinct()) {
            try {
                val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                val pageContent = app.get(fixedUrl, headers = mapOf("User-Agent" to UA_BROWSER)).text 
                
                // [1] DETEKSI KELUARGA HANERIX / HGCLOUD
                val allLinks = Regex("""(?i)(?:src|href)=["'](https?://[^"']+)["']""").findAll(pageContent).map { it.groupValues[1] }.toList()
                for (targetUrl in allLinks) {
                    if (targetUrl.contains(Regex("""(?i)hglink|vibuxer|masukestin|cybervynx|niramirus|smoothpre|hgcloud|hanerix"""))) {
                        val isEmbed = targetUrl.contains("/embed/")
                        val videoId = targetUrl.split("/e/", "/embed/").last().substringBefore("?").trim('/')
                        
                        val realDomain = "hanerix.com"
                        val directUrl = "https://$realDomain/${if (isEmbed) "embed" else "e"}/$videoId"
                        
                        extractHanerix(directUrl, realDomain, callback)
                    }
                }

                // [2] DETEKSI RPM LIVE & P2PPLAY
                Regex("""(?i)src=["'](https?://([^/]+(?:rpmlive\.online|p2pplay\.pro)).*?(?:id=|/v/|/e/|#)([a-zA-Z0-9_-]+)[^"']*)["']""").findAll(pageContent).forEach {
                    extractRpm(it.groupValues[3], it.groupValues[2], callback)
                }

                // [3] DETEKSI KRAKENFILES
                Regex("""(?i)src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""").findAll(pageContent).forEach { 
                    extractKrakenManual(it.groupValues[1], callback) 
                }

            } catch (e: Exception) { }
        }
        return true
    }

    // --- EKSTRAKTOR HANERIX ---
    private suspend fun extractHanerix(url: String, domain: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, headers = mapOf(
                "User-Agent" to UA_BROWSER,
                "Referer" to mainUrl, 
                "Origin" to "https://$domain",
                "Upgrade-Insecure-Requests" to "1"
            ))
            
            val unpackedJs = multiUnpack(response.text)
            
            var linkM3u8 = Regex("""["'](?:hls[234])["']\s*:\s*["']([^"']+)["']""").find(unpackedJs)?.groupValues?.get(1)
            if (linkM3u8 == null) {
                linkM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)
            }

            if (linkM3u8 != null) {
                var finalM3u8 = linkM3u8.replace("\\/", "/")
                if (finalM3u8.startsWith("/")) finalM3u8 = "https://$domain$finalM3u8"
                 
                callback.invoke(
                    newExtractorLink(
                        "Hanerix Server",
                        "Hanerix Server",
                        finalM3u8,
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
        } catch (e: Exception) { }
    }

    // --- EKSTRAKTOR RPM & P2PPLAY ---
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

    // --- EKSTRAKTOR KRAKENFILES ---
    private suspend fun extractKrakenManual(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val text = app.get(url, headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to mainUrl)).text
            val videoUrl = Regex("""<source[^>]+src=["'](https:[^"']+)["']""").find(text)?.groupValues?.get(1) ?: Regex("""src=["'](https:[^"']+/play/video/[^"']+)["']""").find(text)?.groupValues?.get(1)
            videoUrl?.let { clean ->
                callback.invoke(newExtractorLink("Krakenfiles", "Krakenfiles", clean.replace("&amp;", "&").replace("\\", ""), ExtractorLinkType.VIDEO) { this.referer = url; this.headers = mapOf("User-Agent" to UA_BROWSER) })
            }
        } catch (e: Exception) {}
    }

    // --- MESIN DEKRIPSI AES (UNTUK RPM/P2PPLAY) ---
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

    // --- JAVASCRIPT UNPACKER (UNTUK HANERIX) ---
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
