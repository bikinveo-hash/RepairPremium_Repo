package com.Moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class MovieboxProvider : MainAPI() {
    override var name = "Moviebox"
    
    // Domain Utama (Juga digunakan untuk rute API Video Play)
    override var mainUrl = "https://netfilm.world"
    
    // Domain Khusus API (Untuk Home, Search, Detail, dan Subtitle)
    private val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff" 
    
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var rawToken: String? = null

    // Fungsi otomatis mencuri token dari sesi Cloudstream
    private suspend fun initSessionAndGetToken(): String? {
        if (rawToken != null) return rawToken
        
        try {
            val response = app.get(mainUrl)
            
            // 1. Ekstrak dari Cookie
            val cookieToken = response.cookies["token"] ?: response.cookies["mb_token"]
            if (!cookieToken.isNullOrBlank()) {
                rawToken = cookieToken.replace("%22", "").replace("\"", "")
                return rawToken
            }
            
            // 2. Ekstrak dari HTML (Fallback)
            val tokenRegex = """eyJhbGciOiJIUzI1NiI[a-zA-Z0-9\-_.]+""".toRegex()
            val matchResult = tokenRegex.find(response.text)
            if (matchResult != null) {
                rawToken = matchResult.value
                return rawToken
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private suspend fun getApiHeaders(): Map<String, String> {
        val token = initSessionAndGetToken()
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
            "x-source" to "",
            "x-request-lang" to "en",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            // Menyamar sebagai browser PC agar lolos deteksi Anti-Bot
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        
        if (token != null) {
            headers["Authorization"] = "Bearer $token"
            headers["Cookie"] = "token=$token; mb_token=$token" 
        }
        return headers
    }

    // --- DATA CLASSES ---
    data class HomeResponse(@JsonProperty("data") val data: HomeData?)
    data class HomeData(@JsonProperty("operatingList") val operatingList: List<OperatingList>?)
    data class OperatingList(@JsonProperty("title") val title: String?, @JsonProperty("subjects") val subjects: List<Subject>?, @JsonProperty("banner") val banner: Banner?)
    data class Banner(@JsonProperty("items") val items: List<BannerItem>?)
    data class BannerItem(@JsonProperty("subject") val subject: Subject?)
    
    data class SearchApiResponse(@JsonProperty("data") val data: SearchData?)
    data class SearchData(@JsonProperty("subjectList") val subjectList: List<Subject>?, @JsonProperty("items") val items: List<Subject>?, @JsonProperty("list") val list: List<Subject>?)
    data class Subject(@JsonProperty("title") val title: String?, @JsonProperty("subjectId") val subjectId: String?, @JsonProperty("subjectType") val subjectType: Int?, @JsonProperty("detailPath") val detailPath: String?, @JsonProperty("releaseDate") val releaseDate: String?, @JsonProperty("cover") val cover: ImageInfo?)
    data class ImageInfo(@JsonProperty("url") val url: String?)
    
    data class DetailResponse(@JsonProperty("data") val data: DetailDataWrapper?)
    data class DetailDataWrapper(@JsonProperty("subject") val subject: DetailData?, @JsonProperty("stars") val stars: List<Star>?, @JsonProperty("resource") val resource: ResourceData?)
    data class DetailData(@JsonProperty("subjectId") val subjectId: String?, @JsonProperty("title") val title: String?, @JsonProperty("description") val description: String?, @JsonProperty("releaseDate") val releaseDate: String?, @JsonProperty("cover") val cover: ImageInfo?, @JsonProperty("imdbRatingValue") val imdbRatingValue: String?, @JsonProperty("subjectType") val subjectType: Int?, @JsonProperty("episodes") val episodes: List<EpisodeInfo>?)
    data class Star(@JsonProperty("name") val name: String?, @JsonProperty("avatarUrl") val avatarUrl: String?, @JsonProperty("character") val character: String?)
    data class ResourceData(@JsonProperty("seasons") val seasons: List<SeasonDataApi>?)
    data class SeasonDataApi(@JsonProperty("se") val se: Int?, @JsonProperty("maxEp") val maxEp: Int?)
    data class EpisodeInfo(@JsonProperty("episodeId") val episodeId: String?, @JsonProperty("title") val title: String?, @JsonProperty("episodeNum") val episodeNum: Int?, @JsonProperty("seasonNum") val seasonNum: Int?)
    
    data class RecResponse(@JsonProperty("data") val data: RecData?)
    data class RecData(@JsonProperty("items") val items: List<Subject>?)
    
    data class LinkData(
        @JsonProperty("subjectId") val subjectId: String, 
        @JsonProperty("detailPath") val detailPath: String, 
        @JsonProperty("season") val season: Int = 0, 
        @JsonProperty("episode") val episode: Int = 0
    )
    
    data class PlayResponse(@JsonProperty("data") val data: PlayData?)
    data class PlayData(@JsonProperty("streams") val streams: List<StreamItem>?)
    data class StreamItem(@JsonProperty("id") val id: String?, @JsonProperty("url") val url: String?, @JsonProperty("resolutions") val resolutions: String?, @JsonProperty("format") val format: String?)
    data class CaptionResponse(@JsonProperty("data") val data: CaptionData?)
    data class CaptionData(@JsonProperty("captions") val captions: List<CaptionItem>?)
    data class CaptionItem(@JsonProperty("lanName") val lanName: String?, @JsonProperty("url") val url: String?)

    // --- FUNGSI UTAMA ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val apiUrl = "$apiBaseUrl/home?host=netfilm.world"
        val response = app.get(apiUrl, headers = getApiHeaders()).parsedSafe<HomeResponse>()
        
        val homeItems = mutableListOf<HomePageList>()
        response?.data?.operatingList?.forEach { section ->
            val searchResponses = mutableListOf<SearchResponse>()
            section.subjects?.forEach { it.toSearchResponse()?.let { res -> searchResponses.add(res) } }
            section.banner?.items?.forEach { it.subject?.toSearchResponse()?.let { res -> searchResponses.add(res) } }
            if (searchResponses.isNotEmpty()) homeItems.add(HomePageList(section.title ?: "", searchResponses))
        }
        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$apiBaseUrl/subject/search"
        val payload = mapOf(
            "keyword" to query,
            "page" to "1",
            "perPage" to 28,
            "subjectType" to 0
        )
        
        val response = app.post(
            apiUrl, 
            headers = getApiHeaders(), 
            json = payload 
        ).parsedSafe<SearchApiResponse>()
        
        val list = response?.data?.items ?: response?.data?.subjectList ?: emptyList()
        return list.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/") 
        val detailUrl = "$apiBaseUrl/detail?detailPath=$slug"
        
        val wrapper = app.get(detailUrl, headers = getApiHeaders()).parsedSafe<DetailResponse>()?.data ?: return null
        val res = wrapper.subject ?: return null
        
        val recUrl = "$apiBaseUrl/subject/detail-rec?subjectId=${res.subjectId}&page=1&perPage=12"
        val recs = app.get(recUrl, headers = getApiHeaders()).parsedSafe<RecResponse>()?.data?.items?.mapNotNull { it.toSearchResponse() }
        
        val castList = wrapper.stars?.mapNotNull { star ->
            if (star.name != null) ActorData(actor = Actor(star.name, star.avatarUrl), roleString = star.character) else null
        }
        
        return if (res.subjectType == 1) { 
            // Default season 1 dan episode 1 untuk tipe Film
            newMovieLoadResponse(res.title ?: "", url, TvType.Movie, LinkData(res.subjectId ?: "", slug, 1, 1).toJson()) {
                this.posterUrl = res.cover?.url
                this.plot = res.description
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.recommendations = recs
                this.actors = castList
                res.imdbRatingValue?.let { this.score = Score.from(it, 10) }
            }
        } else {
            val episodesList = mutableListOf<Episode>()
            val seasonsData = wrapper.resource?.seasons
            
            if (!seasonsData.isNullOrEmpty()) {
                seasonsData.forEach { season ->
                    val sNum = season.se ?: 1
                    val maxEp = season.maxEp ?: 0
                    if (maxEp > 0) {
                        for (eNum in 1..maxEp) {
                            episodesList.add(
                                newEpisode(LinkData(res.subjectId ?: "", slug, sNum, eNum).toJson()) {
                                    this.name = "Episode $eNum"
                                    this.season = sNum
                                    this.episode = eNum
                                }
                            )
                        }
                    }
                }
            } else if (!res.episodes.isNullOrEmpty()) {
                res.episodes.forEach { ep ->
                    episodesList.add(
                        newEpisode(LinkData(res.subjectId ?: "", slug, ep.seasonNum ?: 1, ep.episodeNum ?: 1).toJson()) { 
                            this.name = ep.title
                            this.season = ep.seasonNum
                            this.episode = ep.episodeNum 
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(res.title ?: "", url, TvType.TvSeries, episodesList) {
                this.posterUrl = res.cover?.url
                this.plot = res.description
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.recommendations = recs
                this.actors = castList
                res.imdbRatingValue?.let { this.score = Score.from(it, 10) }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val linkData = tryParseJson<LinkData>(data) ?: return false
        
        // MISTERI TERPECAHKAN: API Play harus ditembak ke mainUrl, bukan apiBaseUrl
        val playUrl = "$mainUrl/wefeed-h5api-bff/subject/play?subjectId=${linkData.subjectId}&se=${linkData.season}&ep=${linkData.episode}&detailPath=${linkData.detailPath}"
        
        val reqHeaders = getApiHeaders()
        var response = app.get(playUrl, headers = reqHeaders)
        var playRes = response.parsedSafe<PlayResponse>()
        
        // Auto-Retry jika token mati
        if (playRes?.data?.streams.isNullOrEmpty()) {
            rawToken = null // Reset token
            val retryHeaders = getApiHeaders()
            response = app.get(playUrl, headers = retryHeaders)
            playRes = response.parsedSafe<PlayResponse>()
        }
        
        val streams = playRes?.data?.streams
        if (streams.isNullOrEmpty()) return false

        streams.forEach { stream ->
            val streamQuality = getQuality(stream.resolutions)
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
            
            // Subtitle di-host di apiBaseUrl
            if (stream == streams.firstOrNull()) {
                val captionUrl = "$apiBaseUrl/subject/caption?format=${stream.format}&id=${stream.id}&subjectId=${linkData.subjectId}&detailPath=${linkData.detailPath}"
                app.get(captionUrl, headers = reqHeaders).parsedSafe<CaptionResponse>()?.data?.captions?.forEach { cap ->
                    subtitleCallback.invoke(
                        newSubtitleFile(cap.lanName ?: "Unknown", cap.url ?: "")
                    )
                }
            }
        }
        return true
    }

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
