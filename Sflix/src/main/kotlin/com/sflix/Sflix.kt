package com.sflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName

// ==========================================
// DATA CLASSES UNTUK MENGANGKAP JSON API
// ==========================================

// 1. Data Class Beranda (Main Page)
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

// 2. Data Class Detail (Halaman Info Film)
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

// 3. Data Class Penyambung load() ke loadLinks()
data class SflixLinkData(
    val subjectId: String,
    val detailPath: String,
    val seasonNumber: Int,
    val episodeNumber: Int
)

// 4. Data Class Play Video & Subtitle
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
// MESIN UTAMA SFLIX PROVIDER
// ==========================================

class SflixProvider : MainAPI() {
    override var name = "Sflix"
    override var mainUrl = "https://sflix.film"
    private val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "id"
    override val hasMainPage = true

    // Token Hardcode (Ganti bagian ini nanti kalau mau diotomatiskan)
    private val hardcodedBearer = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjIyOTQ0NzA1MzU0OTk2MjkwNTYsImF0cCI6MywiZXh0IjoiMTc3NjQ0NTgzOSIsImV4cCI6MTc4NDIyMTgzOSwiaWF0IjoxNzc2NDQ1NTM5fQ.c_0FLy4h-eefWW5xIt2u9CzxczQ1IT0EEY4H2JM9Y9s"
    
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
        val response = app.get(url, headers = apiHeaders).parsedSafe<SflixResponse>()

        val homeItems = response?.data?.subjectList?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val detailPath = item.detailPath ?: return@mapNotNull null
            val urlDetail = "$mainUrl/detail/$detailPath" // Bikin fake URL untuk di-pass ke load()
            val poster = item.cover?.url
            val rating = item.imdbRatingValue

            if (item.subjectType == 1) {
                newMovieSearchResponse(title, urlDetail, TvType.Movie) {
                    this.posterUrl = poster
                    addScore(rating)
                }
            } else {
                newTvSeriesSearchResponse(title, urlDetail, TvType.TvSeries) {
                    this.posterUrl = poster
                    addScore(rating)
                }
            }
        } ?: return null

        val hasNext = response.data.pager?.hasMore ?: false
        return newHomePageResponse(request, homeItems, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val detailPath = url.substringAfter("detail/").substringBefore("?")
        val detailApiUrl = "$apiBaseUrl/detail?detailPath=$detailPath"

        val response = app.get(detailApiUrl, headers = apiHeaders).parsedSafe<SflixDetailResponse>()
        val data = response?.data ?: return null
        val subject = data.subject ?: return null
        val subjectId = subject.subjectId ?: return null
        val title = subject.title ?: return null

        val actors = data.stars?.mapNotNull {
            val actorName = it.name ?: return@mapNotNull null
            ActorData(actor = Actor(actorName, it.avatarUrl), roleString = it.character)
        }

        if (subject.subjectType == 1) { // Movie
            val linkData = SflixLinkData(subjectId, detailPath, 0, 0).toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = subject.cover?.url
                this.plot = subject.description
                this.tags = subject.genre?.split(",")?.map { it.trim() }
                this.actors = actors
                addScore(subject.imdbRatingValue)
            }
        } else { // TV Series
            val episodes = mutableListOf<Episode>()
            data.resource?.seasons?.forEach { season ->
                val seasonNum = season.se ?: 0
                val totalEp = season.resolutions?.firstOrNull()?.epNum ?: 0

                for (ep in 1..totalEp) {
                    val linkData = SflixLinkData(subjectId, detailPath, seasonNum, ep).toJson()
                    episodes.add(
                        newEpisode(linkData) {
                            this.season = seasonNum
                            this.episode = ep
                            this.name = "Episode $ep"
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = subject.cover?.url
                this.plot = subject.description
                this.tags = subject.genre?.split(",")?.map { it.trim() }
                this.actors = actors
                addScore(subject.imdbRatingValue)
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

        // Ambil Video MP4
        val playResponse = app.get(playApiUrl, headers = apiHeaders).parsedSafe<SflixPlayResponse>()
        val streams = playResponse?.data?.streams ?: return false
        var subtitleId: String? = null

        streams.forEach { stream ->
            val videoUrl = stream.url ?: return@forEach
            if (subtitleId == null && !stream.id.isNullOrBlank()) subtitleId = stream.id
            
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    referer = mainUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    quality = getQualityFromName(stream.resolutions)
                }
            )
        }

        // Ambil Subtitle SRT
        if (subtitleId != null) {
            val subApiUrl = "$apiBaseUrl/subject/caption?format=MP4&id=$subtitleId&subjectId=${linkData.subjectId}&detailPath=${linkData.detailPath}"
            val subResponse = app.get(subApiUrl, headers = apiHeaders).parsedSafe<SflixSubtitleResponse>()
            
            subResponse?.data?.captions?.forEach { caption ->
                val subUrl = caption.url ?: return@forEach
                val langName = caption.lanName ?: "Unknown"
                subtitleCallback(newSubtitleFile(langName, subUrl))
            }
        }
        return true
    }
}
