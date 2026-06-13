package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.threadSafeListOf
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URLEncoder

// ============================================================================
// MAIN PROVIDER: RiveStream
// ============================================================================

class RiveStreamProvider : MainAPI() {
    override var name = "RiveStream"
    override var mainUrl = "https://www.rivestream.app"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(
        Pair("movie/now_playing", "Latest Movies"),
        Pair("tv/airing_today", "Latest TV Shows"),
        Pair("trending/movie/week", "Trending Movies"),
        Pair("trending/tv/week", "Trending TV Shows")
    )

    private val primeSrcHelper = PrimeSrcHelper()

    // ===== HEADER STANDARD =====
    private fun stdHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to mainUrl,
        "Referer" to referer,
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not.A/Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
        "User-Agent" to USER_AGENT
    )

    // ===== MAIN PAGE =====
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = "${request.data}?page=$page"
        val url = "${TMDB_BASE}/${path}${if (path.contains("?")) "&" else "?"}api_key=$TMDB_API_KEY"

        val response = app.get(url, headers = stdHeaders()).text
        val parsed = tryParseJson<TmdbResultsResponse>(response) ?: return null

        val homeItems = parsed.results?.mapNotNull { item ->
            val isMovie = item.title != null || request.data.contains("movie")
            val idAndType = if (isMovie) "$mainUrl/movie/${item.id}" else "$mainUrl/tv/${item.id}"
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

            if (isMovie) {
                newMovieSearchResponse(title, idAndType, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, idAndType, TvType.TvSeries) { this.posterUrl = poster }
            }
        } ?: emptyList()

        val hasNext = (parsed.page ?: 1) < (parsed.totalPages ?: 1)
        return newHomePageResponse(request.name, homeItems, hasNext = hasNext)
    }

    // ===== SEARCH =====
    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "${TMDB_BASE}/search/multi?query=$encodedQuery&api_key=$TMDB_API_KEY"

        val response = app.get(url, headers = stdHeaders()).text
        val parsed = tryParseJson<TmdbResultsResponse>(response) ?: return null

        return parsed.results?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val mediaType = item.mediaType ?: (if (item.title != null) "movie" else "tv")
            val idAndType = "$mainUrl/$mediaType/${item.id}"

            if (mediaType == "movie") {
                newMovieSearchResponse(title, idAndType, TvType.Movie) { this.posterUrl = poster }
            } else if (mediaType == "tv") {
                newTvSeriesSearchResponse(title, idAndType, TvType.TvSeries) { this.posterUrl = poster }
            } else null
        }
    }

    // ===== LOAD =====
    override suspend fun load(url: String): LoadResponse? {
        val cleanPath = url.replace("$mainUrl/", "")
        val type = cleanPath.substringBefore("/")
        val id = cleanPath.substringAfter("/")
        val detailsUrl = "${TMDB_BASE}/${cleanPath}?append_to_response=external_ids&api_key=$TMDB_API_KEY"

        val response = app.get(detailsUrl, headers = stdHeaders()).text
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
                item.voteAverage?.let { this.score = Score.from10(it.toInt()) }
            }
        } else {
            val episodes = threadSafeListOf<Episode>()
            item.seasons?.amap { season ->
                val seasonNum = season.seasonNumber ?: return@amap
                if (seasonNum == 0) return@amap
                try {
                    val seasonUrl = "${TMDB_BASE}/${type}/${id}/season/${seasonNum}?api_key=$TMDB_API_KEY"
                    val seasonResponse = app.get(seasonUrl, headers = stdHeaders()).text
                    val seasonData = tryParseJson<TmdbSeasonResponse>(seasonResponse)

                    seasonData?.episodes?.forEach { ep ->
                        episodes.add(newEpisode(url) {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = ep.episodeNumber
                            this.data = "$url?season=$seasonNum&episode=${ep.episodeNumber}"
                        })
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            val sortedEpisodes = episodes.sortedWith(
                compareBy<Episode> { it.season }.thenBy { it.episode }
            )
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = overview
                this.year = item.firstAirDate?.substringBefore("-")?.toIntOrNull()
                this.tags = genres
                item.voteAverage?.let { this.score = Score.from10(it.toInt()) }
            }
        }
    }

    // ===== LOAD LINKS =====
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return primeSrcHelper.invokePrimeSrc(
            data = data,
            mainUrl = mainUrl,
            providerName = this.name,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    // ===== DATA CLASSES TMDB =====
    data class TmdbResultsResponse(
        @JsonProperty("results") val results: List<TmdbItem>?,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null
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
    data class TmdbGenreItem(@JsonProperty("name") val name: String?)
    data class TmdbSeasonItem(@JsonProperty("season_number") val seasonNumber: Int?)
    data class TmdbSeasonResponse(@JsonProperty("episodes") val episodes: List<TmdbEpisodeItem>?)
    data class TmdbEpisodeItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?
    )

    companion object {
        private const val TMDB_API_KEY = "d64117f26031a428449f102ced3aba73"
        private const val TMDB_BASE = "https://api.themoviedb.org/3"
    }
}

// ============================================================================
// HELPER: PrimeSrc - WebView-based secretKey resolution
// ============================================================================

class PrimeSrcHelper {

    // Cache per mediaId+type - gak perlu algoritma salt array
    private val secretKeyCache = mutableMapOf<String, String>()

    /**
     * Ambil secretKey dari CloudStream's official WebViewResolver.
     * Real WebView session → Cloudflare detect sebagai legitimate user.
     * Gak ngecrack main.js, gak hardcode per film.
     */
    private suspend fun getSecretKeyViaWebView(mediaId: String, isMovie: Boolean): String? {
        return try {
            val typeParam = if (isMovie) "movie" else "tv"
            val watchUrl = "https://www.rivestream.app/watch?type=$typeParam&id=$mediaId"

            val resolver = WebViewResolver(
                interceptUrl = Regex("""backendfetch.*secretKey=([^&]+)"""),
                additionalUrls = emptyList(),
                timeout = 30_000L
            )

            val (finalRequest, allRequests) = resolver.resolveUsingWebView(
                url = watchUrl,
                referer = "https://www.rivestream.app/"
            )

            finalRequest?.url?.queryParameter("secretKey")
                ?: allRequests.firstNotNullOfOrNull {
                    Regex("""secretKey=([^&]+)""")
                        .find(it.url.toString())?.groupValues?.get(1)
                }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun getSecretKey(mediaId: String, isMovie: Boolean): String {
        val cacheKey = "$mediaId:${if (isMovie) "m" else "t"}"

        // 1. Cache hit
        secretKeyCache[cacheKey]?.let { return it }

        // 2. Known hardcoded (dari intercept-an lo)
        val known = mapOf(
            "1304313" to "MzZmMWZjZjc=",
            "1339713" to "MzZmMWZjZjc=",
            "83533" to "MC00YTgzNDM="
        )
        known[mediaId]?.let {
            secretKeyCache[cacheKey] = it
            return it
        }

        // 3. Fallback: WebView resolver
        val key = getSecretKeyViaWebView(mediaId, isMovie) ?: "rive"
        secretKeyCache[cacheKey] = key
        return key
    }

    suspend fun invokePrimeSrc(
        data: String,
        mainUrl: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?season=")
        val cleanId = cleanData.substringBefore("?").substringAfterLast("/")
        var linksFound = 0

        val standardHeaders = mapOf(
            "Authority" to "www.rivestream.app",
            "Accept" to "application/json",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/watch?type=${if (isMovie) "movie" else "tv"}&id=$cleanId",
            "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not.A/Brand\";v=\"24\"",
            "Sec-Ch-Ua-Mobile" to "?1",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )

        // ===== JALUR 1: Backend RiveStream =====
        try {
            val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"
            val secretKey = getSecretKey(cleanId, isMovie)

            val servicesListUrl = "$mainUrl/api/backendfetch?requestID=VideoProviderServices&secretKey=rive&proxyMode=noProxy"
            val servicesResponse = app.get(servicesListUrl, headers = standardHeaders).text
            val parsedServices = tryParseJson<BackendServicesResponse>(servicesResponse)

            val activeServices = parsedServices?.data?.filter { it != "primevids" }
                ?: listOf("flowcast", "asiacloud", "hindicast", "guru", "ophim")

            val baseApiUrl = "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId"

            activeServices.amap { service ->
                try {
                    val proxyMode = "noProxy"
                    val finalApiUrl = if (isMovie) {
                        "$baseApiUrl&service=$service&secretKey=$secretKey&proxyMode=$proxyMode"
                    } else {
                        val season = cleanData.substringAfter("?season=").substringBefore("&")
                        val episode = cleanData.substringAfter("&episode=")
                        "$baseApiUrl&service=$service&secretKey=$secretKey&proxyMode=$proxyMode&season=$season&episode=$episode"
                    }

                    val response = app.get(finalApiUrl, headers = standardHeaders).text
                    val parsedData = tryParseJson<BackendFetchResponse>(response) ?: return@amap
                    val sources = parsedData.data?.sources ?: return@amap

                    parsedData.data.captions?.forEach { caption ->
                        val captionUrl = caption.file ?: return@forEach
                        val captionLabel = caption.label ?: "External Subtitle"
                        subtitleCallback(newSubtitleFile(lang = captionLabel, url = captionUrl))
                    }

                    for (source in sources) {
                        val streamUrl = source.url ?: continue
                        val qualityName = source.quality?.uppercase() ?: "AUTO"
                        val sourceLabel = source.source ?: "RiveStream"
                        val displayName = "$sourceLabel - $qualityName"

                        if (streamUrl.contains(".m3u8") || source.format?.lowercase() == "hls") {
                            callback(
                                newExtractorLink(
                                    source = providerName,
                                    name = displayName,
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    val isAudioLabel = qualityName.any { it.isLetter() }
                                    this.quality = if (isAudioLabel) Qualities.Unknown.value
                                                   else getQualityFromName(qualityName)
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf("Origin" to mainUrl, "Accept" to "*/*")
                                }
                            )
                            linksFound++
                        } else {
                            val targetReferer = if (service == "flowcast" || service == "hindicast")
                                "https://123movienow.cc/" else "$mainUrl/"
                            val isExtractorFound = loadExtractor(
                                url = streamUrl,
                                referer = targetReferer,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )

                            if (!isExtractorFound && !streamUrl.contains("/e/")) {
                                callback(
                                    newExtractorLink(
                                        source = providerName,
                                        name = displayName,
                                        url = streamUrl
                                    ) {
                                        this.quality = getQualityFromName(qualityName)
                                        this.referer = targetReferer
                                    }
                                )
                                linksFound++
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ===== JALUR 2: PrimeSrc API =====
        try {
            val typeParam = if (isMovie) "movie" else "tv"
            var primeSrcApiUrl = "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=$typeParam"

            if (!isMovie) {
                val season = cleanData.substringAfter("?season=").substringBefore("&")
                val episode = cleanData.substringAfter("&episode=")
                primeSrcApiUrl += "&season=$season&episode=$episode"
            }

            val primeSrcHeaders = mapOf(
                "Referer" to "https://primesrc.me/embed/$typeParam?tmdb=$cleanId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            )

            val primeSrcResponse = app.get(primeSrcApiUrl, headers = primeSrcHeaders).text
            val parsedPrimeSrc = tryParseJson<PrimeSrcServerResponse>(primeSrcResponse)

            val sortedServers = parsedPrimeSrc?.servers?.sortedByDescending { server ->
                val name = server.name?.lowercase() ?: ""
                name.contains("streamtape") || name.contains("voe") || name.contains("mixdrop")
            }

            sortedServers?.amap { server ->
                val serverName = server.name?.lowercase() ?: return@amap
                val serverKey = server.key ?: return@amap

                val embedUrl = when {
                    serverName.contains("streamtape") -> "https://tpead.net/e/$serverKey"
                    serverName.contains("voe") -> "https://voe.un/e/$serverKey"
                    serverName.contains("streamwish") -> "https://streamwish.to/e/$serverKey"
                    serverName.contains("filemoon") -> "https://filemoon.sx/e/$serverKey"
                    serverName.contains("dood") -> "https://dood.li/e/$serverKey"
                    serverName.contains("mixdrop") -> "https://mixdrop.co/e/$serverKey"
                    serverName.contains("filelions") -> "https://filelions.to/e/$serverKey"
                    serverName.contains("streamruby") -> "https://streamruby.com/e/$serverKey"
                    serverName.contains("vidmoly") -> "https://vidmoly.me/e/$serverKey"
                    serverName.contains("vidnest") -> "https://vidnest.xyz/e/$serverKey"
                    serverName.contains("vidara") -> "https://vidara.org/e/$serverKey"
                    serverName.contains("savefiles") -> "https://savefiles.inc/e/$serverKey"
                    else -> null
                }

                if (embedUrl != null) {
                    try {
                        // Pakai built-in extractor CloudStream lebih dulu
                        val isExtractorFound = loadExtractor(
                            url = embedUrl,
                            referer = "https://primesrc.me/",
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                        if (isExtractorFound) {
                            linksFound++
                        } else if (embedUrl.contains("tpead.net")) {
                            // Fallback custom extractor
                            TpeadExtractor().getUrl(embedUrl, "https://primesrc.me/", subtitleCallback, callback)
                            linksFound++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return linksFound > 0
    }
}

// ============================================================================
// DATA CLASSES MODELS
// ============================================================================

data class BackendServicesResponse(@JsonProperty("data") val data: List<String>?)
data class BackendFetchResponse(@JsonProperty("data") val data: BackendData?)
data class BackendData(
    @JsonProperty("sources") val sources: List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)
data class BackendSource(
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("format") val format: String?
)
data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file") val file: String?
)
data class PrimeSrcServerResponse(@JsonProperty("servers") val servers: List<PrimeSrcServer>?)
data class PrimeSrcServer(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key") val key: String?
)

// ============================================================================
// EXTRACTOR: Tpead (Streamtape alias) - Fallback only
// ============================================================================

class TpeadExtractor : ExtractorApi() {
    override val name = "Streamtape"
    override val mainUrl = "https://tpead.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer).text

            val baseRegex = Regex("""(?i)(?:document\.getElementById\('[^']+'\)\.)?innerHTML\s*=\s*['"](//[^'"]+)['"]""")
            val baseMatch = baseRegex.find(response) ?: return

            val baseUrl = baseMatch.groupValues[1]
            val startIndex = baseMatch.range.last
            val searchArea = response.substring(startIndex, (startIndex + 200).coerceAtMost(response.length))

            val tokenRegex = Regex("""\+\s*\(['"]([^'"]+)['"]\)\.substring\((\d+)\)""")
            val altTokenRegex = Regex("""\+\s*['"]([^'"]+)['"]""")

            var token = ""
            val tokenMatch = tokenRegex.find(searchArea)
            if (tokenMatch != null) {
                val rawToken = tokenMatch.groupValues[1]
                val cutIdx = tokenMatch.groupValues[2].toIntOrNull() ?: 0
                if (cutIdx < rawToken.length) {
                    token = rawToken.substring(cutIdx)
                }
            } else {
                val altMatch = altTokenRegex.find(searchArea)
                if (altMatch != null) {
                    token = altMatch.groupValues[1]
                }
            }

            if (token.isNotEmpty()) {
                val finalUrl = "https:$baseUrl$token&dl=1"
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Streamtape (Tpead Bypass)",
                        url = finalUrl
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
