package com.RiveStream

import android.util.Base64
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
        return if (useProxy) "$PROXY_BASE${URLEncoder.encode(fullUrl, "UTF-8")}" else fullUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
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
                newMovieSearchResponse(title, idAndType, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, idAndType, TvType.TvSeries) { this.posterUrl = poster }
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
                newMovieSearchResponse(title, idAndType, TvType.Movie) { this.posterUrl = poster }
            } else if (mediaType == "tv") {
                newTvSeriesSearchResponse(title, idAndType, TvType.TvSeries) { this.posterUrl = poster }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanPath = url.replace("$mainUrl/", "")
        val type = cleanPath.substringBefore("/")
        val id = cleanPath.substringAfter("/")
        val detailsUrl = buildUrl("$cleanPath?append_to_response=external_ids")
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
                item.voteAverage?.let { this.score = Score.from10(it) }
            }
        } else {
            val episodes = Coroutines.threadSafeListOf<Episode>()
            item.seasons?.amap { season ->
                val seasonNum = season.seasonNumber ?: return@amap
                if (seasonNum == 0) return@amap
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
                } catch (e: Exception) { e.printStackTrace() }
            }

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
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?s=")
        val cleanId = cleanData.substringAfter("/").substringBefore("?")

        val serversUrl = if (isMovie) {
            "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=movie"
        } else {
            val season = cleanData.substringAfter("?s=").substringBefore("&")
            val episode = cleanData.substringAfter("&e=")
            "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=tv&season=$season&episode=$episode"
        }

        val baseHeaders = mapOf(
            "Host" to "primesrc.me",
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache",
            "Accept" to "*/*",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "User-Agent" to USER_AGENT,
            "Referer" to if (isMovie) "https://primesrc.me/embed/movie?tmdb=$cleanId" else "https://primesrc.me/embed/tv?tmdb=$cleanId"
        )

        var linksFound = 0

        try {
            // Langkah 1: Ambil daftar kuncian internal key penyamaran server luar
            val response = app.get(serversUrl, headers = baseHeaders).text
            val parsed = tryParseJson<PrimeSrcResponse>(response) ?: return false

            parsed.servers?.forEach { server ->
                val internalKey = server.key ?: return@forEach
                val hostName = server.name?.lowercase() ?: return@forEach

                try {
                    // Langkah 2: Lakukan dekripsi tahap kedua untuk mendapatkan URL embed asli hoster
                    val decryptUrl = "https://primesrc.me/api/v1/l?key=$internalKey"
                    val decryptResponse = app.get(decryptUrl, headers = baseHeaders).text
                    val linkData = tryParseJson<PrimeSrcLinkResponse>(decryptResponse) ?: return@forEach
                    val realEmbedUrl = linkData.link ?: return@forEach

                    // Langkah 3: Ekstraksi string dokumen HTML murni tanpa WebView background core
                    when {
                        hostName.contains("streamtape") -> {
                            val html = app.get(realEmbedUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
                            
                            // Ekstrak parameter enkripsi kancing ketukan tersembunyi
                            val match = Regex("""'//[^']+/get_vi'\s*\+\s*\('([^']+)'\)\.substring\((\d+)\)""").find(html)
                            val encryptedPart = match?.groupValues?.get(1)
                            val skipCount = match?.groupValues?.get(2)?.toIntOrNull() ?: 4
                            
                            if (encryptedPart != null) {
                                val finalParam = encryptedPart.substring(skipCount)
                                val directVideoUrl = "https://streamtape.com/get_vi$finalParam&stream=1"
                                
                                // Pemanggilan menggunakan urutan Positional Arguments murni agar kebal eror build
                                callback(ExtractorLink(
                                    "Streamtape Rive",
                                    "Streamtape Direct (MP4)",
                                    directVideoUrl,
                                    realEmbedUrl,
                                    Qualities.P720.value,
                                    false
                                ))
                                linksFound++
                            }
                        }

                        hostName.contains("voe") -> {
                            val html = app.get(realEmbedUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
                            val base64Regex = Regex("""window\.atob\s*\(\s*'([^']+)'\s*\)""")
                            val base64Match = base64Regex.find(html)?.groupValues?.get(1)
                            
                            var videoUrl = ""
                            if (base64Match != null) {
                                val decoded = String(Base64.decode(base64Match, Base64.DEFAULT))
                                if (decoded.contains(".m3u8")) videoUrl = decoded
                            }
                            if (videoUrl.isEmpty()) {
                                videoUrl = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1) ?: ""
                            }
                            
                            if (videoUrl.isNotEmpty()) {
                                callback(ExtractorLink(
                                    "Voe Rive",
                                    "Voe (HLS)",
                                    videoUrl,
                                    realEmbedUrl,
                                    Qualities.Unknown.value,
                                    true
                                ))
                                linksFound++
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return linksFound > 0
    }
    
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

data class PrimeSrcResponse(@JsonProperty("servers") val servers: List<PrimeSrcServer>?)
data class PrimeSrcServer(@JsonProperty("name") val name: String?, @JsonProperty("key") val key: String?)

data class PrimeSrcLinkResponse(
    @JsonProperty("link") val link: String?,
    @JsonProperty("host") val host: String?
)
