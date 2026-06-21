package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import kotlinx.coroutines.delay
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * PrimeSrc Helper - bridges RiveStream provider dengan PrimeSrc embed service
 *
 * Flow:
 *  1. /api/v1/s?tmdb=X&season=Y&episode=Z&type=tv  → server list (NO Turnstile)
 *  2. /api/v1/l?key=<key>&token=<turnstile>        → iframe URL (Turnstile-protected)
 *  3. iframe URL on streamcasthub.store            → real stream
 *
 * Server domains discovered (dari network capture):
 *  - voe.sx, filelions.to, streamtape.com, dood.li, luluvdoo.com
 *  - streamplay.to, vidnest.io, filemoon.io, streamwish.to
 *  - vidmoly.to, mixdrop.ag, upzur.com, savefiles.com
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
            logError("[$providerName] Embed URL: $embedUrl")

            // Parse embed URL untuk extract params
            val params = parseEmbedParams(embedUrl) ?: return false
            logError("[$providerName] Parsed params: $params")

            // Step 1: Get server list (no Turnstile needed)
            val servers = fetchServerList(params) ?: return false
            logError("[$providerName] Got ${servers.size} servers from /api/v1/s")

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
                    logError("[$providerName] Got iframe for ${server.name}: ${iframeUrl.take(80)}")
                    iframeUrls += server.name to iframeUrl
                }
            }

            if (iframeUrls.isEmpty()) {
                logError("[$providerName] No iframe URLs resolved via API - will try embed mode")
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
    // FALLBACK API: invokeEmbedMode
    // ========================================================
    /**
     * Strategi 2: Embed Mode (WebView-based)
     * - Load embed URL di WebView
     * - Tunggu Turnstile auto-solve (CloudStream WebView handles this)
     * - Extract iframe URL dari rendered DOM
     * - Pass ke ExtractorApi
     */
    suspend fun invokeEmbedMode(
        data: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedUrl = buildEmbedUrl(mainUrl, data) ?: return false
            logError("[PrimeSrc.Embed] Loading: $embedUrl")

            logError("[PrimeSrc.Embed] WebView implementation TODO")
            false
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

        // Menggunakan getExtractorApiFromName dan getSafeUrl untuk penanganan ekstraksi yang aman sesuai arsitektur baru
        try {
            getExtractorApiFromName(extractorKey).getSafeUrl(
                url = iframeUrl,
                referer = null,
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
