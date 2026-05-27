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
        val title = document.selectFirst("h1.entry-title")?.text()?.replace("Streaming Film", "")?.trim() ?: ""
        val poster = document.selectFirst(".content-thumbnail img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }
        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val year = document.selectFirst(".gmr-moviedata:contains(Year) a")?.text()?.toIntOrNull()
        val description = document.selectFirst(".entry-content-single p")?.text()
        
        // Perbaikan: Ambil rating sebagai Double untuk Score API
        val ratingValue = document.selectFirst(".gmr-rating-item")?.text()?.trim()?.toDoubleOrNull()

        return if (document.selectFirst(".gmr-listseries") != null) {
            val episodes = document.select(".gmr-season-episodes a.button-shadow").mapNotNull {
                val epHref = it.attr("href")
                val epName = it.text()
                
                if (epName.contains("Batch", true)) return@mapNotNull null
                
                val sMatch = Regex("""S(\d+)""").find(epName)
                val eMatch = Regex("""Eps(\d+)""").find(epName)
                
                newEpisode(epHref) {
                    this.name = epName
                    this.season = sMatch?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = eMatch?.groupValues?.get(1)?.toIntOrNull()
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                // Perbaikan: Gunakan properti score dengan Score.from10
                this.score = Score.from10(ratingValue)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                // Perbaikan: Gunakan properti score dengan Score.from10
                this.score = Score.from10(ratingValue)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isDataJob: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val ajaxId = document.selectFirst(".gmr-server-wrap")?.attr("data-id") ?: return false
        val servers = document.select("ul.muvipro-player-tabs li a").mapNotNull {
            val id = it.attr("href").replace("#", "")
            if (id.startsWith("p")) id else null
        }

        servers.forEach { serverId ->
            val serverNum = serverId.replace("p", "")
            val response = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to serverNum,
                    "post_id" to ajaxId
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            val iframeUrl = Regex("""src='([^"']+)""").find(response)?.groupValues?.get(1)
                ?: Regex("""src="([^"']+)""").find(response)?.groupValues?.get(1)

            iframeUrl?.let { url ->
                val finalUrl = if (url.startsWith("//")) "https:$url" else url
                loadExtractor(finalUrl, data, subtitleCallback, callback)
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
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }
}
