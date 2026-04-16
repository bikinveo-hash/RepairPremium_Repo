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
        
        // Mengatasi Lazy Loading Gambar
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        }
        val quality = this.selectFirst(".quality")?.text()

        // Deteksi Tipe (Movie atau TV)
        val isTvSeries = this.select(".metadata span").text().contains("TV") || href.contains("-season-")
        
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

        // 1. Top 10 Today
        val top10Items = document.select("#top10 .item").mapNotNull { it.toSearchResult() }
        if (top10Items.isNotEmpty()) homeList.add(HomePageList("Top 10 Today", top10Items))

        // 2. Recommended Movies
        val recMovies = document.select(".tab-body[data-id=movie] .item").mapNotNull { it.toSearchResult() }
        if (recMovies.isNotEmpty()) homeList.add(HomePageList("Recommended Movies", recMovies))

        // 3. Recommended TV Series
        val recTv = document.select(".tab-body[data-id=tv] .item").mapNotNull { it.toSearchResult() }
        if (recTv.isNotEmpty()) homeList.add(HomePageList("Recommended TV Series", recTv))

        // 4. Latest (Slider Dinamis)
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

        return document.select(".item").mapNotNull { element ->
            element.toSearchResult()
        }
    }

    // ==========================================
    // DETAIL HALAMAN (LOAD)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Ekstrak Informasi Dasar
        val title = document.selectFirst("h1.title, .detail .title")?.text() ?: return null
        val poster = document.selectFirst(".poster img")?.attr("src")
        val plot = document.selectFirst(".description, .plot")?.text()
        val year = document.selectFirst(".metadata span:contains(20)")?.text()?.toIntOrNull()
        
        // Deteksi apakah ini TV Series atau Movie
        val isTvSeries = document.select(".episodes, .seasons, .episode-list").isNotEmpty() || url.contains("-season-")

        if (isTvSeries) {
            val episodes = ArrayList<Episode>()
            // Asumsi struktur episode: <li><a href="...">Episode 1</a></li>
            document.select(".episode-list a, .episodes a").forEach { ep ->
                val epHref = ep.attr("href")
                val epName = ep.text()
                val epNum = ep.text().replace(Regex("[^0-9]"), "").toIntOrNull() // Ekstrak angka episode

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
            }
        } else {
            // Jika Movie, episodenya adalah link movie itu sendiri
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
            }
        }
    }

    // ==========================================
    // EKSTRAKSI LINK VIDEO (LOAD LINKS)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Buka halaman player (data berisi url episode atau movie)
        val document = app.get(data).document

        // Skenario 1: Web menggunakan iframe untuk memutar video (paling umum)
        document.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                // Meminta Cloudstream untuk mendeteksi Extractor (seperti Streamtape, Doodstream, dll)
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }

        // Skenario 2: Link server disembunyikan di tombol (misal: data-link="https://...")
        document.select(".server-btn, [data-link]").forEach { server ->
            val serverLink = server.attr("data-link")
            if (serverLink.isNotBlank()) {
                loadExtractor(serverLink, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
