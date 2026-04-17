package com.sflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// ==========================================
// DATA CLASSES UNTUK PARSING JSON
// ==========================================

data class SflixResponse(@JsonProperty("data") val data: SflixData? = null)
data class SflixData(
    @JsonProperty("subjectList") val subjectList: List<SflixSubject>? = null,
    @JsonProperty("pager") val pager: SflixPager? = null
)
data class SflixSubject(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("subjectType") val subjectType: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("cover") val cover: SflixCover? = null,
    @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
    @JsonProperty("detailPath") val detailPath: String? = null
)
data class SflixCover(@JsonProperty("url") val url: String? = null)
data class SflixPager(@JsonProperty("hasMore") val hasMore: Boolean? = null)

data class SflixSearchAPIResponse(@JsonProperty("data") val data: SflixSearchData? = null)
data class SflixSearchData(@JsonProperty("items") val items: List<SflixSubject>? = null)

data class SflixDetailResponse(@JsonProperty("data") val data: SflixDetailData? = null)
data class SflixDetailData(
    @JsonProperty("subject") val subject: SflixSubjectDetail? = null,
    @JsonProperty("stars") val stars: List<SflixStar>? = null,
    @JsonProperty("resource") val resource: SflixResource? = null
)
data class SflixSubjectDetail(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("subjectType") val subjectType: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("cover") val cover: SflixCover? = null,
    @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null
)
data class SflixStar(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("avatarUrl") val avatarUrl: String? = null
)
data class SflixResource(@JsonProperty("seasons") val seasons: List<SflixSeason>? = null)
data class SflixSeason(
    @JsonProperty("se") val se: Int? = null,
    @JsonProperty("resolutions") val resolutions: List<SflixResolution>? = null
)
data class SflixResolution(@JsonProperty("epNum") val epNum: Int? = null)

data class SflixLinkData(
    val subjectId: String,
    val detailPath: String,
    val seasonNumber: Int,
    val episodeNumber: Int
)

data class SflixPlayResponse(@JsonProperty("data") val data: SflixPlayData? = null)
data class SflixPlayData(@JsonProperty("streams") val streams: List<SflixStream>? = null)
data class SflixStream(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("resolutions") val resolutions: String? = null
)
data class SflixSubtitleResponse(@JsonProperty("data") val data: SflixSubtitleData? = null)
data class SflixSubtitleData(@JsonProperty("captions") val captions: List<SflixCaption>? = null)
data class SflixCaption(
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("url") val url: String? = null
)

// ==========================================
// SFLIX PROVIDER CLASS
// ==========================================

class SflixProvider : MainAPI() {
    override var name = "Sflix"
    override var mainUrl = "https://sflix.film"
    private val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val hardcodedBearer = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjc5OTMyOTY0NzY4NjcyODE0MDgsImF0cCI6MywiZXh0IjoiMTc3NjQ1MDM1OSIsImV4cCI6MTc4NDIyNjM1OSwiaWF0IjoxNzc2NDUwMDU5fQ.1SvmTEtzsosH4gnSiYgFjshtYf028U6ZAhnquMMo3fo"
    
    private val apiHeaders = mapOf(
        "Authorization" to "Bearer $hardcodedBearer",
        "x-request-lang" to "en",
        "x-client-info" to """{"timezone":"Asia/Jayapura"}""",
        "accept" to "application/json"
    )

    override val mainPage = mainPageOf(
        "$apiBaseUrl/subject/trending?" to "Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "${request.data}page=$page&perPage=18"
        val resText = app.get(url, headers = apiHeaders).text
        val response = tryParseJson<SflixResponse>(resText)

        val homeItems = response?.data?.subjectList?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val detailPath = item.detailPath ?: return@mapNotNull null
            val urlDetail = "$mainUrl/detail/$detailPath" 
            val poster = item.cover?.url
            val rating = item.imdbRatingValue

            if (item.subjectType == 1) {
                newMovieSearchResponse(title, urlDetail, TvType.Movie) {
                    this.posterUrl = poster
                    this.score = Score.from10(rating) 
                }
            } else {
                newTvSeriesSearchResponse(title, urlDetail, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.score = Score.from10(rating) 
                }
            }
        } ?: return null

