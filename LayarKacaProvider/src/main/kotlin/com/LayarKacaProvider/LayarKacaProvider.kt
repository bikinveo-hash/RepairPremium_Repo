package com.LayarKacaProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

data class Lk21WatchData(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("rating") val rating: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("year") val year: Int?
)

data class Lk21Episode(
    @JsonProperty("s") val season: Int?,
    @JsonProperty("episode_no") val episode_no: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("slug") val slug: String?
)

data class Lk21SearchResponse(
    @JsonProperty("totalPages") val totalPages: Int?,
    @JsonProperty("data") val data: List<Lk21SearchItem>?
)

data class Lk21SearchItem(
    @JsonProperty("title") val title: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("rating") val rating: Double?
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
            val qualityStr = element.selectFirst("span.label")?.text() ?: ""
            val ratingStr = element.selectFirst("span[itemprop=ratingValue]")?.text()

            val isTvSeries = element.selectFirst("span.episode") != null

            if (isTvSeries) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    addQuality(qualityStr)
                    ratingStr?.let { this.score = Score.from(it, 10) }
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    addQuality(qualityStr)
                    ratingStr?.let { this.score = Score.from(it, 10) }
                }
            }
        }

        return newHomePageResponse(HomePageList(request.name, home, false), hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchUrl = "https://gudangvape.com/search.php?s=$query&page=$page"
        val resText = app.get(searchUrl).text
        val response = tryParseJson<Lk21SearchResponse>(resText) ?: return null

        val results = response.data?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val link = "$mainUrl/$slug"
            val posterUrl = item.poster?.let { "https://static-jpg.showcdnx.com/wp-content/uploads/$it" }
            
            if (item.type == "series") {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    addQuality(item.quality ?: "")
                    item.rating?.let { this.score = Score.from(it, 10) }
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = posterUrl
                    addQuality(item.quality ?: "")
                    item.rating?.let { this.score = Score.from(it, 10) }
                }
            }
        } ?: emptyList()

        return newSearchResponseList(results, hasNext = page < (response.totalPages ?: 1))
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val jsonString = document.selectFirst("script#watch-history-data")?.data()
        val watchData = jsonString?.let { tryParseJson<Lk21WatchData>(it) } ?: return null

        val plotText = document.selectFirst("div.synopsis")?.text()?.trim()
        val tagsList = document.select("div.tag-list span.tag a").map { it.text() }
        
        val seasonDataString = document.selectFirst("script#season-data")?.data()

        if (seasonDataString != null) {
            val seasonData = tryParseJson<Map<String, List<Lk21Episode>>>(seasonDataString)
            val episodes = mutableListOf<Episode>()

            seasonData?.forEach { (_, epsList) ->
                episodes.addAll(epsList.mapNotNull { ep ->
                    val epSlug = ep.slug ?: return@mapNotNull null
                    newEpisode("$mainUrl/$epSlug") {
                        this.name = ep.title
                        this.season = ep.season
                        this.episode = ep.episode_no
                    }
                })
            }

            return newTvSeriesLoadResponse(
                name = watchData.title ?: "",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = watchData.poster
                this.year = watchData.year
                this.plot = plotText
                this.tags = tagsList
                watchData.rating?.let { this.score = Score.from(it, 10) }
            }
        } else {
            return newMovieLoadResponse(
                name = watchData.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = url 
            ) {
                this.posterUrl = watchData.poster
                this.year = watchData.year
                this.plot = plotText
                this.tags = tagsList
                watchData.rating?.let { this.score = Score.from(it, 10) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        val serverElements = document.select("ul#player-list li a")
        
        serverElements.forEach { element ->
            val serverName = element.attr("data-server").lowercase()
            val serverId = element.attr("data-url")
            
            if (serverId.isNotBlank() && serverName.isNotBlank()) {
                val iframePopupUrl = "https://playeriframe.sbs/mobile/$serverName/$serverId/embed"
                
                try {
                    // Masuk ke dalam halaman popup untuk mencari link iframe pihak ketiga aslinya
                    val iframeDoc = app.get(iframePopupUrl, referer = data).document
                    // Konversi secara paksa menjadi string agar tidak terdeteksi sebagai 'Any' oleh compiler
                    val realIframeSrc: String? = iframeDoc.selectFirst("div.embed-container iframe")?.attr("src")?.toString()
                    
                    if (!realIframeSrc.isNullOrEmpty()) {
                        // Memanggil fungsi extractor bawaan dengan penamaan parameter yang jelas
                        loadExtractor(
                            url = realIframeSrc,
                            referer = iframePopupUrl,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    }
                } catch (e: Exception) {
                    // Abaikan jika server down / timeout dan lanjut memproses server berikutnya
                }
            }
        }

        return true
    }
}
