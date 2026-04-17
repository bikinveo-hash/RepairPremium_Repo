package com.sflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Sflix : MainAPI() {
    override var mainUrl = "https://sflix.film"
    override var name = "Sflix"
    
    // 1. KITA UBAH MENJADI TRUE AGAR MUNCUL DI BERANDA
    override val hasMainPage = true 
    
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // 2. KITA DAFTARKAN MENU HALAMAN UTAMA (Contoh: Trending)
    override val mainPage = mainPageOf(
        "Trending" to "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/trending"
    )

    // ==========================================
    // 3. FUNGSI HALAMAN UTAMA (GET MAIN PAGE)
    // ==========================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Menambahkan paginasi ke URL
        val url = "${request.data}?page=$page&perPage=18"
        
        val response = app.get(
            url = url,
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
        ).parsedSafe<SflixTrendingResponse>()

        // Mengurai data menjadi item poster di beranda
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
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            ),
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
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
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
            val playUrl = "$mainUrl/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=0&ep=0&detailPath=$url"
            
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.score = Score.from10(rating)
                this.actors = actorsList
                
                if (!trailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(trailerUrl, referer = null, raw = false))
                }
            }
        } else {
            val episodeList = mutableListOf<Episode>()
            
            responseData.resource?.seasons?.forEach { season ->
                val seasonNum = season.se ?: 1
                val maxEp = season.maxEp ?: 0
                
                for (epNum in 1..maxEp) {
                    val playUrl = "$mainUrl/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$seasonNum&ep=$epNum&detailPath=$url"
                    
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
                    this.trailers.add(TrailerData(trailerUrl, referer = null, raw = false))
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
        val response = app.get(
            url = data,
            headers = mapOf(
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
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
        return true
    }

    // ==========================================
    // DATA CLASSES UNTUK PARSING JSON
    // ==========================================
    // Data class tambahan untuk halaman utama (Trending)
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
