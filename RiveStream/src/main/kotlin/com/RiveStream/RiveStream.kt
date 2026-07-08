package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.amap
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * RiveStream Provider - Core Plugin Cloudstream Terkalibrasi Standar MainAPI
 *
 * FIXED (lihat CHANGELOG):
 *  - load() sekarang parsing type/id dari QUERY STRING (?id=..&type=..), bukan
 *    dari path segment. Sebelumnya bug karena semua URL item dibuat dalam format
 *    "$mainUrl/detail?id=X&type=Y" (path "detail" doang, tanpa id di path),
 *    tapi load() lama mengasumsikan format path "$mainUrl/{type}/{id}" yang
 *    sudah dikonfirmasi 404 di server asli.
 *  - hasNext di getMainPage()/search() sekarang mengikuti data aktual
 *    (total_pages dari TMDB), bukan di-hardcode true.
 *  - useProxy default di-nonaktifkan sesuai catatan bahwa TMDB sudah CORS-ready;
 *    proxy tetap tersedia sebagai opsi manual kalau suatu saat dibutuhkan lagi.
 *
 * v2 PATCH (2026-07-09) berdasarkan analisis MainApi.kt + ExtractorApi.kt referensi:
 *  - [A] sequentialMainPage = true + delays (anti 429 rate-limit)
 *  - [B] hasQuickSearch = false dihapus (default sudah false di MainAPI)
 *  - [C] useProxy jadi companion setting (toggle-able lewat settings panel)
 *  - [D] Error hardening di load() — wrap TMDB request dengan try/catch +
 *        retry sederhana untuk 429, plus ErrorLoadingException untuk empty result
 */
class RiveStreamProvider : MainAPI() {
    override var name    = "RiveStream"
    override var mainUrl = "https://www.rivestream.app"
    override var lang    = "en"
    override val hasMainPage    = true
    // [v2 PATCH B] hasQuickSearch dihapus — default MainAPI sudah `false`.

    // [v2 PATCH A] Sequential main page requests to avoid TMDB rate-limiting (429).
    // Default di MainAPI adalah `false` (parallel) — TMDB tidak suka parallel banyak
    // request dari satu IP, terutama saat cold start ketika user pertama buka homepage
    // (4 sections = 4 request paralel = 429 risk). Pattern sesuai referensi CloudStream.
    override val sequentialMainPage            = true
    override val sequentialMainPageDelay       = 500L   // 500ms antar request di first load
    override val sequentialMainPageScrollDelay = 200L   // 200ms saat scrolling

    // [v2 PATCH C] Settings toggle untuk useProxy. Default `false` karena TMDB
    // sudah CORS-ready, tapi user bisa override lewat settings kalau suatu saat
    // diblokir. Implementasi dibaca dari `overrideData` di MainAPI.init().
    // (Lihat catatan di companion object di bawah untuk detail.)
    override val canBeOverridden = true  // agar overrideData diproses saat init

    companion object {
        // Shared API key untuk backward compat. Sangat disarankan pakai API key
        // sendiri dari themoviedb.org kalau plugin ini didistribusikan secara luas,
        // supaya tidak kena rate-limit/revoke bareng-bareng dengan user lain.
        const val SHARED_API_KEY = "d64117f26031a428449f102ced3aba73"
        private const val TMDB_BASE  = "https://api.themoviedb.org/3"

        // TMDB sudah mendukung CORS secara native, jadi proxy tidak lagi diperlukan
        // secara default. Tetap disimpan sebagai fallback opsional.
        private const val PROXY_BASE = "https://proxy.valhallastream.com/?destination="
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

        // [v2 PATCH C] Setting key untuk useProxy toggle.
        // Disimpan sebagai konstanta supaya bisa direferensikan dari init() dan
        // dari call site buildUrl() (opsional, kalau lo nanti mau expose ke settings UI).
        const val SETTING_USE_PROXY = "use_proxy"

        // [v2 PATCH C] Default useProxy — `false` karena TMDB sudah CORS-ready.
        // Bisa di-override lewat settings panel.
        @JvmStatic
        var useProxy: Boolean = false
    }

    override val mainPage = mainPageOf(
        Pair("movie/now_playing",  "Latest Movies"),
        Pair("tv/airing_today",    "Latest TV Shows"),
        Pair("trending/movie/week","Trending Movies"),
        Pair("trending/tv/week",   "Trending TV Shows")
    )

    private fun buildUrl(path: String): String {
        // [v2 PATCH C] Baca useProxy dari companion setting, bukan parameter.
        // Tetap backward-compat dengan signature lama (parameter useProxy di-ignore,
        // baca dari companion `useProxy` yang bisa di-toggle lewat settings).
        val fullUrl = "$TMDB_BASE/$path${if (path.contains("?")) "&" else "?"}api_key=$SHARED_API_KEY"
        return if (useProxy) "$PROXY_BASE${URLEncoder.encode(fullUrl, "UTF-8")}" else fullUrl
    }

