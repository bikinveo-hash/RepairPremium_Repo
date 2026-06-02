package com.LayarKacaProvider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.jsoup.nodes.Element

class LayarKacaProvider : MainAPI() {
    // URL terbaru sesuai log Termux
    override var mainUrl = "https://tv4.nontondrama.my" 
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Terpopuler",
        "$mainUrl/latest/page/" to "Upload Terbaru",
        "$mainUrl/rating/page/" to "Rating Tertinggi",
        "$mainUrl/series/ongoing/page/" to "Series Ongoing"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.video-list-wrapper ul.episode-list li, div.video-list-wrapper article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.poster-title, h2.poster-title, .title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-src").ifBlank { it.attr("src") } 
        }

        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/?s=$query").document
        return document.select("div.video-list-wrapper article, ul.episode-list li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, h2.title, .movie-info h1")?.text()?.replace("Nonton ", "")?.replace(" Streaming", "") ?: ""
        val poster = document.selectFirst(".detail .lazyload, .poster img")?.let { 
            it.attr("data-src").ifBlank { it.attr("src") } 
        }
        val tags = document.select(".tag-list a, .genre a").map { it.text() }
        val year = document.selectFirst(".info-tag span:contains(20)")?.text()?.toIntOrNull()
        val description = document.selectFirst(".synopsis, .description")?.text()?.trim()
        val tvType = if (url.contains("/series/") || document.select(".season-list").isNotEmpty()) TvType.TvSeries else TvType.Movie

        if (tvType == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select(".episode-list li a").forEach { eps ->
                val epHref = eps.attr("href")
                val epName = eps.text()
                val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                
                if (epHref.isNotBlank()) {
                    episodes.add(
                        Episode(
                            data = epHref,
                            name = epName,
                            episode = epNum
                        )
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Menggunakan perulangan "for" murni agar fungsi suspend di dalam berjalan mulus
        val elements = document.select("ul#player-list li a")
        for (element in elements) {
            val link = element.attr("data-url").ifBlank { element.attr("href") }
            
            if (link.isNotBlank()) {
                when {
                    link.contains("hydrax") -> {
                        HydraxExtractor().getUrl(link, data)?.forEach { callback.invoke(it) }
                    }
                    link.contains("turbovip") -> {
                        Lk21TurboExtractor().getUrl(link, data)?.forEach { callback.invoke(it) }
                    }
                    link.contains("cast") -> {
                        CastExtractor().getUrl(link, data)?.forEach { callback.invoke(it) }
                    }
                    link.contains("p2p") || link.contains("hownetwork") -> {
                        HowNetworkExtractor().getUrl(link, data)?.forEach { callback.invoke(it) }
                    }
                }
            }
        }
        return true
    }
}
