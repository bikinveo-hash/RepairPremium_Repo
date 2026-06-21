package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * PrimeSrc Helper - bridges RiveStream provider dengan PrimeSrc embed service
 *
 * Flow:
 * 1. /api/v1/s?tmdb=X&season=Y&episode=Z&type=tv  → server list (NO Turnstile)
 * 2. /api/v1/l?key=<key>&token=<turnstile>        → iframe URL (Turnstile-protected)
 * 3. iframe URL on streamcasthub.store            → real stream
 *
 * Server domains discovered (dari network capture):
 * - voe.sx, filelions.to, streamtape.com, dood.li, luluvdoo.com
 * - streamplay.to, vidnest.io, filemoon.io, streamwish.to
 * - vidmoly.to, mixdrop.ag, upzur.com, savefiles.com
 */
class PrimeSrcHelper {

    companion object {
        private const val PRIMESRC_BASE = "https://primesrc.me"

        // User-Agent yang dipakai untuk semua request PrimeSrc
        private val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

        // Semua server name yang pernah muncul di response PrimeSrc
        private val KNOWN_SERVERS = setOf(
            "Voe", "Filelions", "Streamtape", "Dood", "Luluvdoo",
            "Streamplay", "VidNest", "FileMoon", "Streamwish",
            "Vidmoly", "Mixdrop", "UpZur", "SaveFiles"
        )

        // Mapping PrimeSrc server name → CloudStream built-in extractor key
        // CloudStream 3.x udah punya extractors built-in untuk ini
        private val SERVER_EXTRACTOR_MAP = mapOf(
            "Voe"       to "Voe",
            "Filelions" to "Filelions",
            "Streamtape" to "Streamtape",
            "Dood"      to "Dood",
            "Luluvdoo"  to "Luluvdoo",    // might not exist - fallback to WebView
            "Streamplay" to "Streamplay", // might not exist - fallback to WebView
            "VidNest"   to "Vidnest",
            "FileMoon"  to "Filemoon",
            "Streamwish" to "Streamwish",
            "Vidmoly"   to "Vidmoly",
            "Mixdrop"   to "Mixdrop",
            "UpZur"     to "Upzur",       // might not exist
            "SaveFiles" to "Savefiles"    // might not exist
        )

        // Header wajib untuk PrimeSrc API
        private fun buildHeaders(referer: String = "$PRIMESRC_BASE/") = mapOf(
            "User-Agent"      to USER_AGENT,
            "Referer"         to referer,
            "Accept"          to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin"          to PRIMESRC_BASE
        )

        // Parse URL RiveStream format → PrimeSrc embed URL
        // Input:  https://www.rivestream.app/tv/219971?season=1&episode=1
        // Output: https://primesrc.me/embed/tv?tmdb=219971&season=1&episode=1
        private fun buildEmbedUrl(mainUrl: String, data: String): String? {
            val path = data.removePrefix("$mainUrl/").takeIf { it.isNotEmpty() } ?: return null
            val type = path.substringBefore("/")   // "tv" or "movie"
            val id   = path.substringAfter("/").substringBefore("?")  // tmdb id

            val params = mutableListOf<String>()
            if (type == "tv") {
                val query = data.substringAfter("?", "")
                val qp = query.split("&").associate {
                    it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
                }
                qp["season"]?.let  { params += "season=$it" }
                qp["episode"]?.let { params += "episode=$it" }
            }

            val qs = if (params.isNotEmpty()) "&" + params.joinToString("&") else ""
            return "$PRIMESRC_BASE/embed/$type?tmdb=$id$qs"
        }

        // Mengubah subdomain dinamis streamcasthub ke target rrr secara aman
        private fun fixStreamCastHubUrl(url: String): String {
            return if (url.contains("streamcasthub.store")) {
                url.replace(Regex("https://[^/]+\\.streamcasthub\\.store"), "https://rrr.streamcasthub.store")
            } else {
                url
            }
        }
    }