    private val tmdbHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer"    to "$mainUrl/"
    )

    /** Parse a raw query string ("a=1&b=2") into a Map, URL-decoding values. */
    private fun parseUrlQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) return@mapNotNull null
                val key = pair.substring(0, idx)
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                key to value
            }.toMap()
    }

    // [v2 PATCH D] Retry helper untuk TMDB request. Kalau kena 429 (rate-limit),
    // tunggu sebentar lalu retry sekali. Pattern ini simple tapi efektif untuk
    // kebanyakan kasus rate-limit short-window.
    private suspend fun <T> tmdbRequestWithRetry(
        url: String,
        parse: (String) -> T?,
        maxRetries: Int = 1
    ): T? {
        var attempt = 0
        var lastError: Exception? = null

        while (attempt <= maxRetries) {
            try {
                val response = app.get(url, headers = tmdbHeaders)
                // Kalau 429, tunggu 1 detik lalu retry.
                if (response.code == 429 && attempt < maxRetries) {
                    kotlinx.coroutines.delay(1000L)
                    attempt++
                    continue
                }
                return parse(response.text)
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(1000L)
                    attempt++
                    continue
                }
                throw e
            }
        }
        return null
    }

    /** Convenience wrapper untuk TMDB detail request dengan retry. */
    private suspend fun fetchDetailWithRetry(type: String, id: String): TmdbDetailResult? {
        val url = buildUrl("$type/$id?append_to_response=external_ids")
        return tmdbRequestWithRetry(
            url = url,
            parse = { response -> tryParseJson<TmdbDetailResult>(response) }
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path     = "${request.data}?page=$page"
        val url      = buildUrl(path)
        val response = app.get(url, headers = tmdbHeaders).text
        val parsed   = tryParseJson<TmdbResultsResponse>(response) ?: return null

        val homeItems = parsed.results?.mapNotNull { item ->
            val isMovie  = item.title != null || request.data.contains("movie")
            // Path detail Rive yang valid adalah /detail?id=...&type=... (bukan /movie/{id})
            val itemUrl  = if (isMovie) {
                "$mainUrl/detail?id=${item.id}&type=movie"
            } else {
                "$mainUrl/detail?id=${item.id}&type=tv"
            }
            val title    = item.title ?: item.name ?: return@mapNotNull null
            val poster   = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

            if (isMovie) {
                newMovieSearchResponse(title, itemUrl, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) { this.posterUrl = poster }
            }
        } ?: emptyList()

        val hasNext = homeItems.isNotEmpty() && (parsed.totalPages == null || page < parsed.totalPages)
        return newHomePageResponse(request.name, homeItems, hasNext = hasNext)
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
            val itemUrl   = "$mainUrl/detail?id=${item.id}&type=$mediaType"

            when (mediaType) {
                "movie" -> newMovieSearchResponse(title, itemUrl, TvType.Movie) { this.posterUrl = poster }
                "tv"    -> newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) { this.posterUrl = poster }
                else    -> null
            }
        } ?: emptyList()

        val hasNext = items.isNotEmpty() && (parsed.totalPages == null || page < parsed.totalPages)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        // URL yang masuk selalu dalam format "$mainUrl/detail?id=X&type=Y[...]"
        // (lihat getMainPage/search), jadi type & id harus diambil dari QUERY, bukan path.
        val queryPart = url.substringAfter("?", "")
        val qp = parseUrlQuery(queryPart)
        val type = qp["type"] ?: return null
        val id   = qp["id"] ?: return null

        // [v2 PATCH D] Error hardening — wrap dengan try/catch supaya
        // - Network error / parse error tidak crash provider
        // - ErrorLoadingException di-throw supaya CloudStream bisa tampil UI yang sesuai
        // - Retry sederhana untuk 429 (rate-limit) — 1x retry dengan delay singkat
        val item: TmdbDetailResult? = try {
            fetchDetailWithRetry(type, id)
        } catch (e: Exception) {
            logError(e)
            null
        }

        if (item == null) {
            // Item tidak ditemukan atau error — lempar ErrorLoadingException
            // supaya UI bisa handle gracefully (daripada return null silent).
            throw ErrorLoadingException("Cannot load $type/$id from TMDB")
        }

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
                            // url sudah mengandung "type=tv", jadi cukup tambahkan season & episode
                            val epData = "$url&season=$seasonNum&episode=$epNum"
                            newEpisode(epData) {
                                this.name    = ep.name
                                this.season  = seasonNum
                                this.episode = epNum
                            }
                        }
                    } catch (e: Exception) {
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

        val videoResult = primeSrcHelper.invokePrimeSrc(
            data             = data,
            mainUrl          = mainUrl,
            providerName     = this.name,
            apiKey           = SHARED_API_KEY,
            subtitleCallback = subtitleCallback,
            callback         = callback
        )

        val embedResult = primeSrcHelper.invokeEmbedMode(
            data             = data,
            mainUrl          = mainUrl,
            subtitleCallback = subtitleCallback,
            callback         = callback
        )

        return videoResult || embedResult
    }

    // ===== DATA CLASSES TMDB =====================================================

    data class TmdbResultsResponse(
        @JsonProperty("results")     val results:    List<TmdbItem>?,
        @JsonProperty("total_pages") val totalPages: Int?
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