        val hasNext = response.data.pager?.hasMore ?: false
        return newHomePageResponse(request, homeItems, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$apiBaseUrl/subject/search"
        val requestData = mapOf(
            "keyword" to query,
            "page" to "1",
            "perPage" to 24,
            "subjectType" to 0
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val resText = app.post(url = searchUrl, headers = apiHeaders, requestBody = requestData).text
        val response = tryParseJson<SflixSearchAPIResponse>(resText)

        return response?.data?.items?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val detailPath = item.detailPath ?: return@mapNotNull null
            val urlDetail = "$mainUrl/detail/$detailPath" 
            val poster = item.cover?.url
            val rating = item.imdbRatingValue

            if (item.subjectType == 1) {
                newMovieSearchResponse(title, urlDetail, TvType.Movie) {
                    this.posterUrl = poster
                    this.score = Score.from10(rating) 
                }
            } else {
                newTvSeriesSearchResponse(title, urlDetail, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.score = Score.from10(rating) 
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val detailPath = url.substringAfter("detail/").substringBefore("?")
        val detailApiUrl = "$apiBaseUrl/detail?detailPath=$detailPath"

        val resText = app.get(detailApiUrl, headers = apiHeaders).text
        val response = tryParseJson<SflixDetailResponse>(resText)
        val data = response?.data ?: return null
        val subject = data.subject ?: return null
        val subjectId = subject.subjectId ?: return null
        val title = subject.title ?: return null

        val actors = data.stars?.mapNotNull {
            ActorData(actor = Actor(it.name ?: return@mapNotNull null, it.avatarUrl), roleString = it.character)
        }

        if (subject.subjectType == 1) {
            val linkData = SflixLinkData(subjectId, detailPath, 1, 1).toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = subject.cover?.url
                this.plot = subject.description
                this.tags = subject.genre?.split(",")?.map { it.trim() }
                this.actors = actors
                this.score = Score.from10(subject.imdbRatingValue)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            data.resource?.seasons?.forEach { season ->
                val seasonNum = season.se ?: 0
                val totalEp = season.resolutions?.firstOrNull()?.epNum ?: 0
                for (ep in 1..totalEp) {
                    val linkData = SflixLinkData(subjectId, detailPath, seasonNum, ep).toJson()
                    episodes.add(newEpisode(linkData) {
                        this.season = seasonNum
                        this.episode = ep
                        this.name = "Episode $ep"
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = subject.cover?.url
                this.plot = subject.description
                this.tags = subject.genre?.split(",")?.map { it.trim() }
                this.actors = actors
                this.score = Score.from10(subject.imdbRatingValue)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = tryParseJson<SflixLinkData>(data) ?: return false
        val playApiUrl = "$mainUrl/wefeed-h5api-bff/subject/play?subjectId=${linkData.subjectId}&se=${linkData.seasonNumber}&ep=${linkData.episodeNumber}&detailPath=${linkData.detailPath}"

        val playHeaders = mapOf(
            "cookie" to "sflix_token=%22eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjc5OTMyOTY0NzY4NjcyODE0MDgsImF0cCI6MywiZXh0IjoiMTc3NjQ1MDM1OSIsImV4cCI6MTc4NDIyNjM1OSwiaWF0IjoxNzc2NDUwMDU5fQ.1SvmTEtzsosH4gnSiYgFjshtYf028U6ZAhnquMMo3fo%22;",
            "x-client-info" to """{"timezone":"Asia/Jayapura"}""",
            "accept" to "application/json",
            "referer" to "$mainUrl/spa/videoPlayPage/movies/${linkData.detailPath}?id=${linkData.subjectId}&type=/movie/detail&lang=en"
        )

        val resText = app.get(playApiUrl, headers = playHeaders).text
        val playResponse = tryParseJson<SflixPlayResponse>(resText)
        val streams = playResponse?.data?.streams ?: return false
        var subtitleId: String? = null

        streams.forEach { stream ->
            val videoUrl = stream.url ?: return@forEach
            if (subtitleId == null && !stream.id.isNullOrBlank()) subtitleId = stream.id
            
            callback(newExtractorLink(this.name, this.name, videoUrl, ExtractorLinkType.VIDEO) {
                this.referer = mainUrl
                this.quality = getQualityFromName(stream.resolutions)
            })
        }

        if (subtitleId != null) {
            val subApiUrl = "$apiBaseUrl/subject/caption?format=MP4&id=$subtitleId&subjectId=${linkData.subjectId}&detailPath=${linkData.detailPath}"
            val subResText = app.get(subApiUrl, headers = apiHeaders).text
            val subResponse = tryParseJson<SflixSubtitleResponse>(subResText)
            
            subResponse?.data?.captions?.forEach { caption ->
                subtitleCallback(newSubtitleFile(caption.lanName ?: "Unknown", caption.url ?: return@forEach))
            }
        }
        return true
    }
}
