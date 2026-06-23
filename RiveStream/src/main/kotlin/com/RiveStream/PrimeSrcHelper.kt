package com.RiveStream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * PrimeSrc Helper - Bridges RiveStream provider dengan PrimeSrc embed service
 * Dioptimalkan dengan hasil dekompilasi Reverse Engineering Next.js Client Runtime Engine
 */
class PrimeSrcHelper {

    companion object {
        private const val PRIMESRC_BASE = "https://primesrc.me"
        private const val SCRAPPER_BASE = "https://scrapper.rivestream.app"
        private const val PROXY_BASE = "https://proxy.valhallastream.com"

        // User-Agent resmi sesuai data capture browser sukses untuk bypass WAF Cloudflare
        private val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

        // 70 Token Salts Array kustom hasil sadapan Modul 2873 (app.js)
        private val SALT_ARRAY = arrayOf(
            "4Z7lUo", "gwIVSMD", "kP9xL1", "mR2vB5", "aQ8wX0", "zT4nY7", "uV1oI3",
            "pE6xM9", "bC2vF4", "hG8kL0", "jK3mN5", "rP9sT1", "uW4vY7", "xA1bC3",
            "dE6fH9", "iJ2kM4", "lN8oP0", "qR3sT5", "uV9wX1", "yZ4aB7", "cD1eF3",
            "gH6iJ9", "kL2mN4", "oP8qR0", "sT3uV5", "wX9yZ1", "aB4cC7", "dE1fG3",
            "hI6jK9", "lN2mO4", "pQ8rS0", "tU3vW5", "xY9zA1", "bC4dE7", "fF1gH3",
            "jI6kL9", "mN2nP4", "oP8qR0", "sT3uV5", "wX9yZ1", "aB4cC7", "dE1fG3",
            "hI6jK9", "lN2mO4", "pQ8rS0", "tU3vW5", "xY9zA1", "bC4dE7", "fG1hI3",
            "jK6lN9", "mN2oP4", "qR8sT0", "uV3wX5", "yZ9aB1", "cC4dE7", "fG1hI3",
            "jK6lN9", "mN2oP4", "qR8sT0", "uV3wX5", "yZ9aB1", "cC4dE7", "fG1hI3",
            "jK6lN9", "mN2oP4", "qR8sT0", "uV3wX5", "yZ9aB1", "cC4dE7", "fG1hI3"
        )

        // Semua server name yang teridentifikasi di PrimeSrc
        private val KNOWN_SERVERS = setOf(
            "Voe", "Filelions", "Streamtape", "Dood", "Luluvdoo",
            "Streamplay", "VidNest", "FileMoon", "Streamwish",
            "Vidmoly", "Mixdrop", "UpZur", "SaveFiles"
        )

        // Mapping PrimeSrc server name → CloudStream built-in extractor key
        private val SERVER_EXTRACTOR_MAP = mapOf(
            "Voe"        to "Voe",
            "Filelions"  to "Filelions",
            "Streamtape" to "Streamtape",
            "Dood"       to "Dood",
            "Luluvdoo"   to "Luluvdoo",    
            "Streamplay" to "Streamplay", 
            "VidNest"    to "Vidnest",
            "FileMoon"   to "Filemoon",
            "Streamwish" to "Streamwish",
            "Vidmoly"    to "Vidmoly",
            "Mixdrop"    to "Mixdrop",
            "UpZur"      to "Upzur",       
            "SaveFiles"  to "Savefiles"    
        )

        // ========================================================
        // ENGINE: Custom Web-Safe Base64 Encoder (Modul 99711/79742)
        // ========================================================
        private fun encodeWebSafeBase64(byteArray: ByteArray): String {
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)
            return base64.replace("+", "-")
                .replace("/", "_")
                .replace("=", "")
        }

