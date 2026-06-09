package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.mvvm.logError
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
        private const val GATEWAY_BASE = "https://primesrc.me"

        private fun buildUrl(path: String, useProxy: Boolean = true): String {
            val fullUrl = "$TMDB_BASE/$path${if (path.contains(\"?\")) \"&\" else \"?\"}api_key=$TMDB_API_KEY"
            return if (useProxy) "$PROXY_BASE${URLEncoder.encode(fullUrl, \"UTF-8\")}" else fullUrl
        }
    }

    // -----------------------------------------------------------------
    // STRUKTUR DATA CLASS UNTUK MODEL API PRIMESRC (ANTI-CRASH LOGIC)
    // -----------------------------------------------------------------
    data class PrimeServerResponse(
        @JsonProperty("servers") val servers: List<PrimeServerItem>?,
        @JsonProperty("info") val info: PrimeMediaInfo?
    )

    data class PrimeServerItem(
        @JsonProperty("name") val name: String,
        @JsonProperty("key") val key: String,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("file_size") val fileSize: String?
    )

    data class PrimeMediaInfo(
        @JsonProperty("title") val title: String?,
        @JsonProperty("tmdb_id") val tmdbId: Int?
    )

    data class PrimeLinkResponse(
        @JsonProperty("link") val link: String?
    )

    // -----------------------------------------------------------------
    // STRUKTUR DATA CLASS MODEL TMDB INTERNAL
    // -----------------------------------------------------------------
    data class MainPageResponse(
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

    // -----------------------------------------------------------------
    // KONFIGURASI HALAMAN UTAMA / MAIN PAGE
    // -----------------------------------------------------------------
    override val mainPage = mainPageOf(
        Pair("movie/now_playing", "Latest Movies"),
        Pair("tv/airing_today", "Airing Today TV Shows"),
        Pair("trending/all/day", "Trending Today")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = request.name
        val useProxy = !path.startsWith("trending/")
        val url = buildUrl("$path?page=$page", useProxy)
        val res = app.get(url).text
        val mapped = tryParseJson<MainPageResponse>(res)
        val homeItems = mapped?.results?.mapNotNull { item ->
            val isMovie = item.mediaType == "movie" || path.startsWith("movie/")
            val title = item.title ?: item.name ?: return@mapNotNull null
            newMovieSearchResponse(title, "${item.id},${if (isMovie) "movie" else "tv"}", TvType.Movie) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${item.posterPath}"
            }
        } ?: return null
        return newHomePageResponse(request.value, homeItems)
    }

    // -----------------------------------------------------------------
    // FITUR PENCARIAN / SEARCH DENGAN PROXY INTEGRASI
    // -----------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse>? {
        val url = buildUrl("search/multi?query=${URLEncoder.encode(query, "UTF-8")}&page=1", true)
        val res = app.get(url).text
        val mapped = tryParseJson<MainPageResponse>(res)
        return mapped?.results?.mapNotNull { item ->
            val isMovie = item.mediaType == "movie"
            val isTv = item.mediaType == "tv"
            if (!isMovie && !isTv) return@mapNotNull null
            val title = item.title ?: item.name ?: return@mapNotNull null
            newMovieSearchResponse(title, "${item.id},${item.mediaType}", TvType.Movie) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${item.posterPath}"
            }
        }
    }

    // -----------------------------------------------------------------
    // MEMUAT HALAMAN DETAIL (LOAD DATA EPISODE & MANIFEST MEDIA)
    // -----------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split(",")
        val id = parts.getOrNull(0) ?: return null
        val type = parts.getOrNull(1) ?: "movie"
        val isMovie = type == "movie"

        val detailUrl = buildUrl("$type/$id", !isMovie)
        val res = app.get(detailUrl).text
        val item = tryParseJson<TmdbDetailResult>(res) ?: return null
        val title = item.title ?: item.name ?: return null
        val poster = "https://image.tmdb.org/t/p/w500${item.posterPath}"

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = item.overview
                this.rating = item.voteAverage?.times(10)?.toInt()
                this.tags = item.genres?.mapNotNull { it.name }
            }
        } else {
            val episodesList = mutableListOf<Episode>()
            item.seasons?.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                val seasonUrl = buildUrl("tv/$id/season/$seasonNum", true)
                val seasonRes = app.get(seasonUrl).text
                val seasonData = tryParseJson<TmdbSeasonResponse>(seasonRes)
                seasonData?.episodes?.forEach { ep ->
                    val epNum = ep.episodeNumber ?: return@forEach
                    episodesList.add(Episode(
                        data = "$id,tv,$seasonNum,$epNum",
                        name = ep.name,
                        season = seasonNum,
                        episode = epNum
                    ))
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = item.overview
                this.rating = item.voteAverage?.times(10)?.toInt()
                this.tags = item.genres?.mapNotNull { it.name }
            }
        }
    }

    // -----------------------------------------------------------------
    // MESIN LINK SCRAPER UTAMA (TAHAP 1 -> SPIDERMAN ACTIVATION -> TAHAP 3)
    // -----------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCubic: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(",")
        val id = parts.getOrNull(0) ?: return false
        val type = parts.getOrNull(1) ?: "movie"
        
        // Membangun URL referer iframe dan URL API server secara dinamis (Movie / TV Series)
        val embedUrl = if (type == "movie") {
            "$GATEWAY_BASE/embed/movie?tmdb=$id"
        } else {
            val season = parts.getOrNull(2) ?: "1"
            val episode = parts.getOrNull(3) ?: "1"
            "$GATEWAY_BASE/embed/tv?tmdb=$id&season=$season&episode=$episode"
        }

        val apiServerUrl = if (type == "movie") {
            "$GATEWAY_BASE/api/v1/s?tmdb=$id&type=movie"
        } else {
            val season = parts.getOrNull(2) ?: "1"
            val episode = parts.getOrNull(3) ?: "1"
            "$GATEWAY_BASE/api/v1/s?tmdb=$id&type=tv&season=$season&episode=$episode"
        }

        // TAHAP 1: Mengambil manifes daftar kunci server penyedia
        val serverResponseText = app.get(
            apiServerUrl,
            referer = "$mainUrl/"
        ).text

        val parsedResponse = tryParseJson<PrimeServerResponse>(serverResponseText)
        val serverList = parsedResponse?.servers
        if (serverList.isNullOrEmpty()) return false

        // Memproses ekstraksi tautan untuk setiap kunci server yang tersedia
        serverList.forEach { server ->
            try {
                // TAHAP 2: Sinyal Ketok Pintu Rahasia (/spiderman) untuk Mengaktifkan Token Server
                app.get(
                    "$GATEWAY_BASE/spiderman?l=${server.key}",
                    referer = embedUrl
                )

                // TAHAP 3: Meminta Tautan Direct Video Stream dari Endpoint /api/v1/l
                val decryptUrl = "$GATEWAY_BASE/api/v1/l?key=${server.key}"
                val decryptResponseText = app.get(
                    decryptUrl,
                    referer = embedUrl
                ).text

                val parsedLinkData = tryParseJson<PrimeLinkResponse>(decryptResponseText)
                val finalVideoLink = parsedLinkData?.link

                // Validasi akhir tautan video sebelum diumpankan ke Core Extractor Cloudstream
                if (!finalVideoLink.isNullOrEmpty() && finalVideoLink != "null") {
                    loadExtractor(
                        url = finalVideoLink,
                        referer = embedUrl,
                        callback = callback
                    )
                }
            } catch (e: Exception) {
                // Mitigasi Anti-Crash: Jika satu server mati, iterasi tetap melompat aman ke server berikutnya
                logError(e)
            }
        }
        return true
    }
}
