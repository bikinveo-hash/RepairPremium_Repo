package com.AdiDrakor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.AdiDrakor.AdiDrakorExtractor.invokeAdimoviebox
import com.AdiDrakor.AdiDrakorExtractor.invokeAdimoviebox2
import com.AdiDrakor.AdiDrakorExtractor.invokeKisskh
import com.AdiDrakor.AdiDrakorExtractor.invokeVidlink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class AdiDrakor : MainAPI() {
    override var name = "AdiDrakor"
    override var mainUrl = "https://vidsrcme.ru"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val apiKey = tmdbApiKey
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Endpoint Vidlink (dipakai oleh invokeVidlink). Konstanta ini setara dengan
     *  Adicinemax21.vidlinkAPI, dideklarasikan lokal supaya package AdiDrakor tetap self-contained. */
    companion object {
        const val vidlinkAPI = "https://vidlink.pro"
    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=1" to "Drama Korea Terbaru",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=1" to "Movie Korea Terbaru",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&sort_by=popularity.desc" to "Popular K-Dramas",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&sort_by=popularity.desc" to "Popular Korean Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&sort_by=vote_average.desc&vote_count.gte=100" to "Top Rated K-Dramas",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&sort_by=vote_average.desc&vote_count.gte=100" to "Top Rated Korean Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&air_date.lte=$today&air_date.gte=$today" to "Airing Today K-Dramas",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&with_genres=10749" to "Romance K-Dramas",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&with_genres=28" to "Action Korean Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Menggabungkan URL kategori yang sudah memuat api_key dengan parameter tambahan
        val url = "${request.data}&language=en-US&page=$page"
        val response = app.get(url).parsedSafe<TmdbResponse>()
            ?: return newHomePageResponse(request, emptyList())

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
        // Pakai overload newHomePageResponse(MainPageRequest, list) sesuai builder terbaru,
        // daripada membungkus manual ke HomePageList.
        return newHomePageResponse(request, filmList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$tmdbAPI/search/multi?api_key=$tmdbApiKey&query=${query.replace(" ", "%20")}&language=en-US"
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
            val tvDetail =
                app.get("$tmdbAPI/tv/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,videos,recommendations")
                    .parsedSafe<TmdbTvDetailResponse>()
                    ?: throw ErrorLoadingException("Gagal mengambil data Series dari TMDB")

            val episodes = mutableListOf<Episode>()
            tvDetail.seasons?.forEach { season ->
                if (season.seasonNumber > 0) {
                    val seasonDetail =
                        app.get("$tmdbAPI/tv/$tmdbId/season/${season.seasonNumber}?api_key=$tmdbApiKey")
                            .parsedSafe<TmdbSeasonDetail>()
                    seasonDetail?.episodes?.forEach { ep ->
                        // data = URL lengkap, dipakai konsisten dengan parsing di loadLinks()
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
                    ActorData(
                        Actor(cast.name, cast.profilePath?.let { "https://image.tmdb.org/t/p/w500$it" }),
                        roleString = cast.character
                    )
                }

                val trailer = tvDetail.videos?.results?.firstOrNull { it.type == "Trailer" && it.site == "YouTube" }
                trailer?.let { addTrailer("https://www.youtube.com/watch?v=${it.key}") }

                this.recommendations = tvDetail.recommendations?.results?.map { rec ->
                    newTvSeriesSearchResponse(rec.name ?: rec.title ?: "Tanpa Judul", "$mainUrl/tv/${rec.id}", TvType.TvSeries) {
                        this.posterUrl = "https://image.tmdb.org/t/p/w500${rec.posterPath}"
                    }
                }
            }
        } else {
            val tmdbId = url.substringAfter("/movie/").substringBefore("/")
            val movieDetail =
                app.get("$tmdbAPI/movie/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,videos,recommendations")
                    .parsedSafe<TmdbDetailResponse>()
                    ?: throw ErrorLoadingException("Gagal mengambil data Movie dari TMDB")

            // data = url lengkap (bukan tmdbId mentah), supaya konsisten dengan parsing di loadLinks()
            return newMovieLoadResponse(movieDetail.title ?: "Tanpa Judul", url, TvType.Movie, url) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movieDetail.posterPath}"
                this.backgroundPosterUrl = "https://image.tmdb.org/t/p/w1280${movieDetail.backdropPath}"
                this.year = movieDetail.releaseDate?.take(4)?.toIntOrNull()
                this.plot = movieDetail.overview
                this.duration = movieDetail.runtime
                this.score = Score.from10(movieDetail.voteAverage)
                this.tags = movieDetail.genres?.map { it.name }
                this.actors = movieDetail.credits?.cast?.map { cast ->
                    ActorData(
                        Actor(cast.name, cast.profilePath?.let { "https://image.tmdb.org/t/p/w500$it" }),
                        roleString = cast.character
                    )
                }

                val trailer = movieDetail.videos?.results?.firstOrNull { it.type == "Trailer" && it.site == "YouTube" }
                trailer?.let { addTrailer("https://www.youtube.com/watch?v=${it.key}") }

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
        // `data` sekarang selalu berupa URL lengkap (baik movie maupun tv series),
        // jadi pola parsing di bawah konsisten untuk kedua tipe.
        val isTvSeries = data.contains("/tv/")
        val parts = data.split("/")

        val tmdbId = if (isTvSeries) parts[parts.size - 3] else data.substringAfter("/movie/").substringBefore("/")
        val season = if (isTvSeries) parts[parts.size - 2].toIntOrNull() else null
        val episode = if (isTvSeries) parts.last().toIntOrNull() else null
        val tmdbIdInt = tmdbId.toIntOrNull()

        var title = ""
        var originalTitle: String? = null

        try {
            if (isTvSeries) {
                val tvDetail = app.get("$tmdbAPI/tv/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbTvDetailResponse>()
                title = tvDetail?.name ?: ""
                originalTitle = tvDetail?.originalName
            } else {
                val movieDetail = app.get("$tmdbAPI/movie/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetailResponse>()
                title = movieDetail?.title ?: ""
                originalTitle = movieDetail?.originalTitle
            }
        } catch (e: Exception) {
            logError(e)
        }

        val year = if (isTvSeries) {
            (try { app.get("$tmdbAPI/tv/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbTvDetailResponse>() } catch (e: Exception) {
                logError(e); null
            })?.firstAirDate?.take(4)?.toIntOrNull()
        } else {
            (try { app.get("$tmdbAPI/movie/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetailResponse>() } catch (e: Exception) {
                logError(e); null
            })?.releaseDate?.take(4)?.toIntOrNull()
        }

        // Catatan: Extractor menerima (title, orgTitle, altTitle, year, season, episode, ...).
        // AdiDrakor tidak punya data altTitle, jadi kita pass null untuk altTitle.
        runAllAsync(
            { if (title.isNotEmpty()) invokeAdimoviebox(title, originalTitle, null, year, season, episode, subtitleCallback, callback) },
            { if (title.isNotEmpty()) invokeAdimoviebox2(title, originalTitle, null, year, season, episode, subtitleCallback, callback) },
            { if (title.isNotEmpty()) invokeKisskh(title, originalTitle, null, year, season, episode, subtitleCallback, callback) },
            { if (tmdbIdInt != null) invokeVidlink(tmdbIdInt, season, episode, callback) }
        )

        return true
    }
}
