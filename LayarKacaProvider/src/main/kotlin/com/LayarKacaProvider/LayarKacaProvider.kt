package com.LayarKacaProvider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv8.lk21official.cc"
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // --- MAIN PAGE ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = ArrayList<HomePageList>()

        fun addWidget(title: String, selector: String) {
            val list = document.select(selector).mapNotNull { toSearchResult(it) }
            if (list.isNotEmpty()) items.add(HomePageList(title, list))
        }

        addWidget("Film Terbaru", "div.widget[data-type='latest-movies'] li.slider article")
        addWidget("Series Unggulan", "div.widget[data-type='top-series-today'] li.slider article")
        addWidget("Horror Terbaru", "div.widget[data-type='latest-horror'] li.slider article")
        addWidget("Daftar Lengkap", "div#post-container article")

        return newHomePageResponse(items)
    }

    // --- SEARCH ---
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

            return json?.data?.mapNotNull { item ->
                val title = item.title
                val href = fixUrl(item.slug)
                val posterUrl = if (item.poster != null) "https://poster.lk21.party/wp-content/uploads/${item.poster}" else null
                val quality = getQualityFromString(item.quality)
                val isSeries = item.type?.contains("series", ignoreCase = true) == true

                if (isSeries) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.quality = quality
                        this.year = item.year
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.quality = quality
                        this.year = item.year
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("h3.poster-title, h2.entry-title, h1.page-title, div.title").text().trim()
        if (title.isEmpty()) return null
        val href = fixUrl(element.select("a").first()?.attr("href") ?: return null)
        val imgElement = element.select("img").first()
        val posterUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src")
        val quality = getQualityFromString(element.select("span.label").text())
        val isSeries = element.select("span.episode").isNotEmpty() || element.select("span.duration").text().contains("S.")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    // --- LOAD DETAIL ---
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

        val title = document.select("h1.entry-title, h1.page-title, div.movie-info h1").text().trim()
        val plot = document.select("div.synopsis, div.entry-content p").text().trim()
        val poster = document.select("meta[property='og:image']").attr("content").ifEmpty { document.select("div.poster img").attr("src") }
        val ratingText = document.select("span.rating-value").text().ifEmpty { document.select("div.info-tag").text() }
        val ratingScore = Regex("(\\d\\.\\d)").find(ratingText)?.value
        val year = document.select("span.year").text().toIntOrNull() ?: Regex("(\\d{4})").find(document.select("div.info-tag").text())?.value?.toIntOrNull()
        val tags = document.select("div.tag-list a, div.genre a").map { it.text() }
        val actors = document.select("div.detail p:contains(Bintang Film) a, div.cast a").map { ActorData(Actor(it.text(), "")) }
        val recommendations = document.select("div.related-video li.slider article, div.mob-related-series li.slider article").mapNotNull { toSearchResult(it) }

        // ==============================================
        // FITUR TRAILER EXTRACTION
        // ==============================================
        var trailerUrl = document.select("iframe[src*='youtube.com']").attr("src")
        if (trailerUrl.isNullOrEmpty()) {
            trailerUrl = document.select("a.btn-trailer, a:contains(Trailer)").attr("href")
        }
        if (trailerUrl.isNullOrEmpty()) {
            // Fallback: Tarik paksa ID Youtube dari HTML mentah jika disembunyikan
            trailerUrl = Regex("youtube\\.com/embed/([a-zA-Z0-9_-]+)").find(document.html())?.groupValues?.get(1) ?: ""
        }
        
        val ytIdRegex = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})")
        val ytId = ytIdRegex.find(trailerUrl)?.groupValues?.get(1) ?: trailerUrl.takeIf { it.length == 11 }
        
        val finalTrailerUrl = if (!ytId.isNullOrEmpty()) "https://www.youtube.com/watch?v=$ytId" else null
        // ==============================================

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

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from(ratingScore, 10); this.tags = tags; this.actors = actors; this.recommendations = recommendations
                
                // Memasukkan Trailer ke Movie Response
                if (!finalTrailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(
                        extractorUrl = finalTrailerUrl,
                        referer = null,
                        raw = false 
                    ))
                }
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from(ratingScore, 10); this.tags = tags; this.actors = actors; this.recommendations = recommendations
                
                // Memasukkan Trailer ke Series Response
                if (!finalTrailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(
                        extractorUrl = finalTrailerUrl,
                        referer = null,
                        raw = false
                    ))
                }
            }
        }
    }

    // --- LOAD LINKS (CLEAN UI & FIX 3001) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var currentUrl = data
        var document = app.get(currentUrl).document

        val redirectButton = document.select("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)").first()
        if (redirectButton != null && redirectButton.attr("href").isNotEmpty()) {
            currentUrl = fixUrl(redirectButton.attr("href"))
            document = app.get(currentUrl).document
        }

        val playerLinks = document.select("ul#player-list li a").map { it.attr("data-url").ifEmpty { it.attr("href") } }
        val mainIframe = document.select("iframe#main-player").attr("src")
        val allSources = (playerLinks + mainIframe).filter { it.isNotBlank() }.map { fixUrl(it) }.distinct()

        allSources.forEach { url ->
            val directLoaded = loadExtractor(url, currentUrl, subtitleCallback, callback)
            if (!directLoaded) {
                try {
                    val response = app.get(url, referer = currentUrl)
                    val wrapperUrl = response.url
                    val iframePage = response.document

                    // Nested Iframes
                    iframePage.select("iframe").forEach { 
                        loadExtractor(fixUrl(it.attr("src")), wrapperUrl, subtitleCallback, callback) 
                    }
                    
                    // Manual Unwrap (Kalau Extractor di atas gagal)
                    val scriptHtml = iframePage.html().replace("\\/", "/")
                    Regex("(?i)https?://[^\"]+\\.(m3u8|mp4)(?:\\?[^\"']*)?").findAll(scriptHtml).forEach { match ->
                        val streamUrl = match.value
                        val isM3u8 = streamUrl.contains("m3u8", ignoreCase = true)
                        
                        // Origin dynamic sesuai wrapper
                        val originUrl = try { URI(wrapperUrl).let { "${it.scheme}://${it.host}" } } catch(e:Exception) { "https://playeriframe.sbs" }
                        
                        val headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Referer" to wrapperUrl,
                            "Origin" to originUrl
                        )

                        callback.invoke(
                            newExtractorLink(
                                source = "LK21 VIP",
                                name = "LK21 VIP",
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = wrapperUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                    }
                } catch (e: Exception) {}
            }
        }
        return true
    }
}
