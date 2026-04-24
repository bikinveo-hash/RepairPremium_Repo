package com.Moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Memuat ExtractorLink, Qualities, ExtractorLinkType, dll.
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class MovieboxProvider : MainAPI() {
    override var name = "Moviebox"
    override var mainUrl = "https://moviebox.ph"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    
    private var cachedToken: String? = null

    private suspend fun getAuthToken(): String {
        cachedToken?.let { return it }

        try {
            val response = app.get(mainUrl)
            val cookieToken = response.cookies["mb_token"]
            
            if (cookieToken != null) {
                val cleanToken = cookieToken.replace("%22", "").replace("\"", "")
                cachedToken = "Bearer $cleanToken"
                return cachedToken!!
            }

            val htmlResponse = response.text
            val tokenRegex = """(eyJ[a-zA-Z0-9\-_]+\.[a-zA-Z0-9\-_]+\.[a-zA-Z0-9\-_]+)""".toRegex()
            val matchResult = tokenRegex.find(htmlResponse)
            
            if (matchResult != null) {
                cachedToken = "Bearer ${matchResult.groupValues[1]}"
                return cachedToken!!
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjMxMjI1Njk0MzU5NDYxNDAyNjQsImF0cCI6MywiZXh0IjoiMTc3NzAzMjk1OCIsImV4cCI6MTc4NDgwODk1OCwiaWF0IjoxNzc3MDMyNjU4fQ.7cMp7KjbAQy-VZMZGgIC2Z9KCHxkyL3Ib_UGhc6vFU8"
    }

    private suspend fun getApiHeaders(): Map<String, String> {
        return mapOf(
            "accept" to "application/json",
            "x-request-lang" to "en",
            "origin" to mainUrl,
            "referer" to "$mainUrl/",
            "authorization" to getAuthToken() 
        )
    }

    // Data Classes
    data class HomeResponse(@JsonProperty("data") val data: HomeData?)
    data class HomeData(@JsonProperty("operatingList") val operatingList: List<OperatingList>?)
    data class OperatingList(@JsonProperty("title") val title: String?, @JsonProperty("subjects") val subjects: List<Subject>?, @JsonProperty("banner") val banner: Banner?)
    data class Banner(@JsonProperty("items") val items: List<BannerItem>?)
    data class BannerItem(@JsonProperty("subject") val subject: Subject?)
    data class SearchApiResponse(@JsonProperty("data") val data: SearchData?)
    data class SearchData(@JsonProperty("subjectList") val subjectList: List<Subject>?)
    data class Subject(@JsonProperty("title") val title: String?, @JsonProperty("subjectId") val subjectId: String?, @JsonProperty("subjectType") val subjectType: Int?, @JsonProperty("detailPath") val detailPath: String?, @JsonProperty("releaseDate") val releaseDate: String?, @JsonProperty("cover") val cover: ImageInfo?)
    data class ImageInfo(@JsonProperty("url") val url: String?)
    data class DetailResponse(@JsonProperty("data") val data: DetailDataWrapper?)
    data class DetailDataWrapper(@JsonProperty("subject") val subject: DetailData?)
    data class DetailData(@JsonProperty("subjectId") val subjectId: String?, @JsonProperty("title") val title: String?, @JsonProperty("description") val description: String?, @JsonProperty("releaseDate") val releaseDate: String?, @JsonProperty("cover") val cover: ImageInfo?, @JsonProperty("imdbRatingValue") val imdbRatingValue: String?, @JsonProperty("subjectType") val subjectType: Int?, @JsonProperty("episodes") val episodes: List<EpisodeInfo>?)
    data class EpisodeInfo(@JsonProperty("episodeId") val episodeId: String?, @JsonProperty("title") val title: String?, @JsonProperty("episodeNum") val episodeNum: Int?, @JsonProperty("seasonNum") val seasonNum: Int?)
    data class RecResponse(@JsonProperty("data") val data: RecData?)
    data class RecData(@JsonProperty("items") val items: List<Subject>?)
    data class LinkData(val subjectId: String, val detailPath: String, val season: Int = 0, val episode: Int = 0)
    data class PlayResponse(@JsonProperty("data") val data: PlayData?)
    data class PlayData(@JsonProperty("streams") val streams: List<StreamItem>?)
    data class StreamItem(@JsonProperty("id") val id: String?, @JsonProperty("url") val url: String?, @JsonProperty("resolutions") val resolutions: String?, @JsonProperty("format") val format: String?)
    data class CaptionResponse(@JsonProperty("data") val data: CaptionData?)
    data class CaptionData(@JsonProperty("captions") val captions: List<CaptionItem>?)
    data class CaptionItem(@JsonProperty("lanName") val lanName: String?, @JsonProperty("url") val url: String?)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val response = app.get("$apiUrl/home?host=moviebox.ph", headers = getApiHeaders()).parsedSafe<HomeResponse>()
        val homeItems = mutableListOf<HomePageList>()
        response?.data?.operatingList?.forEach { section ->
            val searchResponses = mutableListOf<SearchResponse>()
            section.subjects?.forEach { it.toSearchResponse()?.let { searchResponses.add(it) } }
            section.banner?.items?.forEach { it.subject?.toSearchResponse()?.let { searchResponses.add(it) } }
            if (searchResponses.isNotEmpty()) homeItems.add(HomePageList(section.title ?: "", searchResponses))
        }
        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$apiUrl/subject/search?keyword=$query&page=0&perPage=20", headers = getApiHeaders()).parsedSafe<SearchApiResponse>()
        return response?.data?.subjectList?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get("$apiUrl/detail?detailPath=$url", headers = getApiHeaders()).parsedSafe<DetailResponse>()?.data?.subject ?: return null
        val recs = app.get("$apiUrl/subject/detail-rec?subjectId=${res.subjectId}&page=1&perPage=12", headers = getApiHeaders()).parsedSafe<RecResponse>()?.data?.items?.mapNotNull { it.toSearchResponse() }
        
        return if (res.subjectType == 1) {
            newMovieLoadResponse(res.title ?: "", url, TvType.Movie, LinkData(res.subjectId ?: "", url).toJson()) {
                this.posterUrl = res.cover?.url
                this.plot = res.description
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.recommendations = recs
                // Perbaikan 1: Gunakan properti score terbaru (Score.from)
                res.imdbRatingValue?.let { this.score = Score.from(it, 10) }
            }
        } else {
            val episodes = res.episodes?.map { 
                newEpisode(LinkData(res.subjectId ?: "", url, it.seasonNum ?: 1, it.episodeNum ?: 1).toJson()) { 
                    this.name = it.title
                    this.season = it.seasonNum
                    this.episode = it.episodeNum 
                } 
            } ?: emptyList()
            
            newTvSeriesLoadResponse(res.title ?: "", url, TvType.TvSeries, episodes) {
                this.posterUrl = res.cover?.url
                this.plot = res.description
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.recommendations = recs
                // Perbaikan 1: Gunakan properti score terbaru (Score.from)
                res.imdbRatingValue?.let { this.score = Score.from(it, 10) }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val linkData = tryParseJson<LinkData>(data) ?: return false
        val playRes = app.get("$apiUrl/subject/play?subjectId=${linkData.subjectId}&se=${linkData.season}&ep=${linkData.episode}&detailPath=${linkData.detailPath}", headers = getApiHeaders()).parsedSafe<PlayResponse>()
        
        playRes?.data?.streams?.forEach { stream ->
            val streamQuality = getQuality(stream.resolutions)
            
            // Perbaikan 2: Format newExtractorLink terbaru sesuai ExtractorApi.kt
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} ${stream.resolutions}p",
                    url = stream.url ?: "",
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = streamQuality
                    this.referer = mainUrl
                }
            )
            
            if (stream == playRes.data.streams.firstOrNull()) {
                app.get("$apiUrl/subject/caption?format=${stream.format}&id=${stream.id}&subjectId=${linkData.subjectId}&detailPath=${linkData.detailPath}", headers = getApiHeaders()).parsedSafe<CaptionResponse>()?.data?.captions?.forEach {
                    // Perbaikan 3: Menggunakan newSubtitleFile agar terhindar dari error deprecated
                    subtitleCallback.invoke(
                        newSubtitleFile(it.lanName ?: "Unknown", it.url ?: "")
                    )
                }
            }
        }
        return true
    }

    // Perbaikan 4: Atur year di dalam builder karena interface dasar SearchResponse tidak memiliki atribut year
    private fun Subject.toSearchResponse(): SearchResponse? {
        val titleStr = title ?: return null
        val pathStr = detailPath ?: return null
        val yearInt = releaseDate?.take(4)?.toIntOrNull()
        val poster = cover?.url

        return if (subjectType == 1) {
            newMovieSearchResponse(titleStr, pathStr) {
                this.posterUrl = poster
                this.year = yearInt
            }
        } else {
            newTvSeriesSearchResponse(titleStr, pathStr) {
                this.posterUrl = poster
                this.year = yearInt
            }
        }
    }
    
    private fun getQuality(res: String?): Int { 
        return when { 
            res?.contains("1080") == true -> Qualities.P1080.value
            res?.contains("720") == true -> Qualities.P720.value
            res?.contains("480") == true -> Qualities.P480.value
            else -> Qualities.P360.value 
        }
    }
}
