package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JuraganFilmProvider : MainAPI() {
    override var name = "JuraganFilm"
    override var mainUrl = "https://tv44.juragan.film"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = listOf(
        mainPage("$mainUrl/", "Home")
    )

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

        // 1. Slider / Featured
        val sliderItems = parseSliderItems(doc)
        if (sliderItems.isNotEmpty()) {
            homeLists.add(HomePageList("Featured", sliderItems, isHorizontalImages = true))
        }

        // 2. Widget Sections
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

        // 3. Latest Movie
        val latestItems = parseLatestMovieItems(doc)
        if (latestItems.isNotEmpty()) {
            homeLists.add(HomePageList("Latest Movie", latestItems))
        }

        return if (homeLists.isEmpty()) null
        else newHomePageResponse(homeLists, hasNext = false)
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
        val doc = app.get(url).document

        // Judul (bersih dari h3.entry-title)
        val title = doc.selectFirst("h3.entry-title")?.text()?.trim()
            ?: doc.selectFirst(".entry-title")?.text()?.trim()
            ?: return null

        // Poster HD
        val posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")

        // Tahun rilis (dari meta, atau dari span.gmr-movie-genre)
        val year = doc.selectFirst("time[itemprop=dateCreated]")?.attr("datetime")
            ?.substringBefore("-")?.toIntOrNull()
            ?: doc.selectFirst("span.gmr-movie-genre:contains(Year:) a")?.text()?.trim()
                ?.toIntOrNull()

        // Sinopsis
        val plot = doc.selectFirst(".gmr-moviedata em p")?.text()?.trim()
            ?: doc.selectFirst(".entry-content p")?.text()?.trim()

        // Director (span[itemprop=director] span[itemprop=name] a)
        val directorElements = doc.select("span[itemprop=director] span[itemprop=name] a")
        val directorNames = directorElements.map { it.text().trim() }

        // Cast (span[itemprop=actors] span[itemprop=name] a)
        val castElements = doc.select("span[itemprop=actors] span[itemprop=name] a")
        val actors = castElements.map { el ->
            ActorData(Actor(el.text().trim()))
        }

        // Genre/Tags (span.gmr-movie-genre a[rel=category tag])
        val tags = doc.select("span.gmr-movie-genre a[rel=category tag]").map { it.text().trim() }

        // Trailer (a.gmr-trailer-popup, tidak selalu ada di detail)
        val trailerUrl = doc.selectFirst("a.gmr-trailer-popup")?.attr("href")

        // Deteksi tipe dari URL
        val type = if (url.contains("/film-seri/")) TvType.TvSeries else TvType.Movie

        // dataUrl untuk loadLinks: URL iframe player
        val iframeEl = doc.selectFirst("iframe[id^=jf-frame-]")
        val dataUrl = iframeEl?.attr("src") ?: url

        return when (type) {
            TvType.TvSeries -> {
                // Untuk series, daftar episode bisa diekstrak dari halaman season (jika ada)
                newTvSeriesLoadResponse(title, url, type, emptyList()) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = actors
                    if (!trailerUrl.isNullOrBlank()) {
                        addTrailer(trailerUrl, url)
                    }
                }
            }
            else -> {
                newMovieLoadResponse(title, url, type, dataUrl) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.actors = actors
                    if (!trailerUrl.isNullOrBlank()) {
                        addTrailer(trailerUrl, url)
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
        // data berisi URL iframe (file/?id=...)
        // Buka halaman iframe, mungkin ada extractor khusus atau langsung loadExtractor
        loadExtractor(data, referer = mainUrl, subtitleCallback, callback)
        return true
    }

    // =================== HELPER ===================
    private fun hasNextPage(doc: Document): Boolean {
        return doc.selectFirst("ul.page-numbers li a.next") != null
    }

    private fun parseSliderItems(doc: Document): List<SearchResponse> {
        val items = doc.select(".gmr-slider-content")
        return items.mapNotNull { element ->
            val linkEl = element.selectFirst("a.gmr-slide-titlelink") ?: return@mapNotNull null
            val title = linkEl.text().trim()
            val url = linkEl.attr("href").trim()
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            val imgEl = element.selectFirst("img.tns-lazy-img")
            val posterUrl = imgEl?.attr("data-src")?.ifBlank { imgEl?.attr("src") }

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
        val posterUrl = imgEl?.attr("data-src")?.ifBlank { imgEl?.attr("src") }

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
            val posterUrl = imgEl?.attr("data-src")?.ifBlank { imgEl?.attr("src") }

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
