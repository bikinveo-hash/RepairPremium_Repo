package com.adixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.adixtream.AdiXtreamExtractor.invokeVidSrc
import com.adixtream.AdiXtreamExtractor.invokeAdimoviebox
import com.adixtream.AdiXtreamExtractor.invokeAdimoviebox2
import com.adixtream.AdiXtreamExtractor.invokeKisskh

open class AdiXtream : MainAPI() {
    override var name = "AdiXtream"
    override var mainUrl = "https://vidsrcme.ru"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"

    override val mainPage = mainPageOf(
        "discover/movie?with_watch_providers=8&watch_region=ID&with_original_language=id" to "Netflix Indonesia Movies",
        "discover/tv?with_networks=213&with_original_language=id" to "Netflix Indonesia Series",
        "discover/movie?with_watch_providers=8&watch_region=ID&with_original_language=ko" to "Netflix Korea Movies",
        "discover/tv?with_networks=213&with_original_language=ko" to "Netflix Korea Series",
        "discover/movie?with_watch_providers=8&watch_region=ID&with_original_language=en" to "Netflix West Movies",
        "discover/tv?with_networks=213&with_original_language=en" to "Netflix West Series",
        "discover/tv?with_networks=7237&with_original_language=id" to "Viu Indonesia Series",
        "discover/tv?with_networks=3732&with_original_language=id" to "WeTV Indonesia Series",
        "discover/tv?with_networks=3732&with_original_language=ko" to "WeTV Korea Series",
        "discover/movie?with_watch_providers=119&watch_region=ID" to "Prime Video",
        "discover/tv?with_watch_providers=119&watch_region=ID" to "Prime Video Series",
        "discover/movie?with_watch_providers=384|1899&watch_region=US&sort_by=popularity.desc&primary_release_date.gte=2020-01-01&without_genres=16" to "HBO Movies",
        "discover/tv?with_networks=49" to "HBO Series",
        "discover/movie?with_companies=2" to "Disney Movies",
        "discover/tv?with_networks=2739" to "Disney Series",
        "discover/movie?with_genres=27&with_original_language=id" to "Horror Indonesia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "https://api.themoviedb.org/3/${request.data}&api_key=$tmdbApiKey&language=en-US&page=$page"
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return newHomePageResponse(emptyList())

        val isTvSeries = request.data.contains("discover/tv")
        val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie
        val urlPrefix = if (isTvSeries) "tv" else "movie"

        val filmList = response.results.map { movie ->
            val titleText = movie.title ?: movie.name ?: "Tanpa Judul"
            val targetUrl = "$mainUrl/$urlPrefix/${movie.id}"
            
            if (isTvSeries) {
                newTvSeriesSearchResponse(titleText, targetUrl, tvType) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
                    this.score = Score.from10(movie.voteAverage)
                }
            } else {
                newMovieSearchResponse(titleText, targetUrl, tvType) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
                    this.score = Score.from10(movie.voteAverage)
                }
            }
        }
        return newHomePageResponse(listOf(HomePageList(request.name, filmList)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$tmdbApiKey&query=${query.replace(" ", "%20")}&language=en-US"
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return emptyList()
        
        return response.results.filter { it.mediaType == "movie" || it.mediaType == "tv" }.map { movie ->
            val isTvSeries = movie.mediaType == "tv"
            val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie
            val urlPrefix = if (isTvSeries) "tv" else "movie"
            val targetUrl = "$mainUrl/$urlPrefix/${movie.id}"
            val titleText = movie.title ?: movie.name ?: "Tanpa Judul"
            
            if (isTvSeries) {
                newTvSeriesSearchResponse(titleText, targetUrl, tvType) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
                    this.score = Score.from10(movie.voteAverage)
                }
            } else {
                newMovieSearchResponse(titleText, targetUrl, tvType) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
                    this.score = Score.from10(movie.voteAverage)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val isTvSeries = url.contains("/tv/")
        if (isTvSeries) {
            val tmdbId = url.substringAfter("/tv/").substringBefore("/")
            val tvDetail = app.get("https://api.themoviedb.org/3/tv/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,videos,recommendations").parsedSafe<TmdbTvDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil data Series dari TMDB")

            val episodes = mutableListOf<Episode>()
            tvDetail.seasons?.forEach { season ->
                if (season.seasonNumber > 0) {
                    val seasonDetail = app.get("https://api.themoviedb.org/3/tv/$tmdbId/season/${season.seasonNumber}?api_key=$tmdbApiKey").parsedSafe<TmdbSeasonDetail>()
                    seasonDetail?.episodes?.forEach { ep ->
                        episodes.add(newEpisode("$mainUrl/tv/$tmdbId/${season.seasonNumber}/${ep.episodeNumber}") {
                            this.name = ep.name ?: "Episode ${ep.episodeNumber}"
                            this.season = season.seasonNumber
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                            this.description = ep.overview
                            this.score = Score.from10(ep.voteAverage)
                            ep.airDate?.let { addDate(it) } 
                        })
                    }
                }
            }

            return newTvSeriesLoadResponse(tvDetail.name ?: "Tanpa Judul", url, TvType.TvSeries, episodes) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${tvDetail.posterPath}"
                this.backgroundPosterUrl = "https://image.tmdb.org/t/p/w1280${tvDetail.backdropPath}"
                this.year = tvDetail.firstAirDate?.take(4)?.toIntOrNull()
                this.plot = tvDetail.overview
                this.score = Score.from10(tvDetail.voteAverage)
                this.tags = tvDetail.genres?.map { it.name }
                this.actors = tvDetail.credits?.cast?.map { cast ->
                    ActorData(Actor(cast.name, cast.profilePath?.let { "https://image.tmdb.org/t/p/w500$it" }), roleString = cast.character)
                }
                
                val trailer = tvDetail.videos?.results?.firstOrNull { it.type == "Trailer" && it.site == "YouTube" }
                if (trailer != null) {
                    this.trailers.add(TrailerData(
                        extractorUrl = "https://www.youtube.com/watch?v=${trailer.key}",
                        referer = null,
                        raw = false
                    ))
                }
                this.recommendations = tvDetail.recommendations?.results?.map { rec ->
                    newTvSeriesSearchResponse(rec.name ?: rec.title ?: "Tanpa Judul", "$mainUrl/tv/${rec.id}", TvType.TvSeries) {
                        this.posterUrl = "https://image.tmdb.org/t/p/w500${rec.posterPath}"
                    }
                }
            }
        } else {
            val tmdbId = url.substringAfter("/movie/").substringBefore("/")
            val movieDetail = app.get("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,videos,recommendations").parsedSafe<TmdbDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil data Movie dari TMDB")

            return newMovieLoadResponse(movieDetail.title ?: "Tanpa Judul", url, TvType.Movie, tmdbId) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movieDetail.posterPath}"
                this.backgroundPosterUrl = "https://image.tmdb.org/t/p/w1280${movieDetail.backdropPath}"
                this.year = movieDetail.releaseDate?.take(4)?.toIntOrNull()
                this.plot = movieDetail.overview
                this.duration = movieDetail.runtime
                this.score = Score.from10(movieDetail.voteAverage)
                this.tags = movieDetail.genres?.map { it.name }
                this.actors = movieDetail.credits?.cast?.map { cast ->
                    ActorData(Actor(cast.name, cast.profilePath?.let { "https://image.tmdb.org/t/p/w500$it" }), roleString = cast.character)
                }
                
                val trailer = movieDetail.videos?.results?.firstOrNull { it.type == "Trailer" && it.site == "YouTube" }
                if (trailer != null) {
                    this.trailers.add(TrailerData(
                        extractorUrl = "https://www.youtube.com/watch?v=${trailer.key}",
                        referer = null,
                        raw = false
                    ))
                }
                this.recommendations = movieDetail.recommendations?.results?.map { rec ->
                    newMovieSearchResponse(rec.title ?: rec.name ?: "Tanpa Judul", "$mainUrl/movie/${rec.id}", TvType.Movie) {
                        this.posterUrl = "https://image.tmdb.org/t/p/w500${rec.posterPath}"
                    }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTvSeries = data.contains("/tv/")
        val parts = data.split("/")
        
        val tmdbId = if (isTvSeries) parts[parts.size - 3] else data.substringAfter("/movie/").substringBefore("/")
        val season = if (isTvSeries) parts[parts.size - 2].toIntOrNull() else null
        val episode = if (isTvSeries) parts.last().toIntOrNull() else null

        var title = ""
        var originalTitle: String? = null
        var year: Int? = null
        try {
            if (isTvSeries) {
                val tvDetail = app.get("https://api.themoviedb.org/3/tv/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbTvDetailResponse>()
                title = tvDetail?.name ?: ""
                originalTitle = tvDetail?.originalName
                year = tvDetail?.firstAirDate?.take(4)?.toIntOrNull()
            } else {
                val movieDetail = app.get("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetailResponse>()
                title = movieDetail?.title ?: ""
                originalTitle = movieDetail?.originalTitle
                year = movieDetail?.releaseDate?.take(4)?.toIntOrNull()
            }
        } catch (e: Exception) { }

        runAllAsync(
            { invokeVidSrc(tmdbId, season, episode, isTvSeries, subtitleCallback, callback) },
            { if (title.isNotEmpty()) invokeAdimoviebox(title, year, season, episode, subtitleCallback, callback, originalTitle) },
            { if (title.isNotEmpty()) invokeAdimoviebox2(title, year, season, episode, subtitleCallback, callback, originalTitle) },
            { if (title.isNotEmpty()) invokeKisskh(title, year, season, episode, subtitleCallback, callback, originalTitle) }
        )
        
        return true
    }

    private val CHUNK_REGEX1 by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }
    
    override fun getVideoInterceptor(extractorLink: ExtractorLink): okhttp3.Interceptor {
        return object : okhttp3.Interceptor {
            override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
                val request = chain.request().newBuilder().build()
                val response = chain.proceed(request)
                
                if (response.request.url.toString().contains(".txt")) {
                    val responseBody = response.body?.string() ?: return response
                    val chunks = responseBody.split(CHUNK_REGEX1)
                        .filter(String::isNotBlank)
                        .map(String::trim)
                        
                    val decrypted = chunks.mapIndexed { index, chunk ->
                        if (chunk.isBlank()) return@mapIndexed ""
                        val parts = chunk.split("\n")
                        if (parts.isEmpty()) return@mapIndexed ""

                        val header = parts.first()
                        val text = parts.drop(1)
                        val d = text.joinToString("\n") { line ->
                            try {
                                // Pastikan SubDecryptor.kt berada di package com.adixtream
                                decrypt(line)
                            } catch (e: Exception) {
                                "DECRYPT_ERROR:${e.message}"
                            }
                        }
                        listOf(index + 1, header, d).joinToString("\n")
                    }.filter { it.isNotEmpty() }.joinToString("\n\n")
                    
                    val newBody = okhttp3.ResponseBody.Companion.toResponseBody(
                        decrypted, 
                        response.body?.contentType()
                    )
                    return response.newBuilder()
                        .body(newBody)
                        .build()
                }
                return response
            }
        }
    }
}
