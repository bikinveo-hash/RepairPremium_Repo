package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class RiveStreamProvider : MainAPI() {
    override var name = "RiveStream"
    override var mainUrl = "https://www.rivestream.app"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true

    companion object {
        private const val TMDB_API_KEY = "d64117f26031a428449f102ced3aba73"
        private const val TMDB_BASE = "https://api.themoviedb.org/3"
        private const val PROXY_BASE = "https://proxy.valhallastream.com/?destination="
    }

    override val mainPage = mainPageOf(
        Pair("movie/now_playing", "Latest Movies"),
        Pair("tv/airing_today", "Latest TV Shows"),
        Pair("trending/movie/week", "Trending Movies"),
        Pair("trending/tv/week", "Trending TV Shows")
    )

    private fun buildUrl(path: String, useProxy: Boolean = true): String {
        val fullUrl = "$TMDB_BASE/$path${if (path.contains("?")) "&" else "?"}api_key=$TMDB_API_KEY"
        return if (useProxy) "$PROXY_BASE${URLEncoder.encode(fullUrl, "utf-8")}" else fullUrl
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val tmdbPath = request.data
        val tmdbUrl = buildUrl(tmdbPath, useProxy = true)
        val response = app.get(tmdbUrl).text
        val results = tryParseJson<TmdbResultsResponse>(response)?.results ?: return null

        val homeItems = results.mapNotNull { item ->
            val isMovie = item.mediaType == "movie" || tmdbPath.contains("movie")
            val cleanUrl = if (isMovie) "$mainUrl/movie/${item.id}" else "$mainUrl/tv/${item.id}"
            
            if (isMovie) {
                newMovieSearchResponse(
                    name = item.title ?: item.name ?: "",
                    url = cleanUrl,
                    type = TvType.Movie
                ) {
                    this.posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                }
            } else {
                newTvSeriesSearchResponse(
                    name = item.title ?: item.name ?: "",
                    url = cleanUrl,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                }
            }
        }

        return newHomePageResponse(
            name = request.name,
            list = homeItems,
            horizontalImages = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchPath = "search/multi?query=${URLEncoder.encode(query, "utf-8")}"
        val searchUrl = buildUrl(searchPath, useProxy = true)
        val response = app.get(searchUrl).text
        val results = tryParseJson<TmdbResultsResponse>(response)?.results ?: return null

        return results.mapNotNull { item ->
            val isMovie = item.mediaType == "movie"
            val isTv = item.mediaType == "tv"
            if (!isMovie && !isTv) return@mapNotNull null
            
            val cleanUrl = if (isMovie) "$mainUrl/movie/${item.id}" else "$mainUrl/tv/${item.id}"
            
            if (isMovie) {
                newMovieSearchResponse(
                    name = item.title ?: item.name ?: "",
                    url = cleanUrl,
                    type = TvType.Movie
                ) {
                    this.posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                }
            } else {
                newTvSeriesSearchResponse(
                    name = item.title ?: item.name ?: "",
                    url = cleanUrl,
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("movie/").substringAfter("tv/").substringBefore("?").substringBefore("/")
        val type = if (url.contains("/movie/")) TvType.Movie else TvType.TvSeries

        val tmdbPath = if (type == TvType.Movie) "movie/$id" else "tv/$id"
        val tmdbUrl = buildUrl(tmdbPath, useProxy = true)
        
        println("[RIVE_AUDIT] Loading TMDB URL: $tmdbUrl")
        val response = app.get(tmdbUrl).text
        val detail = tryParseJson<TmdbDetailResult>(response) ?: return null

        if (type == TvType.Movie) {
            return newMovieLoadResponse(
                name = detail.title ?: detail.name ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = detail.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.plot = detail.overview
                this.year = detail.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                this.tags = detail.genres?.mapNotNull { it.name }
                this.rating = detail.voteAverage?.times(10)?.toInt()
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasonsCount = detail.seasons?.mapNotNull { it.seasonNumber }?.maxOrNull() ?: 1
            
            for (seasonNum in 1..seasonsCount) {
                val seasonPath = "tv/$id/season/$seasonNum"
                val seasonUrl = buildUrl(seasonPath, useProxy = true)
                try {
                    val seasonRes = app.get(seasonUrl).text
                    val seasonData = tryParseJson<TmdbSeasonResponse>(seasonRes)
                    seasonData?.episodes?.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        episodes.add(
                            Episode(
                                data = "id=$id&type=tv&season=$seasonNum&episode=$epNum",
                                name = ep.name,
                                season = seasonNum,
                                episode = epNum
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return newTvSeriesLoadResponse(
                name = detail.title ?: detail.name ?: "",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = detail.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                this.plot = detail.overview
                this.year = detail.firstAirDate?.split("-")?.firstOrNull()?.toIntOrNull()
                this.tags = detail.genres?.mapNotNull { it.name }
                this.rating = detail.voteAverage?.times(10)?.toInt()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return PrimeSrcHelper().invokePrimeSrc(
            data = data,
            mainUrl = mainUrl,
            providerName = name,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    // ===== DATA CLASSES TMDB =====
    data class TmdbResultsResponse(@JsonProperty("results") val results: List<TmdbItem>?)
    data class TmdbItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("media_type") val mediaType: String?
    )
    data class TmdbDetailResult(
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("seasons") val seasons: List<TmdbSeasonItem>?,
        @JsonProperty("genres") val genres: List<TmdbGenreItem>?
    )
    data class TmdbGenreItem(@JsonProperty("name") val name: String?)
    data class TmdbSeasonItem(@JsonProperty("season_number") val seasonNumber: Int?)
    data class TmdbSeasonResponse(@JsonProperty("episodes") val episodes: List<TmdbEpisodeItem>?)
    data class TmdbEpisodeItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?
    )
}
