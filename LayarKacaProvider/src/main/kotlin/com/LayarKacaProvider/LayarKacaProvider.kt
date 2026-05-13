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
import com.lagradost.api.Log

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv8.lk21official.cc"
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    // PENTING: Wajib true agar WebView bisa berjalan sebagai "Browser Hantu"
    override val usesWebView = true 

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

    data class NontonDramaEpisode(val s: Int? = null, val episode_no: Int? = null, val title: String? = null, val slug: String? = null)

    override suspend fun load(url: String): LoadResponse {
        var cleanUrl = fixUrl(url)
        var response = app.get(cleanUrl)
        var document = response.document

        val redirectButton = document.select("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)").first()
        if (redirectButton != null) {
            val newUrl = redirectButton.attr("href")
            if (newUrl.isNotEmpty()) {
                cleanUrl = fixUrl(newUrl)
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
        val jsonScript = document.select("script#season-data").html()

        if (jsonScript.isNotBlank()) {
            tryParseJson<Map<String, List<NontonDramaEpisode>>>(jsonScript)?.forEach { (_, epsList) ->
                epsList.forEach { epData ->
                    episodes.add(newEpisode(fixUrl(epData.slug ?: "")) {
                        this.name = epData.title ?: "Episode ${epData.episode_no}"
                        this.season = epData.s
                        this.episode = epData.episode_no
                    })
                }
            }
        } else {
            document.select("ul.episodes li a").forEach {
                episodes.add(newEpisode(fixUrl(it.attr("href"))) {
                    this.name = it.text()
                    val epNum = Regex("(?i)Episode\\s+(\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = epNum
                })
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
                    this.trailers.add(TrailerData(
                        extractorUrl = finalTrailerUrl, referer = null, raw = false 
                    ))
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
                    this.trailers.add(TrailerData(
                        extractorUrl = finalTrailerUrl, referer = null, raw = false
                    ))
                }
            }
        }
    }

    // --- LOAD LINKS (WEBVIEW RESOLVER + FULL HEADERS PLAYBACK) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var currentUrl = data
        val doc = app.get(currentUrl).document

        // Bypass Nontondrama
        val redirectButton = doc.select("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)").first()
        if (redirectButton != null && redirectButton.attr("href").isNotEmpty()) {
            currentUrl = fixUrl(redirectButton.attr("href"))
        }

        try {
            // WEBVIEW TAHAP 1: Menangkap URL rahasia hasil dari JS LK21
            val interceptRegex = Regex("(?i)(playeriframe\\.sbs|hownetwork\\.xyz|turbovidhls\\.com|abyssplayer\\.com)")
            
            val response = app.get(
                currentUrl,
                interceptor = WebViewResolver(interceptRegex),
                timeout = 30L 
            )
            
            val capturedUrl = response.url 
            Log.d("LK21", "WEBVIEW MENANGKAP URL: $capturedUrl")

            // Headers standar browser untuk mengelabuhi server video
            val defaultHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*"
            )

            // Jika WebView menangkap format "playeriframe.sbs/iframe/NAMA_SERVER/ID_VIDEO"
            if (capturedUrl.contains("playeriframe.sbs/iframe/")) {
                val parts = capturedUrl.split("/")
                val server = parts.getOrNull(parts.size - 2)
                val id = parts.lastOrNull()
                
                if (server != null && id != null) {
                    when (server.lowercase()) {
                        
                        // ===== SERVER P2P =====
                        "p2p" -> {
                            val apiUrl = "https://cloud.hownetwork.xyz/api2.php?id=$id"
                            val p2pHeaders = defaultHeaders + mapOf(
                                "Referer" to "https://playeriframe.sbs/",
                                "Origin" to "https://cloud.hownetwork.xyz",
                                "X-Requested-With" to "XMLHttpRequest"
                            )
                            val p2pForm = mapOf("r" to "https://playeriframe.sbs/", "d" to "cloud.hownetwork.xyz")
                            
                            val p2pRes = app.post(apiUrl, headers = p2pHeaders, data = p2pForm).text
                            val videoUrl = tryParseJson<Map<String, String>>(p2pRes)?.get("file")
                            
                            if (videoUrl != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "P2P VIP",
                                        name = "P2P VIP",
                                        url = videoUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        // INI KUNCI FIX ERROR 403 & M3U8 TS FILES:
                                        this.headers = defaultHeaders + mapOf(
                                            "Origin" to "https://cloud.hownetwork.xyz",
                                            "Referer" to "https://cloud.hownetwork.xyz/"
                                        )
                                    }
                                )
                            }
                        }
                        
                        // ===== SERVER TURBOVIP =====
                        "turbovip", "emturbovid" -> {
                            val turboUrl = "https://emturbovid.com/t/$id"
                            val turboRes = app.get(turboUrl, headers = defaultHeaders + mapOf("Referer" to "https://playeriframe.sbs/")).text
                            val m3u8Url = turboRes.substringAfter("var urlPlay = '").substringBefore("'")
                            
                            if (m3u8Url.contains(".m3u8")) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "TurboVip",
                                        name = "TurboVip HD",
                                        url = m3u8Url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        // INI KUNCI FIX ERROR 403 UNTUK TURBOVIP:
                                        this.headers = defaultHeaders + mapOf(
                                            "Origin" to "https://emturbovid.com",
                                            "Referer" to "https://emturbovid.com/"
                                        )
                                    }
                                )
                            }
                        }

                        // ===== SERVER F16 =====
                        "f16" -> {
                            val realUrl = "https://f16px.com/e/$id"
                            loadExtractor(realUrl, currentUrl, subtitleCallback, callback)
                        }

                        // ===== SERVER HYDRAX =====
                        "hydrax" -> {
                            val realUrl = "https://abyssplayer.com/$id"
                            // Kita arahkan ke file Extractor.kt milikmu kalau ada
                            loadExtractor(realUrl, currentUrl, subtitleCallback, callback)
                        }

                        else -> {
                            loadExtractor(capturedUrl, currentUrl, subtitleCallback, callback)
                        }
                    }
                }
            } else {
                // Jika WebView menangkap langsung file raw (seperti storage.googleapis.com)
                if (capturedUrl.contains(Regex("(?i)\\.(m3u8|mp4)"))) {
                    callback.invoke(
                        newExtractorLink(
                            source = "LK21 Raw",
                            name = "LK21 Raw Video",
                            url = capturedUrl,
                            type = if (capturedUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.headers = defaultHeaders + mapOf("Referer" to currentUrl)
                        }
                    )
                } else {
                    loadExtractor(capturedUrl, currentUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("LK21", "WebViewResolver Error: ${e.message}")
        }
        
        return true
    }
}
