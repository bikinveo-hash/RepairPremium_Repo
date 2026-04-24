package com.Moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class MovieboxProvider : MainAPI() {
    override var name = "Moviebox"
    override var mainUrl = "https://moviebox.ph"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
    
    private var rawToken: String? = null

    // Sistem Scraper Token Anti-Bot
    private suspend fun getRawAuthToken(): String {
        rawToken?.let { return it }

        val domains = listOf("https://moviebox.ph", "https://netfilm.world", "https://h5-api.aoneroom.com")
        val scrapeHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )

        for (domain in domains) {
            try {
                val response = app.get(domain, headers = scrapeHeaders)
                
                // Menangkap token JWT (HS256) apapun bentuknya di dalam HTML
                val tokenRegex = """eyJhbGciOiJIUzI1NiI[a-zA-Z0-9\-_.]+""".toRegex()
                val matchResult = tokenRegex.find(response.text)
                
                if (matchResult != null) {
                    rawToken = matchResult.value
                    return rawToken!!
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Token cadangan darurat (hanya dipakai jika benar-benar gagal scrape)
        return "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjQ4Njc4NDE4MzQ5MDEwMjkzMjgsImF0cCI6MywiZXh0IjoiMTc3NzA0MDQyMiIsImV4cCI6MTc4NDgxNjQyMiwiaWF0IjoxNzc3MDQwMTIyfQ.cxsvUHlRWDWiWOiEB78a9POanRXjX5eoL6h6Ip5Q7OU"
    }

    private suspend fun getApiHeaders(): Map<String, String> {
        val token = getRawAuthToken()
        return mapOf(
            "accept" to "application/json",
            "x-request-lang" to "en",
            "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
            "x-source" to "null",
            "origin" to mainUrl,
            "referer" to "$mainUrl/",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "authorization" to "Bearer $token",
            // Cookie format browser asli dengan %22 (URL Encoded Double Quotes)
            "cookie" to "mb_token=%22$token%22; token=%22$token%22"
        )
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
    
    // Keamanan ekstra untuk proguard Cloudstream
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
        val response = app.get("$apiUrl/home?host=moviebox.ph", headers = getApiHeaders()).parsedSafe<HomeResponse>()
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
        val payload = mapOf(
            "keyword" to query,
            "page" to "1",
            "perPage" to 28,
            "subjectType" to 0
        )
        val reqBody = payload.toJson().toRequestBody("application/json".toMediaTypeOrNull())
        
        val response = app.post(
            "$apiUrl/subject/search", 
            headers = getApiHeaders(), 
            requestBody = reqBody
        ).parsedSafe<SearchApiResponse>()
        
        val list = response?.data?.items ?: response?.data?.subjectList ?: emptyList()
        return list.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/") 
        
        val wrapper = app.get("$apiUrl/detail?detailPath=$slug", headers = getApiHeaders()).parsedSafe<DetailResponse>()?.data ?: return null
        val res = wrapper.subject ?: return null
        
        val recs = app.get("$apiUrl/subject/detail-rec?subjectId=${res.subjectId}&page=1&perPage=12", headers = getApiHeaders()).parsedSafe<RecResponse>()?.data?.items?.mapNotNull { it.toSearchResponse() }
        
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
        val playUrl = "$apiUrl/subject/play?subjectId=${linkData.subjectId}&se=${linkData.season}&ep=${linkData.episode}&detailPath=${linkData.detailPath}"
        
        // Percobaan Pertama
        var playRes = app.get(playUrl, headers = getApiHeaders()).parsedSafe<PlayResponse>()
        
        // PANTANG MENYERAH: Jika server membalas dengan streams kosong (Token Mati), 
        // kita paksa hapus token lama dan tembak lagi dengan token baru!
        if (playRes?.data?.streams.isNullOrEmpty()) {
            rawToken = null // Reset cache token
            playRes = app.get(playUrl, headers = getApiHeaders()).parsedSafe<PlayResponse>()
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
            
            if (stream == streams.firstOrNull()) {
                app.get("$apiUrl/subject/caption?format=${stream.format}&id=${stream.id}&subjectId=${linkData.subjectId}&detailPath=${linkData.detailPath}", headers = getApiHeaders()).parsedSafe<CaptionResponse>()?.data?.captions?.forEach { cap ->
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
