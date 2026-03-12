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
                this.posterUrl = poster; this.plot = plotText; this.year = yearText
                this.score = Score.from10(ratingText); this.tags = tagsList
                this.actors = actorsList.map { ActorData(Actor(it)) }
                if (!trailerUrl.isNullOrEmpty()) this.trailers.add(TrailerData(trailerUrl, null, false))
            }
        } else {
            return newMovieLoadResponse(title, url, type, url) { 
                this.posterUrl = poster; this.plot = plotText; this.year = yearText
                this.score = Score.from10(ratingText); this.tags = tagsList
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
            playerLinks.distinct().map { playerUrl ->
                async {
                    try {
                        val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                        val pageContent = app.get(fixedUrl, headers = mapOf("User-Agent" to UA_BROWSER)).text 

                        // [1] UNIVERSAL RPM LIVE & P2PPLAY (SERVER 1 & 5)
                        Regex("""(?i)https?://([^/]+(?:rpmlive\.online|p2pplay\.pro)).*?[#&?]id=([a-zA-Z0-9_]+)""").findAll(pageContent).forEach {
                            extractRpm(it.groupValues[2], it.groupValues[1], callback)
                        }

                        // [2] UNIVERSAL XVIDEOSHARING (SERVER 3 & 4)
                        Regex("""(?i)(?:src|href)\s*=\s*["'](https://[^"']*/(?:e|embed)/[a-zA-Z0-9_-]+)["']""").findAll(pageContent).forEach {
                            val targetUrl = it.groupValues[1]
                            if (targetUrl.contains(Regex("""(?i)hglink|vibuxer|masukestin|cybervynx|niramirus|smoothpre"""))) {
                                extractMasukestin(targetUrl, callback)
                            }
                        }

                        // [3] KRAKENFILES
                        Regex("""(?i)src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""").findAll(pageContent).forEach { 
                            extractKrakenManual(it.groupValues[1], callback) 
                        }

                        // [4] XSHOTCOK / HXFILE
                        Regex("""(?i)src=["'](https://[^"']*(?:xshotcok|hxfile)[^"']*)["']""").findAll(pageContent).forEach { 
                            extractXshotcok(it.groupValues[1], callback)
                        }

                        // [5] GENERIC & ABYSS CDN (SERVER 2)
                        Regex("""(?i)src=["'](https://[^"']*(?:short\.icu|mixdrop|newer\.stream)[^"']*)["']""").findAll(pageContent).forEach { 
                            val url = it.groupValues[1]
                            if (url.contains("short.icu")) {
                                val finalUrl = app.get(url, headers = mapOf("Referer" to fixedUrl)).url
                                if (finalUrl.contains("abyss")) {
                                    // Bypass Abyss Cloudflare & Websocket
                                    loadExtractor(finalUrl.replace("abysscdn.com", "abyss.to"), subtitleCallback, callback)
                                }
                            } else {
                                loadExtractor(url, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }.awaitAll()
        }
        return true
    }

    // --- UNIVERSAL XVIDEOSHARING LOGIC (Masukestin, Smoothpre, Niramirus, Cybervynx) ---
    private suspend fun extractMasukestin(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Melakukan GET untuk membongkar URL asli jika ada redirect (misal cybervynx -> niramirus)
            val initialRes = app.get(url, headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to mainUrl))
            val realUrl = initialRes.url
            val domain = realUrl.substringAfter("https://").substringBefore("/") 
            val doc = initialRes.text
            val cookies = initialRes.cookies
            
            // Ekstrak ID dari path /e/ atau /embed/
            val videoId = realUrl.split("/e/", "/embed/").last().substringBefore("?").substringBefore("\"").substringBefore("'")
            
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d.*?\.split\('\|'\)\)""")
            val packedCode = packedRegex.find(doc)?.value

            if (packedCode != null) {
                val unpackedJs = Unpacker.unpack(packedCode)
                var linkM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)

                if (linkM3u8 == null) {
                    val hashMatch = Regex("""hash\s*:\s*["']([^"']+)["']""").find(unpackedJs)
                    val hash = hashMatch?.groupValues?.get(1)

                    if (hash != null) {
                        val apiUrl = "https://$domain/dl?op=view&file_code=$videoId&hash=$hash&embed=1&referer=$domain"
                        val apiRes = app.get(apiUrl, headers = mapOf(
                            "User-Agent" to UA_BROWSER,
                            "Referer" to realUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        ), cookies = cookies).text 
                        
                        linkM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(apiRes)?.groupValues?.get(1)
                    }
                }

                if (linkM3u8 != null) {
                    linkM3u8 = linkM3u8!!.replace("\\/", "/")
                    if (linkM3u8!!.startsWith("/")) {
                        linkM3u8 = "https://$domain$linkM3u8"
                    }
                    val serverName = domain.split(".").first().replaceFirstChar { it.uppercase() }
                    callback.invoke(
                        newExtractorLink(
                            serverName,
                            "$serverName (Server)",
                            linkM3u8!!,
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
            } else {
                var directM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(doc)?.groupValues?.get(1)
                if (directM3u8 != null) {
                     directM3u8 = directM3u8!!.replace("\\/", "/")
                     if (directM3u8!!.startsWith("/")) directM3u8 = "https://$domain$directM3u8"
                     val serverName = domain.split(".").first().replaceFirstChar { it.uppercase() }
                     
                     callback.invoke(
                        newExtractorLink(
                            serverName,
                            "$serverName (Direct)",
                            directM3u8!!,
                            ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to "https://$domain/")
                        }
                    )
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace()
        }
    }

    // --- UNIVERSAL RPM & P2PPLAY (AES DECRYPTION) ---
    private suspend fun extractRpm(id: String, host: String, callback: (ExtractorLink) -> Unit) {
        try {
            val h = mapOf("Host" to host, "User-Agent" to UA_BROWSER, "Referer" to "https://$host/", "Origin" to "https://$host", "X-Requested-With" to "XMLHttpRequest")
            val refDomain = mainUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            val videoApi = "https://$host/api/v1/video?id=$id&w=1920&h=1080&r=$refDomain"
            
            val encryptedRes = app.get(videoApi, headers = h).text
            val jsonStr = if (encryptedRes.trim().startsWith("{")) encryptedRes else decryptAES(encryptedRes)
            
            val serverName = if (host.contains("p2pplay")) "P2PPlay" else "RPM Live"
            
            Regex(""""source"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1)?.let { link ->
                callback.invoke(newExtractorLink(serverName, serverName, link.replace("\\/", "/"), ExtractorLinkType.M3U8) { this.referer = "https://$host/" })
            }
            Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1)?.let { link ->
                callback.invoke(newExtractorLink("$serverName (Backup)", "$serverName (Backup)", "https://$host" + link.replace("\\/", "/"), ExtractorLinkType.M3U8) { this.referer = "https://$host/" })
            }
        } catch (e: Exception) {}
    }

    // --- XSHOTCOK / SERVER 5 BYPASS ---
    private suspend fun extractXshotcok(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to mainUrl)).text
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d.*?\.split\('\|'\)\)""")
            val packedCode = packedRegex.find(response)?.value

            if (packedCode != null) {
                val unpackedJs = Unpacker.unpack(packedCode)
                val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                val match = m3u8Regex.find(unpackedJs)

                match?.groupValues?.get(1)?.let { rawLink ->
                    val cleanLink = rawLink.replace("\\/", "/")
                    callback.invoke(
                        newExtractorLink("Xshotcok", "Xshotcok (Server)", cleanLink, ExtractorLinkType.M3U8) {
                            this.headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to url, "Origin" to "https://xshotcok.com")
                        }
                    )
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
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
    private fun decryptAES(text: String): String {
        if (text.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(RPM_KEY), "AES"), IvParameterSpec(hexToBytes(RPM_IV)))
            String(cipher.doFinal(hexToBytes(text.replace(Regex("[^0-9a-fA-F]"), ""))))
        } catch (e: Exception) { "" }
    }
    
    private fun hexToBytes(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        return data
    }

    // --- JAVASCRIPT UNPACKER ENGINE ---
    object Unpacker {
        fun unpack(packedJS: String): String {
            try {
                val startIdx = packedJS.indexOf("}('") 
                if (startIdx == -1) return packedJS
                val argsString = packedJS.substring(startIdx + 3)
                val splitIdx = argsString.lastIndexOf("'.split('|')")
                if (splitIdx == -1) return packedJS
                val coreData = argsString.substring(0, splitIdx)
                val parts = coreData.split(",")
                if (parts.size < 4) return packedJS 
                val dictRaw = parts.last().trim('\'', '"')
                val dictionary = dictRaw.split("|")
                val count = parts[parts.size - 2].toInt()
                val radix = parts[parts.size - 3].toInt()
                val payloadRaw = coreData.substring(0, coreData.lastIndexOf("," + radix))
                val payload = payloadRaw.trim('\'', '"')
               
                var decoded = payload
                fun encodeBase(n: Int, radix: Int): String {
                    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    var num = n
                    if (num == 0) return "0"
                    val sb = StringBuilder()
                    while (num > 0) {
                        sb.append(chars[num % radix])
                        num /= radix
                    }
                    return sb.reverse().toString()
                }
                for (i in count - 1 downTo 0) {
                    val token = encodeBase(i, radix)
                    val word = if (i < dictionary.size && dictionary[i].isNotEmpty()) dictionary[i] else token
                    decoded = decoded.replace(Regex("""\b$token\b"""), word)
                }
                return decoded.replace("\\", "")
            } catch (e: Exception) { return packedJS }
        }
    }
}