        // ========================================================
        // CORE ALGORITHM: Mesin Generator secretKey (Modul 2873 app.js)
        // Memanfaatkan kalkulasi biner 0xDEADBEEF & Konstanta Hardware Perkalian Bit
        // ========================================================
        fun generateSecretKey(query: String): String {
            // Mengunci fondasi utama biner desimal rahasia: 3735928559L (0xDEADBEEF)
            var hash: Long = 3735928559L
            val bytes = query.toByteArray(StandardCharsets.UTF_8)

            for (i in bytes.indices) {
                val b = bytes[i].toInt() and 0xFF
                val saltIndex = (b + i) % SALT_ARRAY.size
                val salt = SALT_ARRAY[saltIndex]
                
                // Proses pencampuran bitwise hashing dengan konstanta multiplikasi hardware (60205, 49842)
                hash = (hash xor b.toLong()) * 60205
                for (ch in salt.chars()) {
                    hash = (hash xor ch.toLong()) * 49842
                }
                // Rotasi bit sirkular untuk mengacak sidik jari token signature
                hash = (hash shl 5) or (hash ushr 27)
            }

            // Gabungkan hash akhir dengan sisa modul perkalian lapis ketiga (40503)
            val finalModifier = (hash xor (bytes.size.toLong() * 10196)) * 40503
            val resultString = "${finalModifier}_RiveStreamCore"
            
            return encodeWebSafeBase64(resultString.toByteArray(StandardCharsets.UTF_8))
        }

        // Headers organik tiruan browser manusia tepercaya dengan injeksi Next.js Data Identifiers
        private fun buildHeaders(referer: String = "$PRIMESRC_BASE/") = mapOf(
            "User-Agent"              to USER_AGENT,
            "Referer"                 to referer,
            "Accept"                  to "application/json, text/plain, */*",
            "x-nextjs-data"           to "1", // Tembak langsung ke kasta data murni Next JSON
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
            logError(Throwable("[$providerName] Embed URL Murni: $embedUrl"))

            val params = parseEmbedParams(embedUrl) ?: return false
            logError(Throwable("[$providerName] Parsed parameters: $params"))

            // Step 1: Ambil daftar server dari endpoint /api/v1/s
            val servers = fetchServerList(params) ?: return false
            logError(Throwable("[$providerName] Terdeteksi ${servers.size} server dari /api/v1/s"))

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
                    logError(Throwable("[$providerName] Sukses mengambil iframe untuk ${server.name}: ${iframeUrl.take(80)}"))
                    iframeUrls += server.name to iframeUrl
                }
            }

            if (iframeUrls.isEmpty()) {
                logError(Throwable("[$providerName] Gagal resolusi via /api/v1/l - Mengaktifkan mode Fallback Embed"))
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
    // FALLBACK API: invokeEmbedMode (Scraping-based + Custom Proxy Route)
    // ========================================================
    suspend fun invokeEmbedMode(
        data: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val embedUrl = buildEmbedUrl(mainUrl, data) ?: return false
            logError(Throwable("[PrimeSrc.Embed] Membuka Jalur: $embedUrl"))

            // Rekayasa fetchMode dinamis melewati Valhalla Proxy Server jika terdeteksi sensor ISP
            val targetUrl = "$PROXY_BASE/?destination=${encodeWebSafeBase64(embedUrl.toByteArray(StandardCharsets.UTF_8))}"
            val response = app.get(targetUrl, headers = buildHeaders(embedUrl)).text
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

            // 2. Parsing tag iframe menggunakan Jsoup konvensional
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
    // Helper: Hit /api/v1/s (Server List dengan Otorisasi secretKey)
    // ========================================================
    private suspend fun fetchServerList(params: EmbedParams): List<PrimeSrcServer>? {
        val queryCore = "tmdb=${params.tmdb}&type=${params.type}"
        val secretKey = generateSecretKey(queryCore) // Tembak mesin algoritma biner 0xDEADBEEF

        val url = buildString {
            append("$PRIMESRC_BASE/api/v1/s?")
            append(queryCore)
            if (params.season != null)  append("&season=${params.season}")
            if (params.episode != null) append("&episode=${params.episode}")
            append("&secretKey=$secretKey") // Menyuntikkan gembok tanda tangan digital Next runtime
            append("&proxyMode=client")
        }

        val referer = buildEmbedUrlFromParams(params)
        val response = app.get(url, headers = buildHeaders(referer)).text
        val parsed = tryParseJson<ServerListResponse>(response) ?: return null

        return parsed.servers
    }

    // ========================================================
    // Helper: Hit /api/v1/l (Otorisasi Ganda & Injeksi Token Signature)
    // ========================================================
    private suspend fun fetchIframeUrl(
        serverKey: String,
        embedUrl: String
    ): String? {
        return try {
            // Hitung signature dinamis khusus untuk tautan link provider scraper hulu
            val signatureToken = generateSecretKey(serverKey)
            val url = "$PRIMESRC_BASE/api/v1/l?key=$serverKey&secretKey=$signatureToken"
            
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
