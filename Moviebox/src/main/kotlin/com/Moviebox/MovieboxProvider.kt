package com.Moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class MovieboxProvider : MainAPI() {
    override var name = "Moviebox"
    
    // Domain Utama (Digunakan untuk Origin dan Referer)
    override var mainUrl = "https://moviebox.ph"
    
    // Domain Khusus API (Terpusat)
    private val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff" 
    
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // TOKEN OTENTIKASI (Dari hasil intercept, valid hingga Juli 2026)
    private val bearerToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjY1NDQ3MzA2NDM5NjQ1MTYyMzIsImF0cCI6MywiZXh0IjoiMTc4MjUzNTQwMiIsImV4cCI6MTc5MDMxMTQwMiwiaWF0IjoxNzgyNTM1MTAyfQ.d2WpLFeF0erMdSlaaM1RMgnpyB4j1R1s2xVcY6a2Ut8"

    // Header dengan perlindungan anti-Cloudflare (Tanpa custom User-Agent) dan otorisasi Bearer
    private fun getApiHeaders(customReferer: String = "$mainUrl/"): Map<String, String> {
        return mapOf(
            "Accept" to "application/json",
            "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
            "x-request-lang" to "en",
            "Origin" to mainUrl,
            "Referer" to customReferer,
            "Authorization" to "Bearer $bearerToken"
        )
    }

    // --- DATA CLASSES ---
    // Diperbarui dengan @param:JsonProperty agar aman saat di-compile / minify (R8/ProGuard)
    data class HomeResponse(@param:JsonProperty("data") val data: HomeData?)
    data class HomeData(@param:JsonProperty("operatingList") val operatingList: List<OperatingList>?)
    data class OperatingList(@param:JsonProperty("title") val title: String?, @param:JsonProperty("subjects") val subjects: List<Subject>?, @param:JsonProperty("banner") val banner: Banner?)
    data class Banner(@param:JsonProperty("items") val items: List<BannerItem>?)
    data class BannerItem(@param:JsonProperty("subject") val subject: Subject?)
    
    data class SearchApiResponse(@param:JsonProperty("data") val data: SearchData?)
    data class SearchData(@param:JsonProperty("subjectList") val subjectList: List<Subject>?, @param:JsonProperty("items") val items: List<Subject>?, @param:JsonProperty("list") val list: List<Subject>?)
    data class Subject(@param:JsonProperty("title") val title: String?, @param:JsonProperty("subjectId") val subjectId: String?, @param:JsonProperty("subjectType") val subjectType: Int?, @param:JsonProperty("detailPath") val detailPath: String?, @param:JsonProperty("releaseDate") val releaseDate: String?, @param:JsonProperty("cover") val cover: ImageInfo?)
    data class ImageInfo(@param:JsonProperty("url") val url: String?)
    
    data class DetailResponse(@param:JsonProperty("data") val data: DetailDataWrapper?)
    data class DetailDataWrapper(@param:JsonProperty("subject") val subject: DetailData?, @param:JsonProperty("stars") val stars: List<Star>?, @param:JsonProperty("resource") val resource: ResourceData?)
    data class DetailData(@param:JsonProperty("subjectId") val subjectId: String?, @param:JsonProperty("title") val title: String?, @param:JsonProperty("description") val description: String?, @param:JsonProperty("releaseDate") val releaseDate: String?, @param:JsonProperty("cover") val cover: ImageInfo?, @param:JsonProperty("imdbRatingValue") val imdbRatingValue: String?, @param:JsonProperty("subjectType") val subjectType: Int?, @param:JsonProperty("episodes") val episodes: List<EpisodeInfo>?)
    data class Star(@param:JsonProperty("name") val name: String?, @param:JsonProperty("avatarUrl") val avatarUrl: String?, @param:JsonProperty("character") val character: String?)
    data class ResourceData(@param:JsonProperty("seasons") val seasons: List<SeasonDataApi>?)
    data class SeasonDataApi(@param:JsonProperty("se") val se: Int?, @param:JsonProperty("maxEp") val maxEp: Int?)
    data class EpisodeInfo(@param:JsonProperty("episodeId") val episodeId: String?, @param:JsonProperty("title") val title: String?, @param:JsonProperty("episodeNum") val episodeNum: Int?, @param:JsonProperty("seasonNum") val seasonNum: Int?)
    
    data class RecResponse(@param:JsonProperty("data") val data: RecData?)
    data class RecData(@param:JsonProperty("items") val items: List<Subject>?)
    
    data class LinkData(
        @param:JsonProperty("subjectId") val subjectId: String, 
        @param:JsonProperty("detailPath") val detailPath: String, 
        @param:JsonProperty("season") val season: Int = 0, 
        @param:JsonProperty("episode") val episode: Int = 0
    )
    
    data class PlayResponse(@param:JsonProperty("data") val data: PlayData?)
    data class PlayData(@param:JsonProperty("streams") val streams: List<StreamItem>?)
    data class StreamItem(@param:JsonProperty("id") val id: String?, @param:JsonProperty("url") val url: String?, @param:JsonProperty("resolutions") val resolutions: String?, @param:JsonProperty("format") val format: String?)
    data class CaptionResponse(@param:JsonProperty("data") val data: CaptionData?)
    data class CaptionData(@param:JsonProperty("captions") val captions: List<CaptionItem>?)
    data class CaptionItem(@param:JsonProperty("lanName") val lanName: String?, @param:JsonProperty("url") val url: String?)

    // --- FUNGSI UTAMA ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val apiUrl = "$apiBaseUrl/home?host=moviebox.ph"
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
            "page" to 1,
            "perPage" to 28,
            "subjectType" to 0
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        
        val response = app.post(
            apiUrl, 
            headers = getApiHeaders(), 
            requestBody = payload 
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
            newMovieLoadResponse(res.title ?: "", url, TvType.Movie, LinkData(res.subjectId ?: "", slug, 0, 0).toJson()) {
                this.posterUrl = res.cover?.url
                this.plot = res.description
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.recommendations = recs
                this.actors = castList
                this.score = Score.from10(res.imdbRatingValue)
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
                this.score = Score.from10(res.imdbRatingValue)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val linkData = tryParseJson<LinkData>(data) ?: return false
        
        val playUrl = "$apiBaseUrl/subject/play?subjectId=${linkData.subjectId}&se=${linkData.season}&ep=${linkData.episode}&detailPath=${linkData.detailPath}"
        
        val specificReferer = "$mainUrl/spa/videoPlayPage/movies/${linkData.detailPath}?id=${linkData.subjectId}&type=/movie/detail&detailSe=&detailEp=&lang=en"
        val reqHeaders = getApiHeaders(specificReferer)
        
        val response = app.get(playUrl, headers = reqHeaders)
        val playRes = tryParseJson<PlayResponse>(response.text)
        
        val streams = playRes?.data?.streams
        if (streams.isNullOrEmpty()) return false

        streams.forEach { stream ->
            val streamQuality = getQuality(stream.resolutions)
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} ${stream.resolutions ?: "?"}p",
                    url = stream.url ?: "",
                    type = INFER_TYPE
                ) {
                    this.quality = streamQuality
                    this.referer = mainUrl
                }
            )
            
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
