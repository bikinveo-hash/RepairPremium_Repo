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
        val doc = app.get(url).document

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
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to url
                                )
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
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to url
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    // =================== LOAD LINKS (EKSTRAKSI LANGSUNG) ===================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = URL iframe: https://tv44.juragan.film/file/?id=...
        // Referer dari halaman detail (disimpan di dataUrl, kita tidak punya langsung, tapi bisa pakai mainUrl)
        val html = app.get(data, referer = mainUrl).text
        
        // Cari HLS_URL di JavaScript
        val m3u8Regex = Regex("""https://cloud\.wth\.my\.id/\?id=[^"'\s]+\.m3u8""")
        val match = m3u8Regex.find(html)
        
        if (match != null) {
            val m3u8Url = match.value
            callback(
                newExtractorLink(
                    source = name,
                    name = "JuraganFilm - HLS",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }
        
        // Fallback: coba ambil dari JSON fallback (MP4)
        val fallbackRegex = Regex("""const FALLBACK_JSON_URL\s*=\s*"([^"]+)""")
        val fallbackMatch = fallbackRegex.find(html)
        if (fallbackMatch != null) {
            val fallbackUrl = fallbackMatch.groupValues[1]
            // Ambil sumber dari JSON
            val jsonUrl = if (fallbackUrl.startsWith("http")) fallbackUrl
                else "https://tv44.juragan.film/file/$fallbackUrl"
            try {
                val json = app.get(jsonUrl, referer = data).text
                // Parsing JSON sederhana: cari link pertama
                val linkRegex = Regex(""""link"\s*:\s*"([^"]+)"""")
                val linkMatch = linkRegex.find(json)
                if (linkMatch != null) {
                    val mp4Url = linkMatch.groupValues[1]
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "JuraganFilm - MP4",
                            url = mp4Url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = Qualities.P720.value
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                // lanjut
            }
        }
        
        // Fallback terakhir: loadExtractor
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
