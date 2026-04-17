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

    override val mainPage = mainPageOf(
        "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/trending" to "Trending"
    )

    private var currentToken: String? = null

    // ==========================================
    // FUNGSI HEADER DENGAN IDENTITY LENGKAP
    // ==========================================
    private suspend fun getSflixHeaders(refererUrl: String? = null): Map<String, String> {
        if (currentToken == null) {
            try {
                val response = app.get(
                    "https://h5-api.aoneroom.com/wefeed-h5api-bff/country-code",
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/",
                        "User-Agent" to USER_AGENT
                    )
                )
                val tokenCookie = response.okhttpResponse.headers("set-cookie").find { it.contains("token=") }
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
            "Referer" to (refererUrl ?: "$mainUrl/"),
            "User-Agent" to USER_AGENT,
            "X-Client-Info" to "{\"timezone\":\"Asia/Jayapura\"}",
            "X-Request-Lang" to "en",
            "X-Source" to "null"
        )

        currentToken?.let {
            headers["Authorization"] = "Bearer $it"
            headers["Cookie"] = "token=$it; sflix_token=\"$it\"; sflix_i18n_lang=en"
        }

        return headers
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}?page=$page&perPage=18"
        val response = app.get(url, headers = getSflixHeaders()).parsedSafe<SflixTrendingResponse>()

        val homeItems = response?.data?.subjectList?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val urlPath = item.detailPath ?: return@mapNotNull null
            if (item.subjectType == 1) {
                newMovieSearchResponse(title, urlPath, TvType.Movie) {
                    this.posterUrl = item.cover?.url
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    [span_0](start_span)this.score = Score.from10(item.imdbRatingValue)[span_0](end_span)
                [span_1](start_span)}
            } else {
                newTvSeriesSearchResponse(title, urlPath, TvType.TvSeries) {
                    this.posterUrl = item.cover?.url
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.imdbRatingValue)[span_1](end_span)
                [span_2](start_span)}
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, homeItems, response?.data?.pager?.hasMore ?: false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val payload = mapOf("keyword" to query, "page" to page.toString(), "perPage" to 24, "subjectType" to 0)
        val response = app.post(
            "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search",
            headers = getSflixHeaders(),
            json = payload
        ).parsedSafe<SflixSearchResponse>()

        val searchResults = response?.data?.items?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val urlPath = item.detailPath ?: return@mapNotNull null
            if (item.subjectType == 1) {
                newMovieSearchResponse(title, urlPath, TvType.Movie) {
                    this.posterUrl = item.cover?.url
                    this.score = Score.from10(item.imdbRatingValue)[span_2](end_span)
                [span_3](start_span)}
            } else {
                newTvSeriesSearchResponse(title, urlPath, TvType.TvSeries) {
                    this.posterUrl = item.cover?.url
                    this.score = Score.from10(item.imdbRatingValue)[span_3](end_span)
                [span_4](start_span)}
            }
        } ?: emptyList()

        return newSearchResponseList(searchResults, response?.data?.pager?.hasMore ?: false)
    }

    override suspend fun load(url: String): LoadResponse {
        val responseData = app.get(
            "https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=$url",
            headers = getSflixHeaders()
        ).parsedSafe<SflixDetailResponse>()?.data ?: throw ErrorLoadingException("No Data")

        val subject = responseData.subject ?: throw ErrorLoadingException("No Subject")
        val sId = subject.subjectId ?: ""
        val type = if (subject.subjectType == 1) TvType.Movie else TvType.TvSeries
        val trailerUrl = subject.trailer?.videoAddress?.url

        if (type == TvType.Movie) {
            val playData = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/play?subjectId=$sId&se=0&ep=0&detailPath=$url"
            return newMovieLoadResponse(subject.title ?: "", url, type, playData) {
                this.posterUrl = subject.cover?.url
                this.plot = subject.description
                this.score = Score.from10(subject.imdbRatingValue)[span_4](end_span)
                [span_5](start_span)if (!trailerUrl.isNullOrEmpty()) this.trailers.add(TrailerData(trailerUrl, null, true))[span_5](end_span)
            [span_6](start_span)}
        } else {
            val eps = mutableListOf<Episode>()
            responseData.resource?.seasons?.forEach { season ->
                for (i in 1..(season.maxEp ?: 0)) {
                    val pUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/play?subjectId=$sId&se=${season.se}&ep=$i&detailPath=$url"
                    eps.add(newEpisode(pUrl) { this.name = "Episode $i"; this.season = season.se; this.episode = i })[span_6](end_span)
                }
            }
            return newTvSeriesLoadResponse(subject.title ?: "", url, type, eps) {
                this.posterUrl = subject.cover?.url
                this.plot = subject.description
                [span_7](start_span)this.score = Score.from10(subject.imdbRatingValue)[span_7](end_span)
                [span_8](start_span)if (!trailerUrl.isNullOrEmpty()) this.trailers.add(TrailerData(trailerUrl, null, true))[span_8](end_span)
            [span_9](start_span)}
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // Membuat Referer spesifik yang diminta server agar hasResource jadi true
        val sId = data.substringAfter("subjectId=").substringBefore("&")
        val dPath = data.substringAfter("detailPath=")
        val isMovie = data.contains("se=0&ep=0")
        val typeStr = if (isMovie) "movies" else "series"
        val customReferer = "$mainUrl/spa/videoPlayPage/$typeStr/$dPath?id=$sId&type=/${if(isMovie) "movie" else "tv"}/detail&lang=en"

        val response = app.get(data, headers = getSflixHeaders(customReferer)).parsedSafe<SflixPlayResponse>()?.data
        response?.streams?.forEach { stream ->
            callback.invoke(newExtractorLink(this.name, "Sflix ${stream.format}", stream.url ?: return@forEach, type = ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = getQualityFromName("${stream.resolutions}p")
            })[span_9](end_span)
        }
        return true
    }

    data class SflixTrendingResponse(val data: SflixTrendingData?)
    data class SflixTrendingData(val pager: SflixPager?, val subjectList: List<SflixSubjectItem>?)
    data class SflixSearchResponse(val data: SflixSearchData?)
    data class SflixSearchData(val pager: SflixPager?, val items: List<SflixSubjectItem>?)
    data class SflixPager(val hasMore: Boolean?)
    data class SflixSubjectItem(val subjectType: Int?, val title: String?, val releaseDate: String?, val cover: SflixCover?, val imdbRatingValue: String?, val detailPath: String?)
    data class SflixDetailResponse(val data: SflixDetailData?)
    data class SflixDetailData(val subject: SflixSubject?, val stars: List<SflixStar>?, val resource: SflixResource?)
    data class SflixSubject(val subjectId: String?, val subjectType: Int?, val title: String?, val description: String?, val imdbRatingValue: String?, val cover: SflixCover?, val trailer: SflixTrailer?)
    data class SflixStar(val name: String?)
    data class SflixTrailer(val videoAddress: SflixVideoAddress?)
    data class SflixVideoAddress(val url: String?)
    data class SflixResource(val seasons: List<SflixSeason>?)
    data class SflixSeason(val se: Int?, val maxEp: Int?)
    data class SflixCover(val url: String?)
    data class SflixPlayResponse(val data: SflixPlayData?)
    data class SflixPlayData(val streams: List<SflixStream>?)
    data class SflixStream(val format: String?, val url: String?, val resolutions: String?)
}
