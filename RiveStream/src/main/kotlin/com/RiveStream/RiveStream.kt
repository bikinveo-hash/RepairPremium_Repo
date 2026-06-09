package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.apmap
import java.net.URLEncoder

class RiveStreamProvider : MainAPI() {
    override var name = "RiveStream"
    override var mainUrl = "https://www.rivestream.app"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true

    companion object {
        // Kunci API TMDB asli milik RiveStream yang ditemukan di Webpack Chunk 2873
        private const val TMDB_API_KEY = "d64117f26031a428449f102ced3aba73"
        private const val TMDB_BASE = "https://api.themoviedb.org/3"
        
        // Server Reverse Proxy RiveStream untuk bypass geo-block/ISP blocking
        private const val PROXY_BASE = "https://proxy.valhallastream.com/?destination="
        
        // API Server Scraper RiveStream untuk mengambil link stream/embed player
        private const val SCRAPPER_BASE = "https://scrapper.rivestream.app"

        // Helper untuk membungkus request lewat proxy jika fetchMode server diaktifkan
        private fun buildUrl(path: String, useProxy: Boolean = true): String {
            val fullUrl = "$TMDB_BASE/$path${if (path.contains("?")) "&" else "?"}api_key=$TMDB_API_KEY"
            return if (useProxy) "$PROXY_BASE${URLEncoder.encode(fullUrl, "UTF-8")}" else fullUrl
        }
    }

    override val mainPage = mainPageOf(
        Pair("movie/now_playing", "Latest Movies"),
        Pair("tv/airing_today", "Latest TV Shows"),
        Pair("trending/movie/week", "Trending Movies"),
        Pair("trending/tv/week", "Trending TV Shows")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val path = "${request.data}?page=$page"
        val url = buildUrl(path)
        
        val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).text
        val parsed = tryParseJson<TmdbResultsResponse>(response) ?: return null
        
        val homeItems = parsed.results?.mapNotNull { item ->
            val isMovie = item.title != null || request.data.contains("movie")
            val idAndType = if (isMovie) "movie/${item.id}" else "tv/${item.id}"
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

            if (isMovie) {
                newMovieSearchResponse(title, idAndType, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, idAndType, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, homeItems, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = buildUrl("search/multi?query=$encodedQuery")
        
        val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).text
        val parsed = tryParseJson<TmdbResultsResponse>(response) ?: return null

        return parsed.results?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val mediaType = item.mediaType ?: (if (item.title != null) "movie" else "tv")
            val idAndType = "$mediaType/${item.id}"

            if (mediaType == "movie") {
                newMovieSearchResponse(title, idAndType, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else if (mediaType == "tv") {
                newTvSeriesSearchResponse(title, idAndType, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val type = url.substringBefore("/")
        val id = url.substringAfter("/")
        
        val detailsUrl = buildUrl("$url?append_to_response=external_ids")
        val response = app.get(detailsUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).text
        val item = tryParseJson<TmdbDetailResult>(response) ?: return null
        
        val title = item.title ?: item.name ?: return null
        val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val overview = item.overview
        val genres = item.genres?.mapNotNull { it.name }

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = overview
                this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                this.tags = genres
                item.voteAverage?.let { this.score = Score.from10(it) } // Menggunakan Score API terbaru
            }
        } else {
            // threadSafeListOf digunakan karena modifikasi array dilakukan serentak oleh apmap
            val episodes = Coroutines.threadSafeListOf<Episode>()
            
            // apmap mengeksekusi request data antar-season secara pararel di background thread
            item.seasons?.apmap { season ->
                val seasonNum = season.seasonNumber ?: return@apmap
                if (seasonNum == 0) return@apmap
                
                try {
                    val seasonUrl = buildUrl("$type/$id/season/$seasonNum")
                    val seasonResponse = app.get(seasonUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).text
                    val seasonData = tryParseJson<TmdbSeasonResponse>(seasonResponse)
                    
                    seasonData?.episodes?.forEach { ep ->
                        episodes.add(newEpisode(url) {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = ep.episodeNumber
                            this.data = "$url?s=$seasonNum&e=${ep.episodeNumber}"
                        })
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Menyusun urutan episode agar rapi sesuai urutan season dan episodenya
            val sortedEpisodes = episodes.sortedWith(compareBy<Episode> { it.season }.thenBy { it.episode })

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
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
        val isMovie = !data.contains("?s=")
        val type = data.substringBefore("/")
        val cleanId = data.substringAfter("/").substringBefore("?")
        val providersUrl = "$SCRAPPER_BASE/api/embeds" 
        
        var linksFoundCount = 0

        try {
            val providersResponse = app.get(providersUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).text
            val providersList = tryParseJson<List<String>>(providersResponse) ?: listOf("vidsrc", "embedsu")

            for (provider in providersList) {
                val finalScrapUrl = if (isMovie) {
                    "$SCRAPPER_BASE/api/embed?provider=$provider&id=$cleanId"
                } else {
                    val season = data.substringAfter("?s=").substringBefore("&")
                    val episode = data.substringAfter("&e=")
                    "$SCRAPPER_BASE/api/embed?provider=$provider&id=$cleanId&season=$season&episode=$episode"
                }

                val embedResponse = app.get(finalScrapUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).text
                val embedData = tryParseJson<EmbedApiResponse>(embedResponse)

                if (embedData?.success == true && embedData.url != null) {
                    loadExtractor(embedData.url, "$mainUrl/", subtitleCallback) { link ->
                        linksFoundCount++
                        callback(link)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // --- SISTEM PERTAHANAN FALLBACK ---
        // Jika API Scrapper RiveStream zonk/gagal mengembalikan link video,
        // alihkan pencarian ke Vidsrc Engine universal menggunakan basis data ID TMDB yang sama.
        if (linksFoundCount == 0) {
            val season = if (!isMovie) data.substringAfter("?s=").substringBefore("&") else ""
            val episode = if (!isMovie) data.substringAfter("&e=") else ""
            val fallbackUrl = if (isMovie) "https://vidsrc.to/embed/movie/$cleanId" else "https://vidsrc.to/embed/tv/$cleanId/$season/$episode"
            loadExtractor(fallbackUrl, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

    // --- JACKSON JSON MODEL BINDINGS ---
    
    data class TmdbResultsResponse(
        @JsonProperty("results") val results: List<TmdbItem>?
    )

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
        @JsonProperty("name") val name: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?
    )

    data class EmbedApiResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("url") val url: String?
    )
}
