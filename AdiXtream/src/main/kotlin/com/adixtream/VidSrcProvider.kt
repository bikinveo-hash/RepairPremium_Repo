package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty
// IMPORT YANG BENAR:
import com.lagradost.cloudstream3.* import com.lagradost.cloudstream3.utils.* class VidSrcProvider : MainAPI() {
    override var name = "VidSrc AdiXtream"
    override var mainUrl = "https://vidsrc.net"
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    private val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homePageLists = ArrayList<HomePageList>()
        val url = "https://api.themoviedb.org/3/movie/popular?api_key=$tmdbApiKey&language=en-US&page=$page"

        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return newHomePageResponse(emptyList())

        val filmList = response.results.map { movie ->
            newMovieSearchResponse(
                name = movie.title,
                url = movie.id.toString(),
                type = TvType.Movie
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }

        homePageLists.add(HomePageList("Populer di VidSrc", filmList))
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val safeQuery = query.replace(" ", "%20")
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$safeQuery&language=en-US"
        
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return emptyList()

        return response.results.map { movie ->
            newMovieSearchResponse(
                name = movie.title,
                url = movie.id.toString(),
                type = TvType.Movie
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val tmdbId = url 
        val detailUrl = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=en-US"
        
        val movieDetail = app.get(detailUrl).parsedSafe<TmdbDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil detail film")

        return newMovieLoadResponse(
            name = movieDetail.title,
            url = tmdbId, 
            type = TvType.Movie,
            dataUrl = tmdbId
        ) {
            this.posterUrl = "https://image.tmdb.org/t/p/w500${movieDetail.posterPath}"
            this.backgroundPosterUrl = "https://image.tmdb.org/t/p/w1280${movieDetail.backdropPath}"
            this.year = movieDetail.releaseDate?.take(4)?.toIntOrNull()
            this.plot = movieDetail.overview
            this.duration = movieDetail.runtime
            this.score = Score.from10(movieDetail.voteAverage)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbId = data
        val baseUrl = "$mainUrl/embed/movie?tmdb=$tmdbId"

        val response1 = app.get(baseUrl).text
        val iframe1 = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1) ?: return false
        val iframeUrl1 = if (iframe1.startsWith("//")) "https:$iframe1" else iframe1

        val response2 = app.get(iframeUrl1, referer = baseUrl).text
        val iframe2 = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response2)?.groupValues?.get(1)
        
        var finalReferer = iframeUrl1
        val finalHtml = if (iframe2 != null) {
            val iframeUrl2 = if (iframe2.startsWith("//")) "https:$iframe2" else iframe2
            finalReferer = iframeUrl2
            app.get(iframeUrl2, referer = iframeUrl1).text
        } else {
            response2 
        }

        val subRegex = Regex("""(https?://[^"'\s]+\.vtt[^"'\s]*)""")
        subRegex.findAll(finalHtml).forEach { match ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = "English",
                    url = match.value
                )
            )
        }

        val hashRegex = Regex("""(H4sI[^"'\s]+m3u8)""")
        val matchResult = hashRegex.find(finalHtml)

        if (matchResult != null) {
            val teksRahasia = matchResult.value
            val host = "https://tmstr2.neonhorizonworkshops.com/pl/"
            val m3u8Url = "$host$teksRahasia"

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "VidSrc HD",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = finalReferer
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }
}

data class TmdbResponse(
    @JsonProperty("results") val results: List<TmdbMovie>
)

data class TmdbMovie(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("poster_path") val posterPath: String?
)

data class TmdbDetailResponse(
    @JsonProperty("title") val title: String,
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("release_date") val releaseDate: String?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("vote_average") val voteAverage: Double?
)
