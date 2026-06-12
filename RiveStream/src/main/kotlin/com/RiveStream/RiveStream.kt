package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.ArrayList

class RiveStreamProvider : MainAPI() {
    override var mainUrl = "https://www.rivestream.app"
    override var name = "RiveStream"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie, 
        TvType.TvSeries, 
        TvType.AsianDrama, 
        TvType.Anime
    )

    private const val PROXY_BASE = "https://proxy.valhallastream.com/?destination="
    private const val TMDB_API = "https://api.themoviedb.org/3"
    private const val API_KEY = "d64117f26031a428449f102ced3aba73"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // FIX OPTIMALISASI: Anti-Blank Page via Direct TMDB Fallback
    private suspend fun safeTmdbGet(path: String, useProxy: Boolean = true): String {
        val targetUrl = "$TMDB_API$path&api_key=$API_KEY"
        val finalUrl = if (useProxy) "$PROXY_BASE$targetUrl" else targetUrl
        return try {
            app.get(finalUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).text
        } catch (e: Exception) {
            if (useProxy) {
                // Jalur Darurat: Jika proxy valhalla down, tembak langsung ke server asli TMDB
                safeTmdbGet(path, useProxy = false)
            } else {
                throw e
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val lists = ArrayList<HomePageList>()
        val categories = listOf(
            Pair("/movie/popular?language=en-US&page=1", "Film Populer"),
            Pair("/tv/popular?language=en-US&page=1", "Serial Populer"),
            Pair("/trending/all/day?language=en-US", "Sedang Tren Hari Ini")
        )

        categories.forEach { (path, title) ->
            try {
                val jsonText = safeTmdbGet(path)
                val response = tryParseJson<TmdbSearchResponse>(jsonText)
                val results = response?.results?.mapNotNull { item ->
                    val isMovie = item.mediaType == "movie" || path.contains("movie")
                    val mediaId = item.id ?: return@mapNotNull null
                    val titleName = item.title ?: item.name ?: return@mapNotNull null
                    val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
                    
                    val type = if (isMovie) TvType.Movie else TvType.TvSeries

                    MovieSearchResponse(
                        titleName,
                        "$mainUrl/${if (isMovie) "movie" else "tv"}/$mediaId",
                        this.name,
                        type,
                        poster,
                        item.voteAverage?.let { Score.from10(it) }
                    )
                } ?: emptyList()

                if (results.isNotEmpty()) {
                    lists.add(HomePageList(title, results))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return HomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.replace(" ", "%20")
        val movieSearchJson = safeTmdbGet("/search/movie?query=$cleanQuery&language=en-US&page=1")
        val tvSearchJson = safeTmdbGet("/search/tv?query=$cleanQuery&language=en-US&page=1")

        val movies = tryParseJson<TmdbSearchResponse>(movieSearchJson)?.results?.mapNotNull { item ->
            MovieSearchResponse(
                item.title ?: return@mapNotNull null,
                "$mainUrl/movie/${item.id}",
                this.name,
                TvType.Movie,
                item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                item.voteAverage?.let { Score.from10(it) }
            )
        } ?: emptyList()

        val tvShows = tryParseJson<TmdbSearchResponse>(tvSearchJson)?.results?.mapNotNull { item ->
            MovieSearchResponse(
                item.name ?: return@mapNotNull null,
                "$mainUrl/tv/${item.id}",
                this.name,
                TvType.TvSeries,
                item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                item.voteAverage?.let { Score.from10(it) }
            )
        } ?: emptyList()

        return movies + tvShows
    }

    override suspend fun load(url: String): LoadResponse? {
        val isMovie = url.contains("/movie/")
        val id = url.substringAfterLast("/")
        val path = if (isMovie) "/movie/$id?language=en-US" else "/tv/$id?language=en-US&append_to_response=external_ids"
        
        val jsonText = safeTmdbGet(path)
        val item = tryParseJson<TmdbDetailResult>(jsonText) ?: return null

        val title = item.title ?: item.name ?: return null
        val overview = item.overview
        val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val genres = item.genres?.mapNotNull { it.name } ?: emptyList()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = overview
                this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                this.tags = genres
                item.voteAverage?.let { this.score = Score.from10(it) }
            }
        } else {
            val episodes = ArrayList<Episode>()
            item.seasons?.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == 0) return@forEach // Cegah pencampuran indeks konten bonus/spesial

                try {
                    val seasonJson = safeTmdbGet("/tv/$id/season/$seasonNum?language=en-US")
                    val seasonData = tryParseJson<TmdbSeasonDetail>(seasonJson)
                    
                    seasonData?.episodes?.forEach { ep ->
                        // FIX BUG VISUAL: Cegah Gambar Pecah Jika Still Path Bernilai Null pada Episode Masa Depan
                        val epThumb = if (!ep.stillPath.isNullOrBlank()) {
                            "https://image.tmdb.org/t/p/w300${ep.stillPath}"
                        } else {
                            "https://image.tmdb.org/t/p/w300${item.posterPath}"
                        }

                        episodes.add(
                            Episode(
                                data = "$mainUrl/tv/$id?season=${ep.seasonNumber}&episode=${ep.episodeNumber}",
                                name = ep.name ?: "Episode ${ep.episodeNumber}",
                                episode = ep.episodeNumber,
                                season = ep.seasonNumber,
                                posterUrl = epThumb
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // FIX INTEGRASI CORE: Pengelompokan K-Drama & Anime Secara Akurat Lewat origin_country
            val isAsianDrama = item.originCountry?.contains("KR") == true || item.originCountry?.contains("TW") == true
            val isAnime = item.originCountry?.contains("JP") == true
            
            val finalType = when {
                isAnime -> TvType.Anime
                isAsianDrama -> TvType.AsianDrama
                else -> TvType.TvSeries
            }

            return newTvSeriesLoadResponse(title, url, finalType, episodes) {
                this.posterUrl = poster
                this.plot = overview
                this.year = item.firstAirDate?.substringBefore("-")?.toIntOrNull()
                this.tags = genres
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
        val isMovie = data.contains("/movie/")
        val cleanId = data.substringBefore("?").substringAfterLast("/")
        
        PrimeSrcHelper.getPrimeSrcLinks(cleanId, isMovie, data, subtitleCallback, callback)
        return true
    }
}

// ================= MODEL DATA KERNEL (JACKSON) =================
data class TmdbSearchResponse(
    @JsonProperty("results") val results: List<TmdbSearchItem>?
)

data class TmdbSearchItem(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("media_type") val mediaType: String?,
    @JsonProperty("vote_average") val voteAverage: Double?
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
    @JsonProperty("genres") val genres: List<TmdbGenreItem>?,
    @JsonProperty("origin_country") val originCountry: List<String>? = null
)

data class TmdbSeasonItem(
    @JsonProperty("season_number") val seasonNumber: Int?
)

data class TmdbGenreItem(
    @JsonProperty("name") val name: String?
)

data class TmdbSeasonDetail(
    @JsonProperty("episodes") val episodes: List<TmdbEpisodeItem>?
)

data class TmdbEpisodeItem(
    @JsonProperty("episode_number") val episodeNumber: Int,
    @JsonProperty("season_number") val seasonNumber: Int,
    @JsonProperty("name") val name: String?,
    @JsonProperty("still_path") val stillPath: String?
)
