package com.sflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Sflix : MainAPI() {
    override var mainUrl = "https://sflix.film"
    override var name = "Sflix"
    override val hasMainPage = true 
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ==========================================
    // DAFTAR KATEGORI HALAMAN UTAMA
    // ==========================================
    override val mainPage = mainPageOf(
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=872031290915189720" to "Trending",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=6528093688173053896" to "IndoMovie",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=5283462032510044280" to "IndoDrama",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=4993310637209048808" to "Komedi",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=5848753831881965888" to "Horror Indo",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=3528002473103362040" to "Horror Lucu",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=1469286917119311888" to "Hollywood",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=8027941456897802448" to "Pernikahan Palsu",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=1164329479448281992" to "Drama Thailand",
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/ranking-list?id=173752404280836544" to "Drama Ahok"
    )

    // ==========================================
    // FUNGSI PENCURI TOKEN (AUTO-AUTH)
    // ==========================================
    private var currentToken: String? = null

    private suspend fun getSflixHeaders(): Map<String, String> {
        if (currentToken == null) {
            try {
                val response = app.get(
                    "https://h5-api.aoneroom.com/wefeed-h5api-bff/country-code",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                )
                
                val cookies = response.okhttpResponse.headers("set-cookie")
                val tokenCookie = cookies.find { it.contains("token=") }
                
                if (tokenCookie != null) {
                    currentToken = tokenCookie.substringAfter("token=").substringBefore(";")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}",
            "X-Request-Lang" to "en",
            "X-Source" to "null"
        )

        if (!currentToken.isNullOrEmpty()) {
            headers["Authorization"] = "Bearer $currentToken"
            headers["Cookie"] = "token=$currentToken; sflix_token=%22$currentToken%22; sflix_i18n_lang=en"
        }

        return headers
    }

    // ==========================================
    // FUNGSI HALAMAN UTAMA (GET MAIN PAGE)
    // ==========================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}page=$page&perPage=18"
        
        val response = app.get(
            url = url,
            headers = getSflixHeaders()
        ).parsedSafe<SflixTrendingResponse>()

        val homeItems = response?.data?.subjectList?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val urlPath = item.detailPath ?: return@mapNotNull null
            val poster = item.cover?.url
            val rating = item.imdbRatingValue
            val year = item.releaseDate?.substringBefore("-")?.toIntOrNull()

            if (item.subjectType == 1) {
                newMovieSearchResponse(title, urlPath, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = Score.from10(rating)
                }
            } else {
                newTvSeriesSearchResponse(title, urlPath, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = Score.from10(rating)
                }
            }
        } ?: emptyList()

        val hasNext = response?.data?.pager?.hasMore ?: false
        return newHomePageResponse(request.name, homeItems, hasNext)
    }

    // ==========================================
    // FUNGSI PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search"
        val payload = mapOf(
            "keyword" to query,
            "page" to page.toString(),
            "perPage" to 24,
            "subjectType" to 0
        )

        val response = app.post(
            url = searchUrl,
            headers = getSflixHeaders(),
            json = payload
        ).parsedSafe<SflixSearchResponse>()

        val searchResults = response?.data?.items?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val urlPath = item.detailPath ?: return@mapNotNull null
            val poster = item.cover?.url
            val rating = item.imdbRatingValue
            val year = item.releaseDate?.substringBefore("-")?.toIntOrNull()

            if (item.subjectType == 1) {
                newMovieSearchResponse(title, urlPath, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = Score.from10(rating)
                }
            } else {
                newTvSeriesSearchResponse(title, urlPath, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = Score.from10(rating)
                }
            }
        } ?: emptyList()

        val hasNext = response?.data?.pager?.hasMore ?: false
        return newSearchResponseList(searchResults, hasNext)
    }

    // ==========================================
    // FUNGSI DETAIL (LOAD)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val detailUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=$url"
        
        val responseData = app.get(
            url = detailUrl,
            headers = getSflixHeaders()
        ).parsedSafe<SflixDetailResponse>()?.data
            ?: throw ErrorLoadingException("Gagal mengambil detail dari Sflix")

        val subject = responseData.subject ?: throw ErrorLoadingException("Data tidak ditemukan")
        val subjectId = subject.subjectId ?: ""
        
        val title = subject.title ?: ""
        val poster = subject.cover?.url
        val plot = subject.description
        val rating = subject.imdbRatingValue
        val year = subject.releaseDate?.substringBefore("-")?.toIntOrNull()
        val tags = subject.genre?.split(",")?.map { it.trim() }
        val duration = subject.duration?.let { it / 60 } 

        val actorsList = responseData.stars?.mapNotNull { star ->
            val actorName = star.name ?: return@mapNotNull null
            ActorData(Actor(actorName), roleString = star.character)
        }

        val trailerUrl = subject.trailer?.videoAddress?.url

        if (subject.subjectType == 1) {
            val playUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=0&ep=0&detailPath=$url"
            
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.score = Score.from10(rating)
                this.actors = actorsList
                
                if (!trailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(trailerUrl, referer = null, raw = true))
                }
            }
        } else {
            val episodeList = mutableListOf<Episode>()
            
            responseData.resource?.seasons?.forEach { season ->
                val seasonNum = season.se ?: 1
                val maxEp = season.maxEp ?: 0
                
                for (epNum in 1..maxEp) {
                    val playUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$seasonNum&ep=$epNum&detailPath=$url"
                    
                    episodeList.add(
                        newEpisode(playUrl) {
                            this.name = "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.score = Score.from10(rating)
                this.actors = actorsList
                
                if (!trailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(trailerUrl, referer = null, raw = true))
                }
            }
        }
    }

    // ==========================================
    // FUNGSI PEMUTAR VIDEO & SUBTITLE (LOAD LINKS)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val subjectId = Regex("subjectId=([^&]+)").find(data)?.groupValues?.get(1) ?: ""
        val detailPath = Regex("detailPath=([^&]+)").find(data)?.groupValues?.get(1) ?: ""
        val se = Regex("se=([^&]+)").find(data)?.groupValues?.get(1) ?: "0"
        
        val isMovie = se == "0"
        val typePath = if (isMovie) "movies" else "series"
        val typeQuery = if (isMovie) "/movie/detail" else "/tv/detail"
        
        val dynamicReferer = "$mainUrl/spa/videoPlayPage/$typePath/$detailPath?id=$subjectId&type=$typeQuery&lang=en"

        val requestHeaders = getSflixHeaders().toMutableMap()
        requestHeaders["Referer"] = dynamicReferer
        
        val response = app.get(
            url = data,
            headers = requestHeaders 
        ).parsedSafe<SflixPlayResponse>()?.data

        response?.streams?.forEach { stream ->
            val videoUrl = stream.url ?: return@forEach
            val resolution = stream.resolutions ?: ""
            val videoQuality = getQualityFromName("${resolution}p")
            
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Sflix ${stream.format ?: "MP4"}",
                    url = videoUrl,
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/" 
                    this.quality = videoQuality
                }
            )
        }

        val firstStreamId = response?.streams?.firstOrNull()?.id
        if (firstStreamId != null) {
            val captionUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/caption?format=MP4&id=$firstStreamId&subjectId=$subjectId&detailPath=$detailPath"
            
            val captionResponse = app.get(
                url = captionUrl,
                headers = requestHeaders 
            ).parsedSafe<SflixCaptionResponse>()?.data

            captionResponse?.captions?.forEach { caption ->
                val subUrl = caption.url ?: return@forEach
                val langName = caption.lanName ?: "Unknown"
                
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = langName,
                        url = subUrl
                    )
                )
            }
        }

        return true
    }

    // ==========================================
    // DATA CLASSES UNTUK PARSING JSON
    // ==========================================
    data class SflixTrendingResponse(val data: SflixTrendingData? = null)
    data class SflixTrendingData(val pager: SflixPager? = null, val subjectList: List<SflixSubjectItem>? = null)

    data class SflixSearchResponse(val data: SflixSearchData? = null)
    data class SflixSearchData(val pager: SflixPager? = null, val items: List<SflixSubjectItem>? = null)
    
    data class SflixPager(val hasMore: Boolean? = null)
    data class SflixSubjectItem(
        val subjectType: Int? = null,
        val title: String? = null,
        val releaseDate: String? = null,
        val cover: SflixCover? = null,
        val imdbRatingValue: String? = null,
        val detailPath: String? = null
    )

    data class SflixDetailResponse(val data: SflixDetailData? = null)
    data class SflixDetailData(
        val subject: SflixSubject? = null,
        val stars: List<SflixStar>? = null,
        val resource: SflixResource? = null
    )
    data class SflixSubject(
        val subjectId: String? = null,
        val subjectType: Int? = null,
        val title: String? = null,
        val description: String? = null,
        val releaseDate: String? = null,
        val duration: Int? = null,
        val genre: String? = null,
        val cover: SflixCover? = null,
        val imdbRatingValue: String? = null,
        val trailer: SflixTrailer? = null
    )
    data class SflixStar(val name: String? = null, val character: String? = null)
    data class SflixTrailer(val videoAddress: SflixVideoAddress? = null)
    data class SflixVideoAddress(val url: String? = null)
    data class SflixResource(val seasons: List<SflixSeason>? = null)
    data class SflixSeason(val se: Int? = null, val maxEp: Int? = null)
    data class SflixCover(val url: String? = null)

    data class SflixPlayResponse(val data: SflixPlayData? = null)
    data class SflixPlayData(val streams: List<SflixStream>? = null)
    data class SflixStream(
        val id: String? = null, 
        val format: String? = null,
        val url: String? = null,
        val resolutions: String? = null
    )

    data class SflixCaptionResponse(val data: SflixCaptionData? = null)
    data class SflixCaptionData(val captions: List<SflixCaption>? = null)
    data class SflixCaption(
        val lanName: String? = null,
        val url: String? = null
    )
}
