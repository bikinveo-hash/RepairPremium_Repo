package com.LayarKacaProvider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv10.lk21official.cc"
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun decryptRC4(key: String, encryptedBase64: String): String {
        return try {
            val cipher = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0..255) {
                j = (j + s[i] + key[i % key.length].code) % 256
                val temp = s[i]
                s[i] = s[j]
                s[j] = temp
            } // PERBAIKAN: Kurung tutup ini sebelumnya ketinggalan!
            
            var i = 0
            j = 0
            val result = ByteArray(cipher.size)
            for (k in cipher.indices) {
                i = (i + 1) % 256
                j = (j + s[i]) % 256
                val temp = s[i]
                s[i] = s[j]
                s[j] = temp
                val kStream = s[(s[i] + s[j]) % 256]
                result[k] = ((cipher[k].toInt() and 0xFF) xor kStream).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            "" 
        }
    }

    private fun getCleanTitle(title: String): String {
        var clean = title.replace(Regex("(?i)(nonton serial|nonton film|nonton|sub indo|di lk21|lk21|layarkaca21)"), "")
        clean = clean.replace(Regex("(?i)\\bseason\\s*\\d+.*"), "")
        clean = clean.replace(Regex("\\(\\d{4}\\)"), "") 
        return clean.trim()
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var cleanUrl = url
        if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
        cleanUrl = cleanUrl.substringBefore("?") 
        return cleanUrl.replace(Regex("-\\d{2,4}x\\d{2,4}"), "")
    }

    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(
        val backdrop_path: String?,
        val poster_path: String?,
        val release_date: String?,
        val first_air_date: String?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = ArrayList<HomePageList>()

        suspend fun addWidget(sectionTitle: String, selector: String) {
            val elements = document.select(selector).toList()
            val list = coroutineScope {
                elements.map { async { toSearchResult(it) } }.awaitAll().filterNotNull()
            }
            if (list.isNotEmpty()) items.add(HomePageList(sectionTitle, list))
        }

        addWidget("Film Terbaru", "div.widget[data-type='latest-movies'] li.slider article")
        addWidget("Series Unggulan", "div.widget[data-type='top-series-today'] li.slider article")
        addWidget("Horror Terbaru", "div.widget[data-type='latest-horror'] li.slider article")
        addWidget("Daftar Lengkap", "div#post-container article")

        return newHomePageResponse(items)
    }

    private suspend fun toSearchResult(element: Element): SearchResponse? {
        val rawTitle = element.select("h3.poster-title, h2.entry-title, h1.page-title, div.title").text().trim()
        if (rawTitle.isEmpty()) return null
        val href = fixUrl(element.select("a").first()?.attr("href") ?: return null)
        
        val imgElement = element.select("img").first()
        val rawPoster = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")
        val fallbackPoster = fixPosterUrl(rawPoster)
        
        val cleanTitle = getCleanTitle(rawTitle)
        val yearText = element.select("div.year, span.year").text()
        val year = yearText.toIntOrNull() ?: Regex("\\b(\\d{4})\\b").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        var hdPoster: String? = null
        try {
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val tmdbRes = app.get("https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$encodedTitle").parsedSafe<TmdbSearchResponse>()
            val match = tmdbRes?.results?.firstOrNull { 
                val resYear = (it.release_date ?: it.first_air_date)?.take(4)?.toIntOrNull()
                year == null || resYear == null || resYear == year
            } ?: tmdbRes?.results?.firstOrNull()
            
            hdPoster = match?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        } catch(e: Exception) {}
        
        val posterUrl = hdPoster ?: fallbackPoster
        val quality = getQualityFromString(element.select("span.label").text())
        
        val isSeries = element.select("span.episode").isNotEmpty() || element.select("span.duration").text().contains("S.")

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.year = year
            }
        }
    }

    data class Lk21SearchResponse(val data: List<Lk21SearchItem>?)
    data class Lk21SearchItem(val title: String, val slug: String, val poster: String?, val type: String?, val year: Int?, val quality: String?)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "https://gudangvape.com/search.php?s=$query&page=1"
        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )

        try {
            val response = app.get(searchUrl, headers = headers).text
            val json = tryParseJson<Lk21SearchResponse>(response)

            return coroutineScope {
                json?.data?.map { item ->
                    async {
                        val cleanTitle = getCleanTitle(item.title)
                        val href = fixUrl(item.slug)
                        
                        val rawPoster = if (item.poster != null) "https://poster.lk21.party/wp-content/uploads/${item.poster}" else null
                        val fallbackPoster = fixPosterUrl(rawPoster)
                        
                        var hdPoster: String? = null
                        try {
                            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                            val tmdbRes = app.get("https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$encodedTitle").parsedSafe<TmdbSearchResponse>()
                            val match = tmdbRes?.results?.firstOrNull { 
                                val resYear = (it.release_date ?: it.first_air_date)?.take(4)?.toIntOrNull()
                                item.year == null || resYear == null || resYear == item.year
                            } ?: tmdbRes?.results?.firstOrNull()
                            
                            hdPoster = match?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        } catch(e: Exception) {}

                        val posterUrl = hdPoster ?: fallbackPoster
                        val quality = getQualityFromString(item.quality)
                    
                        val isSeries = item.type?.contains("series", ignoreCase = true) == true

                        if (isSeries) {
                            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                                this.quality = quality
                                this.year = item.year
                            }
                        } else {
                            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                                this.posterUrl = posterUrl
                                this.quality = quality
                                this.year = item.year
                            }
                        }
                    }
                }?.awaitAll()?.filterNotNull() ?: emptyList()
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var cleanUrl = fixUrl(url)
        var response = app.get(cleanUrl)
        var document = response.document

        if (document.title().contains("Loading", ignoreCase = true) || document.select("#loading").isNotEmpty()) {
            val path = try { URI(cleanUrl).path } catch(e: Exception) { "" }
            cleanUrl = if (path.contains("season") || path.contains("episode")) {
                "https://tv4.nontondrama.my$path"
            } else {
                "https://tv10.lk21official.cc$path"
            }
            response = app.get(cleanUrl)
            document = response.document
        }

        val redirectButton = document.select("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)").first()
        if (redirectButton != null) {
            var newUrl = redirectButton.attr("href")
            if (newUrl.isNotEmpty()) {
                cleanUrl = fixUrl(newUrl)
                if (cleanUrl.contains("series") || cleanUrl.contains("nontondrama")) {
                    val path = try { URI(cleanUrl).path } catch(e: Exception) { "" }
                    cleanUrl = "https://tv4.nontondrama.my$path"
                }
                response = app.get(cleanUrl)
                document = response.document
            }
        }

        val rawTitle = document.select("h1.entry-title, h1.page-title, div.movie-info h1").text().trim()
        val title = getCleanTitle(rawTitle) 
        
        val plot = document.select("div.synopsis, div.entry-content p").text().trim()
        val rawPoster = document.select("meta[property='og:image']").attr("content").ifEmpty { document.select("div.poster img").attr("src") }
        val fallbackPoster = fixPosterUrl(rawPoster)
        
        val ratingText = document.select("span.rating-value").text().ifEmpty { document.select("div.info-tag").text() }
        val ratingScore = Regex("(\\d\\.\\d)").find(ratingText)?.value
        
        val year = document.select("span.year").text().toIntOrNull() 
            ?: Regex("(\\d{4})").find(document.select("div.info-tag").text())?.value?.toIntOrNull()
            ?: Regex("\\b(\\d{4})\\b").find(rawTitle)?.value?.toIntOrNull()

        val tags = document.select("div.tag-list a, div.genre a").map { it.text() }
        val actors = document.select("div.detail p:contains(Bintang Film) a, div.cast a").map { ActorData(Actor(it.text(), "")) }
        val recommendations = document.select("div.related-video li.slider article, div.mob-related-series li.slider article").mapNotNull { toSearchResult(it) }

        val episodes = ArrayList<Episode>()
        
        // PERBAIKAN: Gunakan .html() karena Jsoup Elements tidak mendukung .data() secara langsung
        val jsonScript = document.select("script#season-data").html()

        if (jsonScript.isNotBlank()) {
            val slugs = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()
            val titles = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()
            val epNos = Regex("\"episode_no\"\\s*:\\s*(\\d+)").findAll(jsonScript).map { it.groupValues[1].toIntOrNull() }.toList()
            val sNos = Regex("\"s\"\\s*:\\s*(\\d+)").findAll(jsonScript).map { it.groupValues[1].toIntOrNull() }.toList()

            for (i in slugs.indices) {
                episodes.add(newEpisode(fixUrl(slugs[i])) {
                    this.name = titles.getOrNull(i) ?: "Episode ${i + 1}"
                    this.season = sNos.getOrNull(i)
                    this.episode = epNos.getOrNull(i)
                })
            }
        }
        
        if (episodes.isEmpty()) {
            document.select("ul.episodes li a, div.mob-list-eps a, .movie-action a[href*='episode']").forEach {
                val href = it.attr("href")
                if (href.isNotBlank() && href.contains("episode", ignoreCase = true)) {
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name = it.text().trim().ifEmpty { "Play Episode" }
                        this.episode = Regex("(?i)Episode\\s+(\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                    })
                }
            }
        }

        var tmdbPoster: String? = null
        var tmdbBackdrop: String? = null
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val tmdbSearchUrl = "https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$encodedTitle"
            val tmdbRes = app.get(tmdbSearchUrl).parsedSafe<TmdbSearchResponse>()
            
            val match = tmdbRes?.results?.firstOrNull { 
                val resYear = (it.release_date ?: it.first_air_date)?.take(4)?.toIntOrNull()
                year == null || resYear == null || resYear == year
            } ?: tmdbRes?.results?.firstOrNull()

            if (match != null) {
                tmdbPoster = match.poster_path?.let { "https://image.tmdb.org/t/p/original$it" }
                tmdbBackdrop = match.backdrop_path?.let { "https://image.tmdb.org/t/p/original$it" }
            }
        } catch (e: Exception) {}

        var trailerUrl = document.select("iframe[src*='youtube.com']").attr("src")
        if (trailerUrl.isNullOrEmpty()) {
            trailerUrl = document.select("a.btn-trailer, a:contains(Trailer)").attr("href")
        }
        if (trailerUrl.isNullOrEmpty()) {
             trailerUrl = Regex("youtube\\.com/embed/([a-zA-Z0-9_-]+)").find(document.html())?.groupValues?.get(1) ?: ""
        }
        
        val ytIdRegex = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})")
        val ytId = ytIdRegex.find(trailerUrl)?.groupValues?.get(1) ?: trailerUrl.takeIf { it.length == 11 }
        val finalTrailerUrl = if (!ytId.isNullOrEmpty()) "https://www.youtube.com/watch?v=$ytId" else null

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = tmdbPoster ?: fallbackPoster
                this.backgroundPosterUrl = tmdbBackdrop ?: tmdbPoster ?: fallbackPoster
                this.plot = plot
                this.year = year
                this.score = Score.from(ratingScore, 10)
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
                if (!finalTrailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(extractorUrl = finalTrailerUrl, referer = null, raw = false))
                }
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
                this.posterUrl = tmdbPoster ?: fallbackPoster
                this.backgroundPosterUrl = tmdbBackdrop ?: tmdbPoster ?: fallbackPoster
                this.plot = plot
                this.year = year
                this.score = Score.from(ratingScore, 10)
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
                if (!finalTrailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(extractorUrl = finalTrailerUrl, referer = null, raw = false))
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var currentUrl = data
        
        // PERBAIKAN: Gunakan .html()
        if (!currentUrl.contains("episode", ignoreCase = true) && currentUrl.contains("nontondrama")) {
            try {
                val doc = app.get(currentUrl).document
                val jsonScript = doc.select("script#season-data").html()
                val firstSlug = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"").find(jsonScript)?.groupValues?.get(1)
                if (firstSlug != null) {
                    currentUrl = fixUrl(firstSlug)
                }
            } catch (e: Exception) {}
        }

        val rawSources = mutableListOf<String>()

        try {
            val interceptorRegex = Regex("(?i)(playeriframe\\.sbs/iframe|emturbovid\\.com/e|turbovidhls\\.com/t|f16px\\.com/e|hydrax\\.net/watch|cloud\\.hownetwork\\.xyz/video\\.php|turboviplay\\.com/.*\\.m3u8)")
            val response = app.get(currentUrl, interceptor = WebViewResolver(interceptorRegex))
            val interceptedUrl = response.url
            
            if (interceptedUrl.isNotBlank() && interceptorRegex.containsMatchIn(interceptedUrl)) {
                rawSources.add(interceptedUrl)
            }
        } catch (e: Exception) {}

        if (rawSources.isEmpty()) {
            val document = app.get(currentUrl).document
            document.select("iframe").mapNotNull { it.attr("src") }.filter { 
                it.isNotBlank() && !it.contains("youtube", ignoreCase = true) && !it.contains("youtu.be", ignoreCase = true) 
            }.forEach {
                rawSources.add(it)
            }
        }

        val allSources = rawSources.distinct().map { fixUrl(it) }

        allSources.forEach { url ->
            var finalUrl = url
            
            if (finalUrl.contains("turboviplay.com") && finalUrl.endsWith(".m3u8", ignoreCase = true)) {
                callback.invoke(
                    newExtractorLink(
                        source = "TurboVIP",
                        name = "TurboVIP HD",
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://turbovidhls.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return@forEach
            }

            if (finalUrl.contains("playeriframe.sbs/iframe/p2p/")) {
                val id = finalUrl.substringAfter("p2p/").substringBefore("/")
                finalUrl = "https://cloud.hownetwork.xyz/video.php?id=$id"
            } else if (finalUrl.contains("playeriframe.sbs/iframe/turbovip/")) {
                val id = finalUrl.substringAfter("turbovip/").substringBefore("/")
                finalUrl = "https://turbovidhls.com/t/$id"
            } else if (finalUrl.contains("playeriframe.sbs")) {
                try {
                    val response = app.get(finalUrl, referer = currentUrl)
                    val iframe = response.document.selectFirst("iframe")?.attr("src")
                    if (!iframe.isNullOrBlank()) {
                        finalUrl = fixUrl(iframe)
                    }
                } catch (e: Exception) {}
            }

            when {
                finalUrl.contains("cloud.hownetwork.xyz") -> {
                    P2PExtractor().getUrl(finalUrl, currentUrl)?.forEach { callback.invoke(it) }
                }
                finalUrl.contains("emturbovid.com") || finalUrl.contains("turbovidhls.com") -> {
                    EmturbovidExtractor().getUrl(finalUrl, currentUrl)?.forEach { callback.invoke(it) }
                }
                finalUrl.contains("f16px.com") -> {
                    F16Extractor().getUrl(finalUrl, currentUrl)?.forEach { callback.invoke(it) }
                }
                else -> {
                    val directLoaded = loadExtractor(finalUrl, currentUrl, subtitleCallback, callback)
                    if (!directLoaded) {
                        try {
                            val response = app.get(finalUrl, referer = currentUrl)
                            val scriptHtml = response.document.html().replace("\\/", "/")
                            Regex("(?i)https?://[^\"]+\\.(m3u8|mp4)(?:\\?[^\"']*)?").findAll(scriptHtml).forEach { match ->
                                val streamUrl = match.value
                                val isM3u8 = streamUrl.contains("m3u8", ignoreCase = true)
                                callback.invoke(
                                    newExtractorLink(
                                        source = "LK21 VIP",
                                        name = "LK21 VIP",
                                        url = streamUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = finalUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        return true
    }
}
