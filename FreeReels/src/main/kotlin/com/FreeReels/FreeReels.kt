package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FreeReels : MainAPI() {
    override var mainUrl = "https://freereels.net" // Sesuaikan jika ada link baru di dalam file Java-nya
    override var name = "FreeReels"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Ini adalah terjemahan dari file $CategoryPage / $NativeCategory
    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/tv-shows/page/" to "TV Shows"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        
        // TODO: Sesuaikan "div.post" dengan elemen HTML asli webnya
        val home = document.select("div.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.post").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst("div.poster img")?.attr("src")
        val plot = document.selectFirst("div.description")?.text()

        return newMovieLoadResponse(name, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Ini adalah terjemahan dari file $loadLinks$emit$1 dan $emit$2
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractedLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Biasanya web streaming menyembunyikan videonya di dalam iframe
        val iframe = document.selectFirst("iframe")?.attr("src")
        
        if (iframe != null) {
            // Ini yang tadinya berupa "$emit" di file Java-mu, 
            // di Kotlin cukup panggil fungsi bawaan Cloudstream ini:
            loadExtractor(iframe, data, subtitleCallback, callback)
        }
        return true
    }
}
