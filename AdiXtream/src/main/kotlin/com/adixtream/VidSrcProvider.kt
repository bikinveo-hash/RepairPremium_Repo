package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class VidSrcProvider : MainAPI() {
    override var name = "VidSrc AdiXtream"
    override var mainUrl = "https://vidsrc.net"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"

    // 1. Kategori Beranda Update: Menghapus Movie Viu/WeTV & Menambah Netflix Barat (West)
    override val mainPage = mainPageOf(
        "discover/movie?with_watch_providers=8&watch_region=ID&with_original_language=id" to "Netflix Indonesia Movies",
        "discover/tv?with_networks=213&with_original_language=id" to "Netflix Indonesia Series",
        "discover/movie?with_watch_providers=8&watch_region=ID&with_original_language=ko" to "Netflix Korea Movies",
        "discover/tv?with_networks=213&with_original_language=ko" to "Netflix Korea Series",
        "discover/movie?with_watch_providers=8&watch_region=ID&with_original_language=en" to "Netflix West Movies",
        "discover/tv?with_networks=213&with_original_language=en" to "Netflix West Series",
        
        // Viu & WeTV sekarang hanya menampilkan Series sesuai permintaanmu
        "discover/tv?with_networks=7237&with_original_language=id" to "Viu Indonesia Series",
        "discover/tv?with_networks=3732&with_original_language=id" to "WeTV Indonesia Series",
        "discover/tv?with_networks=3732&with_original_language=ko" to "WeTV Korea Series",
        
        "discover/movie?with_companies=15615|3268|49" to "HBO Movies",
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
        val baseUrl: String
        val tmdbId: String
        
        if (isTvSeries) {
            val parts = data.split("/")
            val ep = parts.last()
            val season = parts[parts.size - 2]
            tmdbId = parts[parts.size - 3]
            baseUrl = "$mainUrl/embed/tv/$tmdbId/$season/$ep"
        } else {
            tmdbId = data.substringAfter("/movie/").substringBefore("/")
            baseUrl = "$mainUrl/embed/movie/$tmdbId"
        }

        try {
            val mediaTypePath = if (isTvSeries) "tv" else "movie"
            val extRes = app.get("https://api.themoviedb.org/3/$mediaTypePath/$tmdbId/external_ids?api_key=$tmdbApiKey").text
            val imdbId = Regex(""""imdb_id"\s*:\s*"([^"]+)"""").find(extRes)?.groupValues?.get(1)?.removePrefix("tt")
            if (imdbId != null) {
                val opsRes = app.get("https://rest.opensubtitles.org/search/imdbid-$imdbId/sublanguageid-ind", headers = mapOf("X-User-Agent" to "trailers.to-UA")).text
                val subUrl = Regex(""""SubDownloadLink"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)?.replace("\\/", "/")
                val subId = Regex(""""IDSubtitleFile"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)
                if (subUrl != null && subId != null) {
                    val gzBytes = app.get(subUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).okhttpResponse.body?.bytes()
                    if (gzBytes != null) {
                        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("sub_data", "blob", gzBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                            .addFormDataPart("sub_id", subId).addFormDataPart("sub_enc", "UTF-8")
                            .addFormDataPart("sub_src", "ops").addFormDataPart("subformat", "srt").build()
                        val extractRes = app.post("https://cloudnestra.com/get_sub_url", requestBody = requestBody).text
                        if (extractRes.startsWith("/sub/")) subtitleCallback.invoke(newSubtitleFile("Indonesia", "https://cloudnestra.com$extractRes"))
                    }
                }
            }
        } catch (e: Exception) { }

        val universalBypass = WebViewResolver(Regex("""vidsrc|cloudnestra|vsembed|rcp"""))
        val response1 = app.get(baseUrl, interceptor = universalBypass).text
        var iframeUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1)
            ?: Jsoup.parse(response1).selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("Server sibuk. Coba klik play lagi dalam 5 detik.")
        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"

        var finalHtml = app.get(iframeUrl, referer = baseUrl, interceptor = universalBypass).text
        var finalReferer = iframeUrl

        if (!finalHtml.contains("H4sI")) {
            val hiddenMatch = Regex("""['"](/prorcp/[^'"]+|/rcp/[^'"]+)['"]""").find(finalHtml)
            if (hiddenMatch != null) {
                val domain = "https://" + (if (finalReferer.contains("vsembed.ru")) "cloudnestra.com" else finalReferer.substringAfter("://").substringBefore("/"))
                val hiddenUrl = domain + hiddenMatch.groupValues[1]
                finalHtml = app.get(hiddenUrl, referer = finalReferer, interceptor = universalBypass).text
                finalReferer = hiddenUrl
            }
        }

        val hashMatch = Regex("""(H4sI[a-zA-Z0-9+/=_\.\-\\]+m3u8)""").find(finalHtml)
            ?: throw ErrorLoadingException("Gagal memuat video. Pastikan internet stabil.")
        val m3u8Url = "https://tmstr2.neonhorizonworkshops.com/pl/${hashMatch.groupValues[1].replace("\\/", "/")}"

        callback.invoke(newExtractorLink(this.name, "VidSrc HD", m3u8Url, ExtractorLinkType.M3U8) { this.referer = finalReferer })
        return true
    }
}

// DATA CLASSES
data class TmdbResponse(@JsonProperty("results") val results: List<TmdbMovie>)
data class TmdbMovie(@JsonProperty("id") val id: Int, @JsonProperty("title") val title: String?, @JsonProperty("name") val name: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("media_type") val mediaType: String?)
data class TmdbGenre(@JsonProperty("name") val name: String)
data class TmdbVideoResult(@JsonProperty("results") val results: List<TmdbVideo>)
data class TmdbVideo(@JsonProperty("type") val type: String, @JsonProperty("key") val key: String, @JsonProperty("site") val site: String)
data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>)
data class TmdbCast(@JsonProperty("name") val name: String, @JsonProperty("character") val character: String?, @JsonProperty("profile_path") val profilePath: String?)
data class TmdbRecommendations(@JsonProperty("results") val results: List<TmdbMovie>)
data class TmdbDetailResponse(@JsonProperty("title") val title: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("backdrop_path") val backdropPath: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("release_date") val releaseDate: String?, @JsonProperty("runtime") val runtime: Int?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("genres") val genres: List<TmdbGenre>?, @JsonProperty("videos") val videos: TmdbVideoResult?, @JsonProperty("credits") val credits: TmdbCredits?, @JsonProperty("recommendations") val recommendations: TmdbRecommendations?)
data class TmdbTvDetailResponse(@JsonProperty("name") val name: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("backdrop_path") val backdropPath: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("first_air_date") val firstAirDate: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("seasons") val seasons: List<TmdbSeason>?, @JsonProperty("genres") val genres: List<TmdbGenre>?, @JsonProperty("videos") val videos: TmdbVideoResult?, @JsonProperty("credits") val credits: TmdbCredits?, @JsonProperty("recommendations") val recommendations: TmdbRecommendations?)
data class TmdbSeason(@JsonProperty("season_number") val seasonNumber: Int, @JsonProperty("episode_count") val episodeCount: Int)
data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisodeDetail>)
data class TmdbEpisodeDetail(@JsonProperty("episode_number") val episodeNumber: Int, @JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("still_path") val stillPath: String?, @JsonProperty("air_date") val airDate: String?, @JsonProperty("vote_average") val voteAverage: Double?)
