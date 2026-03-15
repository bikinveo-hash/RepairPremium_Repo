package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new32.ngefilm.site" // DOMAIN TERBARU
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    // --- CONFIG ---
    private val UA_BROWSER = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

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

        // HANYA FOKUS KE SERVER HANERIX / VIBUXER / HGCLOUD
        for (playerUrl in playerLinks.distinct()) {
            try {
                val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                val pageContent = app.get(fixedUrl, headers = mapOf("User-Agent" to UA_BROWSER)).text 
                var handled = false

                // 1. Cek di dalam Iframe
                val hanerixIframe = Regex("""(?i)<iframe[^>]+src=["'](https://[^"']+(?:hglink|vibuxer|masukestin|cybervynx|niramirus|smoothpre|hgcloud|hanerix)[^"']*)["']""").find(pageContent)?.groupValues?.get(1)
                
                if (hanerixIframe != null) {
                    handled = true
                    val isEmbed = hanerixIframe.contains("/embed/")
                    val videoId = hanerixIframe.split("/e/", "/embed/").last().substringBefore("?").trim('/')
                    val domain = java.net.URI(hanerixIframe).host
                    val directUrl = "https://$domain/${if (isEmbed) "embed" else "e"}/$videoId"
                    
                    extractXVideoSharing(directUrl, domain, videoId, callback)
                }

                // 2. Cek di src/href biasa (Jika tidak pakai Iframe)
                if (!handled) {
                    Regex("""(?i)(?:src|href)\s*=\s*["'](https://[^"']*/(?:e|embed)/[a-zA-Z0-9_-]+)["']""").findAll(pageContent).forEach {
                        val targetUrl = it.groupValues[1]
                        if (targetUrl.contains(Regex("""(?i)hglink|vibuxer|masukestin|cybervynx|niramirus|smoothpre|hgcloud|hanerix"""))) {
                            val isEmbed = targetUrl.contains("/embed/")
                            val videoId = targetUrl.split("/e/", "/embed/").last().substringBefore("?").trim('/')
                            val domain = java.net.URI(targetUrl).host
                            val directUrl = "https://$domain/${if (isEmbed) "embed" else "e"}/$videoId"
                            
                            extractXVideoSharing(directUrl, domain, videoId, callback)
                        }
                    }
                }
            } catch (e: Exception) { 
                e.printStackTrace() 
            }
        }
        return true
    }

    // --- LOGIKA EKSTRAKSI HANERIX (FINAL) ---
    private suspend fun extractXVideoSharing(url: String, domain: String, videoId: String, callback: (ExtractorLink) -> Unit) {
        try {
            var currentUrl = url
            var currentDomain = domain
            
            // 1. Kunjungi URL target
            var response = app.get(currentUrl, headers = mapOf(
                "User-Agent" to UA_BROWSER,
                "Referer" to mainUrl, 
                "Origin" to "https://$currentDomain",
                "Upgrade-Insecure-Requests" to "1"
            ))
            var doc = response.text
            
            // 2. Cek Iframe Redirect (Bypass hgcloud -> hanerix)
            val hiddenIframe = Regex("""(?i)<iframe[^>]+src=["']([^"']+(?:vibuxer|hanerix|hgcloud)[^"']+)["']""").find(doc)?.groupValues?.get(1)
            if (hiddenIframe != null) {
                currentUrl = hiddenIframe
                currentDomain = java.net.URI(currentUrl).host
                
                response = app.get(currentUrl, headers = mapOf(
                    "User-Agent" to UA_BROWSER,
                    "Referer" to url
                ))
                doc = response.text
            }

            // 3. Bongkar JavaScript
            val unpackedJs = multiUnpack(doc)
            var linkM3u8: String? = null

            // 4. PANEN M3U8 (hls4, hls3, hls2)
            val hlsMatch = Regex("""["'](?:hls[234])["']\s*:\s*["']([^"']+)["']""").find(unpackedJs)
            if (hlsMatch != null) {
                linkM3u8 = hlsMatch.groupValues[1].replace("\\/", "/")
            }

            // Fallback M3U8
            if (linkM3u8 == null) {
                linkM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(unpackedJs)?.groupValues?.get(1)
            }

            // 5. Kirim Link ke Cloudstream
            if (linkM3u8 != null) {
                if (linkM3u8.startsWith("/")) linkM3u8 = "https://$currentDomain$linkM3u8"
                 
                val serverName = "Hanerix Server"
                callback.invoke(
                    newExtractorLink(
                        serverName,
                        serverName,
                        linkM3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "User-Agent" to UA_BROWSER,
                            "Referer" to "https://$currentDomain/",
                            "Origin" to "https://$currentDomain"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
