package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLDecoder

/**
 * PrimeSrc Helper - bridges RiveStream provider dengan PrimeSrc embed service
 */
class PrimeSrcHelper {

    companion object {
        private const val PRIMESRC_BASE = "https://primesrc.me"

        // User-Agent resmi sesuai data capture browser sukses Anda
        private val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

        // Semua server name yang teridentifikasi di PrimeSrc
        private val KNOWN_SERVERS = setOf(
            "Voe", "Filelions", "Streamtape", "Dood", "Luluvdoo",
            "Streamplay", "VidNest", "FileMoon", "Streamwish",
            "Vidmoly", "Mixdrop", "UpZur", "SaveFiles"
        )

        // Mapping PrimeSrc server name → CloudStream built-in extractor key
        private val SERVER_EXTRACTOR_MAP = mapOf(
            "Voe"       to "Voe",
            "Filelions" to "Filelions",
            "Streamtape" to "Streamtape",
            "Dood"      to "Dood",
            "Luluvdoo"  to "Luluvdoo",    
            "Streamplay" to "Streamplay", 
            "VidNest"   to "Vidnest",
            "FileMoon"  to "Filemoon",
            "Streamwish" to "Streamwish",
            "Vidmoly"   to "Vidmoly",
            "Mixdrop"   to "Mixdrop",
            "UpZur"     to "Upzur",       
            "SaveFiles" to "Savefiles"    
        )

        // Headers organik tiruan browser manusia untuk bypass WAF Cloudflare sesuai data capture Anda
        private fun buildHeaders(referer: String = "$PRIMESRC_BASE/") = mapOf(
            "User-Agent"              to USER_AGENT,
            "Referer"                 to referer,
            "Accept"                  to "*/*",
            "sec-ch-ua"               to "\"Chromium\";v=\"139\", \"Not;A=Brand\";v=\"99\"",
            "sec-ch-ua-mobile"        to "?1",
            "sec-ch-ua-platform"      to "\"Android\"",
            "sec-fetch-site"          to "same-origin",
            "sec-fetch-mode"          to "cors",
            "sec-fetch-dest"          to "empty",
            "Accept-Language"         to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin"                  to PRIMESRC_BASE
        )

        // Parse URL RiveStream format → PrimeSrc embed URL
        private fun buildEmbedUrl(mainUrl: String, data: String): String? {
            val path = data.removePrefix("$mainUrl/").takeIf { it.isNotEmpty() } ?: return null
            val type = path.substringBefore("/")   
            val id   = path.substringAfter("/").substringBefore("?")  

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

        // Penanganan restrukturisasi subdomain streamcasthub secara aman
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

            val params = parseEmbedParams(embedUrl) ?: return false
            logError(Throwable("[$providerName] Parsed params: $params"))

            // Step 1: Ambil daftar server dari endpoint /api/v1/s
            val servers = fetchServerList(params) ?: return false
            logError(Throwable("[$providerName] Got ${servers.size} servers from /api/v1/s"))

            if (servers.isEmpty()) return false

            val iframeUrls = mutableListOf<Pair<String, String>>()  

            // Step 2: Ambil URL iframe untuk setiap server via /api/v1/l
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
                logError(Throwable("[$providerName] No iframe URLs resolved via /api/v1/l - will try embed mode"))
                return false
            }

            // Step 3: Kirim link hasil resolusi ke extractor bawaan
            for ((serverName, iframeUrl) in iframeUrls) {
                invokeExtractor(
                    serverName = serverName,
                    iframeUrl  = iframeUrl,
                    embedUrl   = embedUrl,
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

            // 1. Ekstraksi langsung via pencarian teks Regex link streamcasthub
            val streamCastRegex = Regex("""https://[a-zA-Z0-9.-]+\.streamcasthub\.store/[^\s"'`>]+""")
            val matches = streamCastRegex.findAll(response)
            for (match in matches) {
                val url = match.value
                val fixedUrl = fixStreamCastHubUrl(url)
                if (loadExtractor(fixedUrl, subtitleCallback, callback)) {
                    isExtractorInvoked = true
                }
            }

            // 2. Parsing tag iframe menggunakan Jsoup konvensional (aman dari eror konteks suspend)
            val document = org.jsoup.Jsoup.parse(response)
            for (iframe in document.select("iframe")) {
                var src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    if (src.startsWith("//")) src = "https:$src"
                    val fixedSrc = fixStreamCastHubUrl(src)
                    if (loadExtractor(fixedSrc, subtitleCallback, callback)) {
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
    // Helper: Hit /api/v1/l (Menerapkan Hasil Analisis Capture Anda)
    // ========================================================
    private suspend fun fetchIframeUrl(
        serverKey: String,
        embedUrl: String
    ): String? {
        return try {
            val url = "$PRIMESRC_BASE/api/v1/l?key=$serverKey"
            
            // Memanggil endpoint utama dengan kumpulan header penyamaran kustom
            val response = app.get(
                url,
                headers = buildHeaders(embedUrl)
            ).text

            val parsed = tryParseJson<LinkResponse>(response)
            parsed?.link
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // ========================================================
    // Helper: Pass iframe URL ke CloudStream built-in extractor
    // ========================================================
    private suspend fun invokeExtractor(
        serverName: String,
        iframeUrl: String,
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extractorKey = SERVER_EXTRACTOR_MAP[serverName] ?: return
        val fixedUrl = fixStreamCastHubUrl(iframeUrl)

        try {
            // Menggunakan fungsi getSafeUrl versi non-deprecated dari ExtractorApi.kt
            getExtractorApiFromName(extractorKey).getSafeUrl(
                url = fixedUrl,
                referer = embedUrl,
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
        val type: String,        
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
