package com.LayarKacaProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Data class ini digunakan untuk mem-parsing data JSON yang tertanam di halaman HTML web LK21
data class Lk21WatchData(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("rating") val rating: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("year") val year: Int?
)

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://mamamas.xyz"
    override var name = "LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    // Daftar menu kategori halaman depan
    override val mainPage = mainPageOf(
        "/latest" to "Terbaru",
        "/latest-series" to "Series Terbaru",
        "/top-series-today" to "Series Unggulan",
        "/populer" to "Populer",
        "/nonton-bareng-keluarga" to "Nobar Keluarga",
        "/genre/action" to "Action",
        "/genre/horror" to "Horror"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Mengatur pagination
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}/page/$page"
        }

        val document = app.get(url).document

        // Scrape halaman depan
        val home = document.select("div.gallery-grid article").mapNotNull { element ->
            val title = element.selectFirst("h3.poster-title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val link = fixUrl(href)
            val poster = element.selectFirst("img[itemprop=image]")?.attr("src")
            val qualityStr = element.selectFirst("span.label")?.text()
            val ratingStr = element.selectFirst("span[itemprop=ratingValue]")?.text()
            
            // Mendeteksi apakah konten ini Series
            val episodeStr = element.selectFirst("span.episode")?.text()
            val isTvSeries = episodeStr != null

            if (isTvSeries) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    addQuality(qualityStr ?: "")
                    ratingStr?.let { addScore(it, 10) }
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    addQuality(qualityStr ?: "")
                    ratingStr?.let { addScore(it, 10) }
                }
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Membaca data JSON yang ada di script paling bawah HTML
        val jsonString = document.selectFirst("script#watch-history-data")?.data()
        val watchData = jsonString?.let { tryParseJson<Lk21WatchData>(it) }

        // Jika tidak ditemukan data judul, batalkan fungsi
        if (watchData?.title == null) return null

        // 2. Mengambil detail tambahan menggunakan CSS selector dari Jsoup
        val plotText = document.selectFirst("div.synopsis")?.text()?.trim()
        val tagsList = document.select("div.tag-list span.tag a").map { it.text() }
        
        // --- CATATAN UNTUK NANTI: Di sinilah kita akan menambahkan deteksi TV Series ---
        // val isTvSeries = ... (KITA AKAN TAMBAHKAN SETELAH DAPAT HTML SERIES)

        // 3. Mengembalikan format Movie
        return newMovieLoadResponse(
            name = watchData.title,
            url = url,
            type = TvType.Movie,
            dataUrl = url 
        ) {
            this.posterUrl = watchData.poster
            this.year = watchData.year
            this.plot = plotText
            this.tags = tagsList
            
            // Mengubah teks rating (contoh: "7.3") menjadi skor UI
            watchData.rating?.let {
                addScore(it, 10)
            }
        }
    }
}
