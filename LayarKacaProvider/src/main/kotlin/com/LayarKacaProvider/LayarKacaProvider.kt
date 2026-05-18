package com.LayarKacaProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Data class ini untuk membaca JSON otomatis dari web
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

    // Mendefinisikan menu halaman depan
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
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}/page/$page"
        }

        val document = app.get(url).document

        val home = document.select("div.gallery-grid article").mapNotNull { element ->
            val title = element.selectFirst("h3.poster-title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val link = fixUrl(href) 
            val poster = element.selectFirst("img[itemprop=image]")?.attr("src")
            val qualityStr = element.selectFirst("span.label")?.text() 
            val ratingStr = element.selectFirst("span[itemprop=ratingValue]")?.text() 
            
            val episodeStr = element.selectFirst("span.episode")?.text() 
            val isTvSeries = episodeStr != null

            if (isTvSeries) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    addQuality(qualityStr ?: "")
                    // PERBAIKAN: Menggunakan Score.from(...)
                    ratingStr?.let { this.score = Score.from(it, 10) } 
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    addQuality(qualityStr ?: "")
                    // PERBAIKAN: Menggunakan Score.from(...)
                    ratingStr?.let { this.score = Score.from(it, 10) }
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

        // Mengambil JSON rapi dari bawah HTML
        val jsonString = document.selectFirst("script#watch-history-data")?.data()
        val watchData = jsonString?.let { tryParseJson<Lk21WatchData>(it) }

        if (watchData?.title == null) return null

        val plotText = document.selectFirst("div.synopsis")?.text()?.trim()
        val tagsList = document.select("div.tag-list span.tag a").map { it.text() }
        
        // Kita jadikan Film/Movie terlebih dahulu
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
            
            // PERBAIKAN: Menggunakan Score.from(...)
            watchData.rating?.let {
                this.score = Score.from(it, 10)
            }
        }
    }
}
