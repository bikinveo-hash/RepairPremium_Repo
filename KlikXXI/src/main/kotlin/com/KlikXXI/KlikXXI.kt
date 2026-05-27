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
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/tv/" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data 
        } else {
            "${request.data}page/$page/"
        }

        val document = app.get(url).document
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
            val episodes = mutableListOf<Episode>()
            document.select(".gmr-season-block").forEach { block ->
                val seasonName = block.selectFirst(".season-title")?.text() ?: ""
                val seasonNum = Regex("\\d+").find(seasonName)?.value?.toIntOrNull()
                
                block.select(".gmr-season-episodes a").forEach { epNode ->
                    val epUrl = epNode.attr("href")
                    val epTitle = epNode.text()
                    
                    if (!epTitle.contains("Batch", true) && epUrl.contains("/eps/")) {
                        val epNum = Regex("(?i)(?:Eps|Episode)\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("\\d+").findAll(epTitle).lastOrNull()?.value?.toIntOrNull()
                        
                        episodes.add(newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
            }
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, dataId) {
                this.posterUrl = fixUrlNull(poster)
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
        // Ambil ID dari URL episode jika perlu
        val ajaxId = if (data.startsWith("http")) {
            app.get(data).document.selectFirst("#muvipro_player_content_id")?.attr("data-id") ?: return false
        } else data

        val ajaxResponse = app.post(
            url = "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "muvipro_player_content", "post_id" to ajaxId),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded"
            )
        ).text
        
        // Cari semua link iframe yang ada di respon AJAX
        val iframes = Regex("(?i)src=\"(.*?)\"").findAll(ajaxResponse).map { it.groupValues[1] }.toList()
        
        iframes.forEach { link ->
            val fixedLink = fixUrlNull(link) ?: return@forEach
            if (!fixedLink.contains("youtube", true)) {
                loadExtractor(fixedLink, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        
        val isTvSeries = this.selectFirst(".gmr-numbeps") != null || href.contains("/tv/")
        
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { 
                this.posterUrl = fixUrlNull(posterUrl) 
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { 
                this.posterUrl = fixUrlNull(posterUrl) 
            }
        }
    }
}
