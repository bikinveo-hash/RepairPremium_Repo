package com.KlikXXI

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KlikXXI : MainAPI() {
    override var mainUrl = "https://klikxxi.me"
    override var name = "KlikXXI"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movie/page/" to "Latest Movies",
        "$mainUrl/tv/page/" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.gmr-item-modulepost").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1.entry-title")?.text() ?: ""
        val title = rawTitle.replace(Regex("(?i)\\s*(Season\\s*\\d+.*|[0-9]{4})$"), "").trim()
        val poster = document.selectFirst(".gmr-movie-data figure img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        val plot = document.selectFirst(".entry-content[itemprop=description] p")?.text()
        val dataId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id") ?: ""
        val isTvSeries = url.contains("/tv/") || url.contains("/eps/")

        return if (isTvSeries) {
            val episodes = document.select(".gmr-season-episodes a.button").mapNotNull { epNode ->
                val epUrl = epNode.attr("href")
                val epTitle = epNode.text()
                if (epTitle.contains("Batch", true)) return@mapNotNull null
                val match = Regex("S(\\d+)Eps(\\d+)").find(epTitle)
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = match?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = match?.groupValues?.get(2)?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, dataId) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ambil ID untuk request AJAX
        val ajaxId = if (data.startsWith("http")) {
            app.get(data).document.selectFirst("#muvipro_player_content_id")?.attr("data-id") ?: return false
        } else data

        // Request ke AJAX endpoint
        val ajaxResponse = app.post(
            url = "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "muvipro_player_content", "post_id" to ajaxId)
        ).text
        
        // Di sini lu perlu parse iframe dari ajaxResponse, 
        // lalu panggil loadExtractor untuk setiap link yang ketemu.
        // Contoh: callback.invoke(newExtractorLink(...))
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        
        val isTvSeries = this.selectFirst(".gmr-numbeps") != null || href.contains("/tv/")
        return if (isTvSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }
}
