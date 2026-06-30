package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class RiveStreamProvider : MainAPI() {
    override var name    = "RiveStream"
    override var mainUrl = "https://www.rivestream.app"
    override var lang    = "en"
    override val hasMainPage    = true
    override val hasQuickSearch = false

    companion object {
        const val SHARED_API_KEY = "d64117f26031a428449f102ced3aba73"
        private const val TMDB_BASE  = "https://api.themoviedb.org/3"
        private const val PROXY_BASE = "https://proxy.valhallastream.com/?destination="
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        Pair("movie/now_playing",  "Latest Movies"),
        Pair("tv/airing_today",    "Latest TV Shows"),
        Pair("trending/movie/week","Trending Movies"),
        Pair("trending/tv/week",   "Trending TV Shows")
    )

    private fun buildUrl(path: String, useProxy: Boolean = true): String {
        val fullUrl = "$TMDB_BASE/$path${if (path.contains("?")) "&" else "?"}api_key=$SHARED_API_KEY"
        return if (useProxy) "$PROXY_BASE${URLEncoder.encode(fullUrl, "UTF-8")}" else fullUrl
    }

    private val tmdbHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer"    to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path     = "${request.data}?page=$page"
        val url      = buildUrl(path)
        val response = app.get(url, headers = tmdbHeaders).text
        val parsed   = tryParseJson<TmdbResultsResponse>(response) ?: return null

        val homeItems = parsed.results?.mapNotNull { item ->
            val isMovie  = item.title != null || request.data.contains("movie")
            val itemUrl  = if (isMovie) "$mainUrl/movie/${item.id}" else "$mainUrl/tv/${item.id}"
            val title    = item.title ?: item.name ?: return@mapNotNull null
            val poster   = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

            if (isMovie) {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) { this.posterUrl = poster }
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, homeItems, hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url          = buildUrl("search/multi?query=$encodedQuery&page=$page")
        val response     = app.get(url, headers = tmdbHeaders).text
        val parsed       = tryParseJson<TmdbResultsResponse>(response) ?: return null

        val items = parsed.results?.mapNotNull { item ->
            val title       = item.title ?: item.name ?: return@mapNotNull null
            val poster    = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val mediaType = item.mediaType ?: (if (item.title != null) "movie" else "tv")
            val itemUrl   = "$mainUrl/$mediaType/${item.id}"

            when (mediaType) {
                "movie" -> newMovieSearchResponse(title, itemUrl, TvType.Movie) { this.posterUrl = poster }
                "tv"    -> newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) { this.posterUrl = poster }
                else    -> null
            }
        } ?: emptyList()

        return newSearchResponseList(items, hasNext = items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanPath  = url.replace("$mainUrl/", "")
        val type       = cleanPath.substringBefore("/")
        val id         = cleanPath.substringAfter("/")
        val detailsUrl = buildUrl("$cleanPath?append_to_response=external_ids")
        val response   = app.get(detailsUrl, headers = tmdbHeaders).text
        val item       = tryParseJson<TmdbDetailResult>(response) ?: return null

        val title  = item.title ?: item.name ?: return null
        val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val genres = item.genres?.mapNotNull { it.name }

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = item.overview
                this.year      = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                this.tags      = genres
                item.voteAverage?.let { this.score = Score.from10(it) }
            }
        } else {
            val episodes = item.seasons
                ?.filter { (it.seasonNumber ?: 0) > 0 }
                ?.amap { season ->
                    val seasonNum = season.seasonNumber ?: return@amap null
                    try {
                        val seasonUrl      = buildUrl("$type/$id/season/$seasonNum")
                        val seasonResponse = app.get(seasonUrl, headers = tmdbHeaders).text
                        val seasonData     = tryParseJson<TmdbSeasonResponse>(seasonResponse)

                        seasonData?.episodes?.mapNotNull { ep ->
                            val epNum = ep.episodeNumber ?: return@mapNotNull null
                            val epData = "$url?season=$seasonNum&episode=$epNum"
                            newEpisode(epData) {
                                this.name    = ep.name
                                this.season  = seasonNum
                                this.episode = epNum
                            }
                        }
                    } catch (e: Exception) {
                        // Rektifikasi Sukses: Pemanggilan top-level function ter-import
                        logError(e)
                        null
                    }
                }
                ?.filterNotNull()
                ?.flatten()
                ?.sortedWith(compareBy<Episode> { it.season }.thenBy { it.episode })
                ?: emptyList()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = item.overview
                this.year      = item.firstAirDate?.substringBefore("-")?.toIntOrNull()
                this.tags      = genres
                item.voteAverage?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val primeSrcHelper = PrimeSrcHelper()

        // Rektifikasi Sukses: Passing argumen apiKey pelengkap parameter kaku
        val nonEmbedResult = primeSrcHelper.invokePrimeSrc(
            data            = data,
            mainUrl         = mainUrl,
            providerName    = this.name,
            apiKey          = SHARED_API_KEY,
            subtitleCallback = subtitleCallback,
            callback        = callback
        )

        val embedResult = primeSrcHelper.invokeEmbedMode(
            data             = data,
            mainUrl          = mainUrl,
            subtitleCallback = subtitleCallback,
            callback         = callback
        )

        return nonEmbedResult || embedResult
    }

    data class TmdbResultsResponse(
        @JsonProperty("results") val results: List<TmdbItem>?
    )

    data class TmdbItem(
        @JsonProperty("id")          val id:         Int,
        @JsonProperty("title")       val title:      String?,
        @JsonProperty("name")        val name:       String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("media_type")  val mediaType:  String?
    )

    data class TmdbDetailResult(
        @JsonProperty("title")          val title:        String?,
        @JsonProperty("name")           val name:         String?,
        @JsonProperty("overview")       val overview:     String?,
        @JsonProperty("poster_path")    val posterPath:   String?,
        @JsonProperty("release_date")   val releaseDate:  String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average")   val voteAverage:  Double?,
        @JsonProperty("seasons")        val seasons:      List<TmdbSeasonItem>?,
        @JsonProperty("genres")         val genres:       List<TmdbGenreItem>?
    )

    data class TmdbGenreItem(
        @JsonProperty("name") val name: String?
    )

    data class TmdbSeasonItem(
        @JsonProperty("season_number") val seasonNumber: Int?
    )

    data class TmdbSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TmdbEpisodeItem>?
    )

    data class TmdbEpisodeItem(
        @JsonProperty("name")           val name:          String?,
        @JsonProperty("episode_number") val episodeNumber: Int?
    )
}
