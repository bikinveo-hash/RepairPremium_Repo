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
        val rating = document.selectFirst(".gmr-meta-rating span")?.text()?.toRatingInt()

        // Cek apakah ini TV Series atau Movie
        return if (document.selectFirst(".gmr-listseries") != null) {
            val episodes = document.select(".gmr-season-episodes a.button-shadow").mapNotNull {
                val epHref = it.attr("href")
                val epName = it.text()
                
                // Filter link batch download
                if (epName.contains("Batch", true)) return@mapNotNull null
                
                // Ekstrak season dan episode dari teks (Contoh: S1Eps1)
                val sMatch = Regex("""S(\d+)""").find(epName)
                val eMatch = Regex("""Eps(\d+)""").find(epName)
                
                val seasonNum = sMatch?.groupValues?.get(1)?.toIntOrNull()
                val episodeNum = eMatch?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = episodeNum
                }
            }.reversed() // Dibalik agar urutan dari Ep 1

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isDataJob: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data di sini adalah URL (baik URL Movie atau URL Episode)
        val document = app.get(data).document
        
        // Ambil AJAX ID dari halaman
        val ajaxId = document.selectFirst(".gmr-server-wrap")?.attr("data-id") ?: return false

        // Ambil semua server yang tersedia
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

            // Cari URL di dalam iframe atau teks respon
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
        
        // Deteksi tipe berdasarkan badge episode atau URL
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
