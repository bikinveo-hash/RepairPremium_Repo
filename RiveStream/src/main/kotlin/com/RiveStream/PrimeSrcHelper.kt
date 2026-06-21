package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLDecoder
import java.util.Collections

/**
 * PrimeSrc Helper - bridges RiveStream provider dengan PrimeSrc embed service
 */
class PrimeSrcHelper {

    companion object {
        private const val PRIMESRC_BASE = "https://primesrc.me"

        private val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

        private val KNOWN_SERVERS = setOf(
            "Voe", "Filelions", "Streamtape", "Dood", "Luluvdoo",
            "Streamplay", "VidNest", "FileMoon", "Streamwish",
            "Vidmoly", "Mixdrop", "UpZur", "SaveFiles"
        )

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

        private fun buildHeaders(referer: String = "$PRIMESRC_BASE/") = mapOf(
            "User-Agent"      to USER_AGENT,
            "Referer"         to referer,
            "Accept"          to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin"          to PRIMESRC_BASE
        )

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

        // Helper untuk memformat subdomain streamcasthub.store agar sesuai dengan target player rrr
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

            val servers = fetchServerList(params) ?: return false
            logError(Throwable("[$providerName] Got ${servers.size} servers from /api/v1/s"))

            if (servers.isEmpty()) return false

            val iframeUrls = mutableListOf<Pair<String, String>>()  

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
    // FALLBACK API: invokeEmbedMode (WebView-based)
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

            var isExtractorInvoked = false
            val interceptedUrls = Collections.synchronizedList(mutableListOf<String>())

            // Menggunakan WebviewResolver untuk memecahkan Turnstile secara otomatis
            val resolver = WebviewResolver { request ->
                val requestUrl = request.url.toString()
                if (requestUrl.contains("api/v1/l") || requestUrl.contains("streamcasthub.store")) {
                    interceptedUrls.add(requestUrl)
                }
                true 
            }

            // Membuka halaman embed di latar belakang untuk memicu pemecahan Turnstile
            val response = app.get(embedUrl, interceptor = resolver)
            val html = response.text

            // Strategi 1: Ekstraksi dari URL pemuter yang berhasil dicegat oleh Webview Interceptor
            val uniqueUrls = interceptedUrls.toList().distinct()
            for (url in uniqueUrls) {
                val fixedUrl = fixStreamCastHubUrl(url)
                if (fixedUrl.contains("api/v1/l?key=")) {
                    val key = fixedUrl.substringAfter("key=").substringBefore("&")
                    val iframeSrc = fetchIframeUrl(key, embedUrl)
                    if (iframeSrc != null) {
                        val fixedIframe = fixStreamCastHubUrl(iframeSrc)
                        if (loadExtractor(fixedIframe, subtitleCallback, callback)) {
                            isExtractorInvoked = true
                        }
                    }
                } else if (fixedUrl.contains("streamcasthub.store")) {
                    if (loadExtractor(fixedUrl, subtitleCallback, callback)) {
                        isExtractorInvoked = true
                    }
                }
            }

            // Strategi 2: Jika pencegatan jaringan luput, parsing rendered DOM HTML untuk mencari iframe
            val document = org.jsoup.Jsoup.parse(html)
            document.select("iframe").forEach { iframe ->
                var src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    if (src.startsWith("//")) src = "https:$src"
                    val fixedSrc = fixStreamCastHubUrl(src)
                    if (loadExtractor(fixedSrc, subtitleCallback, callback)) {
                        isExtractorInvoked = true
                    }
                }
            }

            // Strategi 3: Setelah WebView sukses dimuat, cookie sesi (cf_clearance) otomatis tersimpan.
            // Jalankan ulang pemanggilan direct API menggunakan kredensial cookie baru tersebut.
            if (!isExtractorInvoked) {
                val params = parseEmbedParams(embedUrl)
                if (params != null) {
                    val servers = fetchServerList(params)
                    if (!servers.isNullOrEmpty()) {
                        for (server in servers) {
                            val iframeUrl = fetchIframeUrl(server.key, embedUrl)
                            if (iframeUrl != null) {
                                val fixedUrl = fixStreamCastHubUrl(iframeUrl)
                                val extractorKey = SERVER_EXTRACTOR_MAP[server.name]
                                if (extractorKey != null) {
                                    getExtractorApiFromName(extractorKey).getSafeUrl(
                                        url = fixedUrl,
                                        referer = embedUrl,
                                        subtitleCallback = subtitleCallback,
                                        callback = callback
                                    )
                                    isExtractorInvoked = true
                                }
                            }
                        }
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
    // Helper: Hit /api/v1/l (iframe URL)
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
    private fun invokeExtractor(
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
