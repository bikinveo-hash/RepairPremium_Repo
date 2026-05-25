package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JuraganFilmProvider : MainAPI() {
    override var name = "JuraganFilm"
    override var mainUrl = "https://tv44.juragan.film"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override val usesWebView = true

    override val mainPage = listOf(
        mainPage("$mainUrl/", "Home")
    )

    // Cookie yang didapat dari WebView saat membuka halaman detail
    private var savedCookies: String? = null

    // =================== HOMEPAGE ===================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        if (page > 1) {
            val doc = app.get("$mainUrl/page/$page/").document
            val latestItems = parseLatestMovieItems(doc)
            return if (latestItems.isNotEmpty()) {
                newHomePageResponse("Latest Movie - Page $page", latestItems, hasNext = hasNextPage(doc))
            } else null
        }

        val doc = app.get(request.data).document
        val homeLists = mutableListOf<HomePageList>()

        // Slider / Featured
        val sliderItems = parseSliderItems(doc)
        if (sliderItems.isNotEmpty()) {
            homeLists.add(HomePageList("Featured", sliderItems, isHorizontalImages = true))
        }

        // Widget Sections
        val sections = doc.select(".home-widget")
        for (section in sections) {
            val sectionTitle = section.selectFirst(".homemodule-title")?.text()?.trim()
                ?: section.selectFirst(".widget-title")?.text()?.trim()
                ?: continue

            val items = section.select(".gmr-item-modulepost")
            val searchResponses = items.mapNotNull { parseWidgetItem(it) }

            if (searchResponses.isNotEmpty()) {
                homeLists.add(HomePageList(sectionTitle, searchResponses))
            }
        }

        // Latest Movie
        val latestItems = parseLatestMovieItems(doc)
        if (latestItems.isNotEmpty()) {
            homeLists.add(HomePageList("Latest Movie", latestItems))
        }

        val hasNext = hasNextPage(doc)
        return if (homeLists.isEmpty()) null
        else newHomePageResponse(homeLists, hasNext = hasNext)
    }

    // =================== SEARCH ===================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchUrl = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val doc = if (page > 1) {
            app.get("$mainUrl/page/$page/?s=$query&post_type[]=post&post_type[]=tv").document
        } else {
            app.get(searchUrl).document
        }

        val widgetItems = doc.select(".gmr-item-modulepost").mapNotNull { parseWidgetItem(it) }
        val latestItems = parseLatestMovieItems(doc)
        val results = (widgetItems + latestItems).distinctBy { it.url }

        val hasNext = hasNextPage(doc)
        return newSearchResponseList(results, hasNext)
    }

    // =================== LOAD (DETAIL) ===================
    override suspend fun load(url: String): LoadResponse? {
        // Gunakan WebViewResolver untuk mendapatkan cookie + pastikan halaman termuat sempurna
        val resolver = WebViewResolver(
            interceptUrl = Regex(".*"),
            timeout = 30_000L
        )
        val (finalRequest, _) = resolver.resolveUsingWebView(url)

        // Ambil cookie dari request final (jika ada)
        val cookieHeader = finalRequest?.header("Cookie")
        if (!cookieHeader.isNullOrBlank()) {
            savedCookies = cookieHeader
        }

        // Siapkan header dengan cookie yang didapat
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to url
        )
        if (!savedCookies.isNullOrBlank()) {
            headers["Cookie"] = savedCookies!!
        }

        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h3.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".entry-title")?.text()?.trim()
            ?: return null

        val posterUrl = fixPosterUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val year = doc.selectFirst("time[itemprop=dateCreated]")?.attr("datetime")
            ?.substringBefore("-")?.toIntOrNull()
            ?: doc.selectFirst("span.gmr-movie-genre a[href*='/tahun/']")?.text()?.trim()
                ?.toIntOrNull()

        val plot = doc.selectFirst(".gmr-moviedata:contains(Sinopsis:) .entry-content em, .gmr-moviedata em p")?.text()?.trim()
            ?: doc.selectFirst(".entry-content p")?.text()?.trim()

        val castElements = doc.select("span[itemprop=actors] span[itemprop=name] a")
        val actors = castElements.map { el ->
            ActorData(Actor(el.text().trim()))
        }

        val tags = doc.select("a[rel=category tag]").map { it.text().trim() }

        val trailerUrl = doc.selectFirst("a.gmr-trailer-popup")?.attr("href")

        val type = if (url.contains("/film-seri/")) TvType.TvSeries else TvType.Movie
        val iframeEl = doc.selectFirst("iframe[id^=jf-frame-]")
        val dataUrl = iframeEl?.attr("src") ?: url

        return when (type) {
            TvType.TvSeries -> {
                val episodeElements = doc.select(
                    ".entry-content .post-page-numbers, " +
                    "article .post-page-numbers, " +
                    ".jf-eps-wrap .post-page-numbers, " +
                    ".post-page-numbers"
                )
                val episodes = episodeElements.map { el ->
                    if (el.tagName() == "span") {
                        newEpisode(url) {
                            this.name = "Episode ${el.text().trim()}"
                            this.episode = el.text().trim().toIntOrNull()
                        }
                    } else {
                        newEpisode(el.attr("href")) {
                            this.name = "Episode ${el.text().trim()}"
                            this.episode = el.text().trim().toIntOrNull()
                        }
                    }
                }.distinctBy { it.episode }

                val hasEpisode1 = episodes.any { it.episode == 1 }
                val finalEpisodes = if (!hasEpisode1 && episodes.isNotEmpty()) {
                    listOf(
                        newEpisode(url) {
                            this.name = "Episode 1"
                            this.episode = 1
                        }
                    ) + episodes
                } else episodes

                val builder = if (finalEpisodes.isEmpty()) {
                    newTvSeriesLoadResponse(title, url, type, emptyList())
                } else {
                    newTvSeriesLoadResponse(title, url, type, finalEpisodes)
                }
                builder.apply {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = actors
                    if (!trailerUrl.isNullOrBlank()) {
                        this.trailers.add(
                            TrailerData(
                                extractorUrl = trailerUrl,
                                referer = url,
                                raw = false,
                                headers = mapOf("User-Agent" to USER_AGENT)
                            )
                        )
                    }
                }
                builder
            }
            else -> {
                newMovieLoadResponse(title, url, type, dataUrl) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = actors
                    if (!trailerUrl.isNullOrBlank()) {
                        this.trailers.add(
                            TrailerData(
                                extractorUrl = trailerUrl,
                                referer = url,
                                raw = false,
                                headers = mapOf("User-Agent" to USER_AGENT)
                            )
                        )
                    }
                }
            }
        }
    }

    // =================== LOAD LINKS ===================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl
        )
        if (!savedCookies.isNullOrBlank()) {
            headers["Cookie"] = savedCookies!!
        }

        val html = app.get(data, headers = headers).text
        val m3u8Regex = Regex("""https://cloud\.wth\.my\.id/\?id=[^"'\s]+\.m3u8""")
        val match = m3u8Regex.find(html)

        if (match != null) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "JuraganFilm - HLS",
                    url = match.value,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }
        return loadExtractor(data, referer = mainUrl, subtitleCallback, callback)
    }

    // =================== HELPER ===================
    private fun hasNextPage(doc: Document): Boolean {
        return doc.selectFirst("ul.page-numbers li a.next") != null
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url == null) return null
        return url.replace(Regex("-\\d+x\\d+(?=\\.(jpg|jpeg|png|webp)$)"), "")
    }

    private fun parseSliderItems(doc: Document): List<SearchResponse> {
        val items = doc.select(".gmr-slider-content")
        return items.mapNotNull { element ->
            val linkEl = element.selectFirst("a.gmr-slide-titlelink") ?: return@mapNotNull null
            val title = linkEl.text().trim()
            val url = linkEl.attr("href").trim()
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            val imgEl = element.selectFirst("img.tns-lazy-img")
            val rawPoster = imgEl?.attr("data-src")?.ifBlank { imgEl?.attr("src") }
            val posterUrl = fixPosterUrl(rawPoster)

            val episodeText = element.selectFirst(".strokeepisode")?.text()?.trim()
            val type = if (url.contains("/film-seri/") || (episodeText != null && episodeText.contains("EPS", ignoreCase = true))) {
                TvType.TvSeries
            } else TvType.Movie

            when (type) {
                TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) {
                    this.posterUrl = posterUrl
                }
                else -> newMovieSearchResponse(title, url, type) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    private fun parseWidgetItem(element: Element): SearchResponse? {
        val linkEl = element.selectFirst(".entry-title a") ?: return null
        val title = linkEl.text().trim()
        val url = linkEl.attr("href").trim()
        if (title.isEmpty() || url.isEmpty()) return null

        val imgEl = element.selectFirst("img.wp-post-image")
        val rawPoster = imgEl?.attr("data-src")?.ifBlank { imgEl?.attr("src") }
        val posterUrl = fixPosterUrl(rawPoster)

        val qualityText = element.selectFirst(".gmr-quality-item a")?.text()?.trim()
        val episodeText = element.selectFirst(".strokeepisode")?.text()?.trim()
        val dateEl = element.selectFirst("time[itemprop=dateCreated]")
        val year = dateEl?.attr("datetime")?.substringBefore("-")?.toIntOrNull()

        val type = if (url.contains("/film-seri/") || (episodeText != null && episodeText.contains("EPS", ignoreCase = true))) {
            TvType.TvSeries
        } else TvType.Movie

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) {
                this.posterUrl = posterUrl
                addQuality(qualityText ?: "")
            }
            else -> newMovieSearchResponse(title, url, type) {
                this.posterUrl = posterUrl
                addQuality(qualityText ?: "")
                this.year = year
            }
        }
    }

    private fun parseLatestMovieItems(doc: Document): List<SearchResponse> {
        val items = doc.select("#gmr-main-load article.item, #primary article.item")
        return items.mapNotNull { element ->
            val linkEl = element.selectFirst(".entry-title a") ?: return@mapNotNull null
            val title = linkEl.text().trim()
            val url = linkEl.attr("href").trim()
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            val imgEl = element.selectFirst("img.wp-post-image")
            val rawPoster = imgEl?.attr("data-src")?.ifBlank { imgEl?.attr("src") }
            val posterUrl = fixPosterUrl(rawPoster)

            val qualityText = element.selectFirst(".gmr-quality-item a")?.text()?.trim()
            val dateEl = element.selectFirst("time[itemprop=dateCreated]")
            val year = dateEl?.attr("datetime")?.substringBefore("-")?.toIntOrNull()

            val type = if (url.contains("/film-seri/")) TvType.TvSeries else TvType.Movie

            when (type) {
                TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) {
                    this.posterUrl = posterUrl
                    addQuality(qualityText ?: "")
                }
                else -> newMovieSearchResponse(title, url, type) {
                    this.posterUrl = posterUrl
                    addQuality(qualityText ?: "")
                    this.year = year
                }
            }
        }
    }
}
