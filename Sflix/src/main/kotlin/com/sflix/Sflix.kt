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

    // ==========================================
    // FUNGSI PENCURI TOKEN (AUTO-AUTH)
    // ==========================================
    private var currentToken: String? = null

    private suspend fun getSflixHeaders(): Map<String, String> {
        // Jika token belum ada, kita curi dulu dari API country-code
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
                
                // Ekstrak token dari header Set-Cookie
                val cookies = response.okhttpResponse.headers("set-cookie")
                val tokenCookie = cookies.find { it.contains("token=") }
                
                if (tokenCookie != null) {
                    currentToken = tokenCookie.substringAfter("token=").substringBefore(";")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Siapkan header wajib (Diperbarui berdasarkan analisa Termux)
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}",
            "X-Request-Lang" to "en",
            "X-Source" to "null"
        )

        // Suntikkan token jika berhasil didapat
        if (!currentToken.isNullOrEmpty()) {
            headers["Authorization"] = "Bearer $currentToken"
            // Menambahkan sflix_i18n_lang=en ke dalam Cookie agar sama persis dengan browser
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
        val url = "${request.data}?page=$page&perPage=18"
        
        val response = app.get(
            url = url,
            headers = getSflixHeaders() // <-- Menggunakan header dengan Token
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
            headers = getSflixHeaders(), // <-- Menggunakan header dengan Token
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
            headers = getSflixHeaders() // <-- Menggunakan header dengan Token
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
    // FUNGSI PEMUTAR VIDEO (LOAD LINKS)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Ekstrak data dari URL yang dilempar oleh fungsi load()
        val subjectId = Regex("subjectId=([^&]+)").find(data)?.groupValues?.get(1) ?: ""
        val detailPath = Regex("detailPath=([^&]+)").find(data)?.groupValues?.get(1) ?: ""
        val se = Regex("se=([^&]+)").find(data)?.groupValues?.get(1) ?: "0"
        
        // 2. Tentukan apakah ini Film atau Serial TV untuk menyesuaikan URL Referer
        val isMovie = se == "0"
        val typePath = if (isMovie) "movies" else "series"
        val typeQuery = if (isMovie) "/movie/detail" else "/tv/detail"
        
        // 3. Buat Referer dinamis persis seperti simulasi Termux kita yang berhasil
        val dynamicReferer = "$mainUrl/spa/videoPlayPage/$typePath/$detailPath?id=$subjectId&type=$typeQuery&lang=en"

        // 4. Ambil header dasar, lalu timpa "Referer" dengan yang dinamis
        val requestHeaders = getSflixHeaders().toMutableMap()
        requestHeaders["Referer"] = dynamicReferer
        
        // 5. Minta data ke server
        val response = app.get(
            url = data,
            headers = requestHeaders // <-- Menggunakan header dengan Referer baru
        ).parsedSafe<SflixPlayResponse>()?.data

        // 6. Urai link video yang didapat
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
                    this.referer = "$mainUrl/" // Referer standar untuk memutar videonya di player
                    this.quality = videoQuality
                }
            )
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
        val format: String? = null,
        val url: String? = null,
        val resolutions: String? = null
    )
}