    // ========================================================
    // MAIN API: invokePrimeSrc
    // ========================================================
    /**
     * Strategi 1: Direct API access
     * - Hit /api/v1/s untuk server list (no Turnstile)
     * - Untuk tiap server, coba /api/v1/l (akan kena Turnstile)
     * - Fallback ke WebView extraction kalau /api/v1/l gagal
     */
    suspend fun invokePrimeSrc(
        data: String,
        mainUrl: String,
        providerName: String,
        apiKey: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedUrl = buildEmbedUrl(mainUrl, data) ?: return false
            logError(Throwable("[$providerName] Embed URL: $embedUrl"))

            // Parse embed URL untuk extract params
            val params = parseEmbedParams(embedUrl) ?: return false
            logError(Throwable("[$providerName] Parsed params: $params"))

            // Step 1: Get server list (no Turnstile needed)
            val servers = fetchServerList(params) ?: return false
            logError(Throwable("[$providerName] Got ${servers.size} servers from /api/v1/s"))

            if (servers.isEmpty()) return false

            // Step 2: For each server, try to get iframe URL
            val iframeUrls = mutableListOf<Pair<String, String>>()  // (server_name, iframe_url)

            for (server in servers) {
                if (server.name !in KNOWN_SERVERS) continue

                val iframeUrl = fetchIframeUrl(
                    serverKey = server.key,
                    embedUrl = embedUrl
                )

                if (iframeUrl != null) {
                    logError(Throwable("[$providerName] Got iframe for ${server.name}: ${iframeUrl.take(80)}"))
                    iframeUrls += server.name to iframeUrl
                }
            }

            if (iframeUrls.isEmpty()) {
                logError(Throwable("[$providerName] No iframe URLs resolved via API - will try embed mode"))
                return false
            }

            // Step 3: Pass iframe URLs to ExtractorApi
            for ((serverName, iframeUrl) in iframeUrls) {
                invokeExtractor(
                    serverName = serverName,
                    iframeUrl  = iframeUrl,
                    subtitleCallback = subtitleCallback,
                    callback   = callback
                )
            }

            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    // ========================================================
    // FALLBACK API: invokeEmbedMode (Scraping-based)
    // ========================================================
    /**
     * Strategi 2: Embed Mode (Standard Scraper)
     * - Menggunakan pemanggilan GET reguler untuk mengambil kode HTML halaman embed
     * - Mengekstrak tautan streamcasthub.store langsung menggunakan pencarian berbasis teks/regex dan Jsoup
     * - Mengubah pola subdomain asal menjadi target rrr.streamcasthub.store
     */
    suspend fun invokeEmbedMode(
        data: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedUrl = buildEmbedUrl(mainUrl, data) ?: return false
            logError(Throwable("[PrimeSrc.Embed] Loading: $embedUrl"))

            val response = app.get(embedUrl, headers = buildHeaders()).text
            var isExtractorInvoked = false

            // 1. Ambil URL streamcasthub langsung dari dokumen teks menggunakan Regex matcher
            val streamCastRegex = Regex("""https://[a-zA-Z0-9.-]+\.streamcasthub\.store/[^\s"'`>]+""")
            val matches = streamCastRegex.findAll(response)
            for (match in matches) {
                val url = match.value
                val fixedUrl = fixStreamCastHubUrl(url)
                if (loadExtractor(fixedUrl, embedUrl, subtitleCallback, callback)) {
                    isExtractorInvoked = true
                }
            }

            // 2. Parsing tag iframe menggunakan Jsoup secara konvensional (untuk mendukung suspend call)
            val document = org.jsoup.Jsoup.parse(response)
            for (iframe in document.select("iframe")) {
                var src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    if (src.startsWith("//")) src = "https:$src"
                    val fixedSrc = fixStreamCastHubUrl(src)
                    if (loadExtractor(fixedSrc, embedUrl, subtitleCallback, callback)) {
                        isExtractorInvoked = true
                    }
                }
            }

            isExtractorInvoked
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    // ========================================================
    // Helper: Hit /api/v1/s (server list)
    // ========================================================
    private suspend fun fetchServerList(params: EmbedParams): List<PrimeSrcServer>? {
        val url = buildString {
            append("$PRIMESRC_BASE/api/v1/s?")
            append("tmdb=${params.tmdb}")
            append("&type=${params.type}")
            if (params.season != null)  append("&season=${params.season}")
            if (params.episode != null) append("&episode=${params.episode}")
        }

        val referer = buildEmbedUrlFromParams(params)
        val response = app.get(url, headers = buildHeaders(referer)).text
        val parsed = tryParseJson<ServerListResponse>(response) ?: return null

        return parsed.servers
    }

    // ========================================================
    // Helper: Hit /api/v1/l (iframe URL - Turnstile protected)
    // ========================================================
    private suspend fun fetchIframeUrl(
        serverKey: String,
        embedUrl: String
    ): String? {
        return try {
            val url = "$PRIMESRC_BASE/api/v1/l?key=$serverKey"
            val response = app.get(
                url,
                headers = buildHeaders(embedUrl) + mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            val parsed = tryParseJson<LinkResponse>(response)
            parsed?.link
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // ========================================================
    // Helper: Pass iframe URL ke CloudStream extractor
    // ========================================================
    private suspend fun invokeExtractor(
        serverName: String,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractorKey = SERVER_EXTRACTOR_MAP[serverName] ?: return
        val fixedUrl = fixStreamCastHubUrl(iframeUrl)

        try {
            getExtractorApiFromName(extractorKey).getSafeUrl(
                url = fixedUrl,
                referer = "$PRIMESRC_BASE/",
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } catch (e: Exception) {
            logError(e)
        }
    }

    // ========================================================
    // Utility: Parse embed URL parameters
    // ========================================================
    private data class EmbedParams(
        val type: String,        // "tv" or "movie"
        val tmdb: Int,
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun parseEmbedParams(embedUrl: String): EmbedParams? {
        val typePart = embedUrl.substringAfter("/embed/").substringBefore("?")
        val query = embedUrl.substringAfter("?", "")

        val qp = query.split("&").associate {
            val key = it.substringBefore("=")
            val value = it.substringAfter("=")
            key to value
        }

        val tmdb = qp["tmdb"]?.toIntOrNull() ?: return null

        return EmbedParams(
            type    = typePart,
            tmdb    = tmdb,
            season  = qp["season"]?.toIntOrNull(),
            episode = qp["episode"]?.toIntOrNull()
        )
    }

    private fun buildEmbedUrlFromParams(params: EmbedParams): String {
        return buildString {
            append("$PRIMESRC_BASE/embed/${params.type}?tmdb=${params.tmdb}")
            if (params.season != null)  append("&season=${params.season}")
            if (params.episode != null) append("&episode=${params.episode}")
        }
    }

    // ========================================================
    // DATA CLASSES - JSON response models
    // ========================================================

    private data class ServerListResponse(
        @JsonProperty("servers") val servers: List<PrimeSrcServer>?,
        @JsonProperty("info")    val info: PrimeSrcInfo?
    )

    private data class PrimeSrcServer(
        @JsonProperty("quality")        val quality: String?,
        @JsonProperty("name")           val name: String,
        @JsonProperty("key")            val key: String,
        @JsonProperty("file_size")      val fileSize: String?,
        @JsonProperty("file_name")      val fileName: String?,
        @JsonProperty("audio_type")     val audioType: String?,
        @JsonProperty("audio_language") val audioLanguage: String?
    )

    private data class PrimeSrcInfo(
        @JsonProperty("type")          val type: String?,
        @JsonProperty("title")         val title: String?,
        @JsonProperty("imdb_id")       val imdbId: String?,
        @JsonProperty("release_date")  val releaseDate: String?,
        @JsonProperty("status")        val status: String?,
        @JsonProperty("description")   val description: String?,
        @JsonProperty("tmdb_image")    val tmdbImage: String?,
        @JsonProperty("tmdb_backdrop") val tmdbBackdrop: String?,
        @JsonProperty("episode")       val episode: PrimeSrcEpisode?,
        @JsonProperty("tmdb_id")       val tmdbIdConfusing: String?,
        @JsonProperty("tvmaze_id")     val tvmazeId: Any?
    )

    private data class PrimeSrcEpisode(
        @JsonProperty("title")         val title: String?,
        @JsonProperty("season")        val season: Int?,
        @JsonProperty("episode")       val episode: Int?,
        @JsonProperty("release_date")  val releaseDate: String?,
        @JsonProperty("description")   val description: String?
    )

    private data class LinkResponse(
        @JsonProperty("link")  val link: String?,
        @JsonProperty("error") val error: String?
    )
}

// ============================================================
// VERIFIED ANALYSIS NOTES (post-movie-data)
// ============================================================
//
// SERIES (e.g., The Agency S01E01, House of the Dragon S01E01):
//   URL: /embed/tv?tmdb=X&season=Y&episode=Z
//   API: /api/v1/s?tmdb=X&season=Y&episode=Z&type=tv
//   info keys: type, tvmaze_id, tmdb_image, tmdb_id (IMDB!), tmdb_backdrop,
//              title, status, release_date, imdb_id, episode{...}, description
//   Server count: 11-30+ (multiple per name with different qualities)
//   Server names: 13+ (Voe, Filelions, Streamtape, Dood, Luluvdoo,
//                  Streamplay, VidNest, FileMoon, Streamwish, Vidmoly,
//                  Mixdrop, UpZur, SaveFiles)
//
// MOVIE (e.g., The Conjuring 2013):
//   URL: /embed/movie?tmdb=X
//   API: /api/v1/s?tmdb=X&type=movie
//   info keys: type, tvmaze_id (null), tmdb_image, tmdb_id (IMDB!),
//              tmdb_backdrop, title, status, release_date,
//              imdb_id, description
//              ❌ NO "episode" key
//   Server count: ~9 typical (less than series, varies per movie)
//   Server names: subset of above (The Conjuring only had 5:
//                  Dood, Filelions, Luluvdoo, Mixdrop, Voe)
//
// KEY DIFFERENCES:
//   1. Series has info.episode; Movie doesn't (entire key absent)
//   2. Both have info.tmdb_image and info.tmdb_backdrop (movie-specific use)
//   3. info.tmdb_id field is misleadingly named (actually IMDB ID)
//   4. info.tvmaze_id is always null for movies
//   5. Series typically has 2-3x more servers than movies
//
// PrimeSrcHelper sudah handle ini via:
//   - EmbedParams.season/episode nullable (movie skips them)
//   - PrimeSrcInfo.episode nullable (missing field OK)
//   - buildEmbedUrl detects type dari path
//   - SERVER_EXTRACTOR_MAP covers semua 13 server names
