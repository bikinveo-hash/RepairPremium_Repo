package com.yflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Yflix : MainAPI() {
    override var mainUrl = "https://yflix.to"
    override var name = "yFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ==========================================
    // FUNGSI BANTUAN: EKSTRAK ITEM FILM
    // ==========================================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.poster")?.attr("href") ?: return null
        val title = this.selectFirst(".info .title")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        }
        val quality = this.selectFirst(".quality")?.text()
        val isTvSeries = this.select(".metadata span")?.text()?.contains("TV") == true || href.contains("-season-")
        
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
                addQuality(quality ?: "")
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
                addQuality(quality ?: "")
            }
        }
    }

    // ==========================================
    // HALAMAN UTAMA (HOME)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/home").document 
        val homeList = ArrayList<HomePageList>()

        val top10Items = document.select("#top10 .item").mapNotNull { it.toSearchResult() }
        if (top10Items.isNotEmpty()) homeList.add(HomePageList("Top 10 Today", top10Items))

        val recMovies = document.select(".tab-body[data-id=movie] .item").mapNotNull { it.toSearchResult() }
        if (recMovies.isNotEmpty()) homeList.add(HomePageList("Recommended Movies", recMovies))

        val recTv = document.select(".tab-body[data-id=tv] .item").mapNotNull { it.toSearchResult() }
        if (recTv.isNotEmpty()) homeList.add(HomePageList("Recommended TV Series", recTv))

        document.select("section.slider").forEach { section ->
            val title = section.selectFirst(".section-title")?.text() ?: "Latest"
            val items = section.select(".item").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homeList.add(HomePageList(title, items))
            }
        }

        return newHomePageResponse(homeList)
    }

    // ==========================================
    // PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filter?keyword=$query"
        val document = app.get(url).document

        return document.select(".item").mapNotNull { it.toSearchResult() }
    }

    // ==========================================
    // DETAIL HALAMAN (LOAD)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text() ?: return null
        val poster = document.selectFirst(".poster img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        }
        val plot = document.selectFirst(".description")?.text()
        val year = document.selectFirst("span[itemprop=dateCreated]")?.text()?.substringBefore("-")?.toIntOrNull()
            ?: document.selectFirst(".metadata span:matches(\\d{4})")?.text()?.toIntOrNull()
            
        // Ambil nilai mentah, biarkan CS3 yang konversi otomatis
        val ratingString = document.selectFirst(".rating")?.attr("data-score")
        val actorsList = document.select("li:contains(Casts) a").map { it.text() }
        val tags = document.select("li:contains(Genres) a").map { it.text() }

        // Ekstrak Media ID
        val mediaId = document.selectFirst(".user-bookmark, .rating")?.attr("data-id") ?: return null
        val isTvSeries = url.contains("/tv/") || url.contains("season")
        val episodes = ArrayList<Episode>()

        // 1. Tembak API Episode
        val epsApiUrl = "$mainUrl/ajax/episodes/list?id=$mediaId"
        val epsResponse = app.get(epsApiUrl).text
        val epsDocument = org.jsoup.Jsoup.parse(epsResponse)
        
        epsDocument.select(".episode, a[data-id], li a").forEach { ep ->
            val epName = ep.text()
            val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
            val eid = ep.attr("data-id").ifEmpty { ep.attr("href").substringAfterLast(".") }

            if (eid.isNotBlank()) {
                episodes.add(
                    newEpisode(eid) { // Simpan Episode ID (eid) ke parameter data
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        }

        // Kalau list episodenya kosong (biasanya pada Movie), kita pakai Media ID sebagai cadangan
        if (episodes.isEmpty() && !isTvSeries) {
            episodes.add(newEpisode(mediaId))
        }

        // 2. Bangun halaman response
        return if (isTvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.tags = tags
                addScore(ratingString)
                addActors(actorsList)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.tags = tags
                addScore(ratingString)
                addActors(actorsList)
            }
        }
    }

    // ==========================================
    // EKSTRAKSI LINK VIDEO (LOAD LINKS)
    // ==========================================
    override suspend fun loadLinks(
        data: String, // Parameter 'data' ini berisi Episode ID (eid) dari fungsi load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // 3. Tembak API Server List menggunakan Episode ID
        val serverApiUrl = "$mainUrl/ajax/links/list?eid=$data"
        val serverJsonResponse = app.get(serverApiUrl).parsedSafe<Map<String, String>>()
        val serverHtml = serverJsonResponse?.get("result") ?: return false
        val serverDocument = org.jsoup.Jsoup.parse(serverHtml)

        // 4. Looping untuk mengambil masing-masing Server
        serverDocument.select(".server").forEach { server ->
            val lid = server.attr("data-lid")
            if (lid.isNotBlank()) {
                
                // 5. Tembak API View rahasia untuk mendapatkan link iframe (Rapidshare, dll)
                val viewApiUrl = "$mainUrl/ajax/links/view?id=$lid" 
                
                try {
                    val viewResponse = app.get(viewApiUrl).parsedSafe<Map<String, String>>()
                    val iframeUrl = viewResponse?.get("link") ?: viewResponse?.get("url") ?: viewResponse?.get("src")
                    
                    if (!iframeUrl.isNullOrBlank()) {
                        // 6. Serahkan URL Iframe ke sistem Extractor bawaan CloudStream
                        loadExtractor(iframeUrl, viewApiUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return true
    }
}
