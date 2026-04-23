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

    override val mainPage = mainPageOf(
        "discover/movie?with_watch_providers=8&watch_region=ID&with_origin_country=ID" to "Netflix Indonesia Movies",
        "discover/tv?with_networks=213&with_origin_country=ID" to "Netflix Indonesia Series",
        "discover/movie?with_watch_providers=8&watch_region=KR&with_origin_country=KR" to "Netflix Korea Movies",
        "discover/tv?with_networks=213&with_origin_country=KR" to "Netflix Korea Series",
        "discover/movie?with_watch_providers=100&watch_region=ID&with_origin_country=ID" to "Viu Indonesia Movies",
        "discover/tv?with_networks=1530&with_origin_country=ID" to "Viu Indonesia Series",
        "discover/movie?with_watch_providers=118&watch_region=US" to "HBO Movies",
        "discover/tv?with_networks=49" to "HBO Series",
        "discover/movie?with_watch_providers=1060&watch_region=ID&with_origin_country=ID" to "WeTV Indonesia Movies",
        "discover/tv?with_networks=3321&with_origin_country=ID" to "WeTV Indonesia Series",
        "discover/tv?with_networks=3321&with_origin_country=KR" to "WeTV Korea Series",
        "discover/movie?with_companies=2" to "Disney Movies",
        "discover/tv?with_networks=2739" to "Disney Series",
        "discover/movie?with_genres=27&with_origin_country=ID" to "Horror Indonesia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "https://api.themoviedb.org/3/${request.data}&api_key=$tmdbApiKey&language=en-US&page=$page"
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return newHomePageResponse(emptyList())

        val isTvSeries = request.data.contains("discover/tv")
        val tvType = if (isTvSeries) TvType.TvSeries else TvType.Movie
        
        // Bikin penanda URL khusus untuk membedakan Movie dan TV
        val urlPrefix = if (isTvSeries) "tv" else "movie"

        val filmList = response.results.map { movie ->
            val titleText = movie.title ?: movie.name ?: "Tanpa Judul"
            val targetUrl = "$mainUrl/$urlPrefix/${movie.id}" // Contoh: https://vidsrc.net/tv/12345
            
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
        // Pindah ke endpoint multi search agar Movie dan TV Series sama-sama muncul
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
            // PROSES UNTUK TV SERIES
            val tmdbId = url.substringAfter("/tv/").substringBefore("/")
            val tvDetail = app.get("https://api.themoviedb.org/3/tv/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbTvDetailResponse>() 
                ?: throw ErrorLoadingException("Gagal mengambil data Series dari TMDB")

            // Looping untuk membuat daftar Season dan Episode
            val episodes = mutableListOf<Episode>()
            tvDetail.seasons?.forEach { season ->
                if (season.seasonNumber > 0) { // Lewati season 0 (Biasanya Special)
                    for (ep in 1..season.episodeCount) {
                        episodes.add(newEpisode("$mainUrl/tv/$tmdbId/${season.seasonNumber}/$ep") {
                            this.name = "Episode $ep"
                            this.season = season.seasonNumber
                            this.episode = ep
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
            }
            
        } else {
            // PROSES UNTUK MOVIE (Seperti aslinya)
            val tmdbId = url.substringAfter("/movie/").substringBefore("/")
            val movieDetail = app.get("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetailResponse>() 
                ?: throw ErrorLoadingException("Gagal mengambil data Movie dari TMDB")

            return newMovieLoadResponse(movieDetail.title ?: "Tanpa Judul", url, TvType.Movie, tmdbId) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movieDetail.posterPath}"
                this.backgroundPosterUrl = "https://image.tmdb.org/t/p/w1280${movieDetail.backdropPath}"
                this.year = movieDetail.releaseDate?.take(4)?.toIntOrNull()
                this.plot = movieDetail.overview
                this.duration = movieDetail.runtime
                this.score = Score.from10(movieDetail.voteAverage)
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
        
        // Membedakan Base URL dan ID untuk Player
        val baseUrl: String
        val tmdbId: String
        
        if (isTvSeries) {
            val parts = data.split("/")
            val ep = parts.last()
            val season = parts[parts.size - 2]
            tmdbId = parts[parts.size - 3]
            baseUrl = "$mainUrl/embed/tv/$tmdbId/$season/$ep" // Link VidSrc untuk series
        } else {
            tmdbId = data.substringAfter("/movie/").substringBefore("/")
            baseUrl = "$mainUrl/embed/movie/$tmdbId" // Link VidSrc untuk movie
        }

        // --- 1. TAHAP SUBTITLE ---
        try {
            val mediaTypePath = if (isTvSeries) "tv" else "movie"
            val extRes = app.get("https://api.themoviedb.org/3/$mediaTypePath/$tmdbId/external_ids?api_key=$tmdbApiKey").text
            val imdbId = Regex(""""imdb_id"\s*:\s*"([^"]+)"""").find(extRes)?.groupValues?.get(1)?.removePrefix("tt")
            
            if (imdbId != null) {
                val opsRes = app.get("https://rest.opensubtitles.org/search/imdbid-$imdbId/sublanguageid-ind", 
                    headers = mapOf("X-User-Agent" to "trailers.to-UA")).text
                val subUrl = Regex(""""SubDownloadLink"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)?.replace("\\/", "/")
                val subId = Regex(""""IDSubtitleFile"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)

                if (subUrl != null && subId != null) {
                    val gzBytes = app.get(subUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).okhttpResponse.body?.bytes()
                    if (gzBytes != null) {
                        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("sub_data", "blob", gzBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                            .addFormDataPart("sub_id", subId).addFormDataPart("sub_enc", "UTF-8")
                            .addFormDataPart("sub_src", "ops")
                            .addFormDataPart("subformat", "srt").build()
                        val extractRes = app.post("https://cloudnestra.com/get_sub_url", requestBody = requestBody).text
                        if (extractRes.startsWith("/sub/")) subtitleCallback.invoke(newSubtitleFile("Indonesia", "https://cloudnestra.com$extractRes"))
                    }
                }
            }
        } catch (e: Exception) { }

        // --- 2. TAHAP VIDEO (SISTEM BYPASS CLOUDFLARE) ---
        val universalBypass = WebViewResolver(Regex("""vidsrc|cloudnestra|vsembed|rcp"""))
        val response1 = app.get(baseUrl, interceptor = universalBypass).text
        
        var iframeUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1)
            ?: Jsoup.parse(response1).selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("Server sibuk. Coba klik play lagi dalam 5 detik.")

        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"

        // Buka Iframe Cloudnestra/Vsembed
        var finalHtml = app.get(iframeUrl, referer = baseUrl, interceptor = universalBypass).text
        var finalReferer = iframeUrl

        // --- 3. BONGKAR JEBAKAN JAVASCRIPT ---
        if (!finalHtml.contains("H4sI")) {
            val hiddenMatch = Regex("""['"](/prorcp/[^'"]+|/rcp/[^'"]+)['"]""").find(finalHtml)
            if (hiddenMatch != null) {
                val domain = "https://" + (if (finalReferer.contains("vsembed.ru")) "cloudnestra.com" else finalReferer.substringAfter("://").substringBefore("/"))
                val hiddenUrl = domain + hiddenMatch.groupValues[1]
                
                finalHtml = app.get(hiddenUrl, referer = finalReferer, interceptor = universalBypass).text
                finalReferer = hiddenUrl
            }
        }

        // --- 4. EKSTRAKSI HASIL AKHIR ---
        val hashMatch = Regex("""(H4sI[a-zA-Z0-9+/=_\.\-\\]+m3u8)""").find(finalHtml)
            ?: throw ErrorLoadingException("Gagal memuat video. Pastikan internet stabil dan coba lagi.")

        val teksRahasia = hashMatch.groupValues[1].replace("\\/", "/")
        val host = "https://tmstr2.neonhorizonworkshops.com/pl/" 
        val m3u8Url = "$host$teksRahasia"

        callback.invoke(
            newExtractorLink(this.name, "VidSrc HD", m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = finalReferer
            }
        )
        return true
    }
}

// DATA CLASSES UTAMA
data class TmdbResponse(@JsonProperty("results") val results: List<TmdbMovie>)

data class TmdbMovie(
    @JsonProperty("id") val id: Int, 
    @JsonProperty("title") val title: String?, 
    @JsonProperty("name") val name: String?,   
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("vote_average") val voteAverage: Double?,
    @JsonProperty("media_type") val mediaType: String? // Diperlukan untuk multi-search
)

data class TmdbDetailResponse(
    @JsonProperty("title") val title: String?, 
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?, 
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("release_date") val releaseDate: String?, 
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("vote_average") val voteAverage: Double?
)

// TAMBAHAN DATA CLASS KHUSUS TV SERIES
data class TmdbTvDetailResponse(
    @JsonProperty("name") val name: String?,
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("first_air_date") val firstAirDate: String?,
    @JsonProperty("vote_average") val voteAverage: Double?,
    @JsonProperty("seasons") val seasons: List<TmdbSeason>?
)

data class TmdbSeason(
    @JsonProperty("season_number") val seasonNumber: Int,
    @JsonProperty("episode_count") val episodeCount: Int
)
