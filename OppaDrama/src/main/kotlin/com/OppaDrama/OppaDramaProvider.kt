package com.OppaDrama

import com.fleeksoft.ksoup.Ksoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse

class OppaDramaProvider : MainAPI() {
    override var name = "OppaDrama"
    override var mainUrl = "http://45.11.57.192"
    override var lang = "id" // Bahasa utama Indonesia
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    // Menyusun menu beranda menggunakan utility mainPageOf[span_8](start_span)[span_8](end_span)
    override val mainPage = mainPageOf(
        Pair("$mainUrl/", "Drama Terbaru"),
        Pair("$mainUrl/genre/korean-drama/", "Drama Korea"),
        Pair("$mainUrl/movie/", "Film Bioskop")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Menangani paginasi pada URL beranda
        val url = if (page > 1) "${request.data}/page/$page/" else request.data
        val html = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text[span_9](start_span)[span_9](end_span)
        val document = Ksoup.parse(html)
        
        // Sesuaikan selector CSS jika struktur kontainer web berubah
        val items = document.select("article.item, div.box-item, div.list-drama .item").map { element ->
            val title = element.select("h2.entry-title a, .title a").text()
            val link = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            // Menggunakan helper untuk membuat object Movie/TV Search Response[span_10](start_span)[span_10](end_span)
            newMovieSearchResponse(title, link, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())[span_11](start_span)[span_11](end_span)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Melakukan request pencarian standar WordPress / Custom CMS (?s=query)
        val html = app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to USER_AGENT)).text[span_12](start_span)[span_12](end_span)
        val document = Ksoup.parse(html)
        
        return document.select("article.item, div.result-item").map { element ->
            val title = element.select(".title a, h2 a").text()
            val link = element.select("a").attr("href")
            val poster = element.select("img").attr("src")
            
            newMovieSearchResponse(title, link, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text[span_13](start_span)[span_13](end_span)
        val document = Ksoup.parse(html)

        val title = document.select("h1.entry-title, .title-drama").text()
        val poster = document.select("div.poster img, .cover img").attr("src")
        val plot = document.select("div.entry-content p, .synopsis").text()
        
        // Memeriksa apakah konten berbentuk serial (Drama/Series) atau single Movie
        val isSeries = document.select(".episode-list, .list-episodes, a[href*='/episode/']").isNotEmpty()

        return if (isSeries) {
            val episodesList = document.select(".episode-list a, .list-episodes a, .episode-item").mapIndexed { index, element ->
                val epUrl = element.attr("href")
                val epName = element.text().trim().ifBlank { "Episode ${index + 1}" }
                
                // Membuat object episode terstruktur menggunakan newEpisode[span_14](start_span)[span_14](end_span)
                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = index + 1
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodesList) {[span_15](start_span)[span_15](end_span)
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {[span_16](start_span)[span_16](end_span)
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
        // Mengambil HTML dari halaman nonton film atau episode drama
        val html = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).text[span_17](start_span)[span_17](end_span)
        val document = Ksoup.parse(html)
        
        // Mencari elemen iframe atau tombol server streaming
        val embedUrls = document.select("iframe[src], source[src], div.server-link").map { element ->
            element.attr("src").ifBlank { element.attr("data-src") }
        }

        var foundCounter = 0
        embedUrls.forEach { url ->
            if (url.isNotBlank()) {
                // Bersihkan skrip packed JS jika terdeteksi di dalam block halaman
                val unpackedHtml = getAndUnpack(html)[span_18](start_span)[span_18](end_span)
                
                // Mengirimkan URL embed ke ekstraktor internal Cloudstream secara otomatis[span_19](start_span)[span_19](end_span)
                val loaded = loadExtractor(url, subtitleCallback, callback)[span_20](start_span)[span_20](end_span)
                if (loaded) foundCounter++
            }
        }
        
        return foundCounter > 0
    }
}
