package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class PrimeSrcHelper {

    companion object {
        private const val SCRAPER_BASE = "https://scrapper.rivestream.app/api/provider"

        private val TEST_PROVIDERS = listOf(
            "primevids", "flowcast", "asiacloud", "guru", "ophim", "flow", "speed", "vidsrc"
        )
    }

    suspend fun invokePrimeSrc(
        data: String,
        mainUrl: String,
        providerName: String,
        apiKey: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?season=")
        val cleanId = cleanData.substringBefore("?").substringAfterLast("/")
        var linksFound = 0

        val standardHeaders = mapOf(
            "Accept"     to "application/json",
            "User-Agent" to USER_AGENT,
            "Origin"     to "https://rivestream.app",
            "Referer"    to "https://rivestream.app/"
        )

        android.util.Log.d("RiveStream", "=== MEMULAI PIPELINE === ID=$cleanId | TIPE=${if (isMovie) "MOVIE" else "TV"}")

        TEST_PROVIDERS.forEach { service ->
            val finalApiUrl = if (isMovie) {
                "$SCRAPER_BASE?provider=$service&id=$cleanId&api_key=$apiKey"
            } else {
                val season  = cleanData.substringAfter("?season=").substringBefore("&")
                val episode = cleanData.substringAfter("&episode=")
                "$SCRAPER_BASE?provider=$service&id=$cleanId&season=$season&episode=$episode&api_key=$apiKey"
            }

            android.util.Log.d("RiveStream", "PROVIDER: ${service.uppercase()} | URL: $finalApiUrl")

            try {
                val response     = app.get(finalApiUrl, headers = standardHeaders, timeout = 10)
                val httpStatus   = response.code
                val rawBody      = response.text

                android.util.Log.d("RiveStream", "HTTP $httpStatus | BODY: $rawBody")

                val parsedData = tryParseJson<BackendFetchResponse>(rawBody)
                if (parsedData == null) {
                    android.util.Log.w("RiveStream", "PARSE GAGAL untuk provider $service")
                    return@forEach
                }

                val sources  = parsedData.data?.sources
                val captions = parsedData.data?.captions

                android.util.Log.d("RiveStream", "SOURCES: ${sources?.size ?: 0} | CAPTIONS: ${captions?.size ?: 0}")

                captions?.forEach { caption ->
                    val captionUrl   = caption.file  ?: return@forEach
                    val captionLabel = caption.label ?: "Subtitle"
                    subtitleCallback(newSubtitleFile(captionLabel, captionUrl))
                }

                sources?.forEach { source ->
                    val streamUrl   = source.url     ?: return@forEach
                    val qualityName = source.quality?.toString() ?: "AUTO"
                    val sourceLabel = source.source  ?: "RiveStream"
                    val displayName = "$sourceLabel - $qualityName"

                    val linkType: ExtractorLinkType? = when {
                        source.format?.contains("hls",  ignoreCase = true) == true -> ExtractorLinkType.M3U8
                        source.format?.contains("dash", ignoreCase = true) == true -> ExtractorLinkType.DASH
                        source.format?.contains("mp4",  ignoreCase = true) == true -> ExtractorLinkType.VIDEO
                        streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                        streamUrl.contains(".mpd")  -> ExtractorLinkType.DASH
                        else -> INFER_TYPE
                    }

                    callback(newExtractorLink(
                        source = providerName,
                        name   = displayName,
                        url    = streamUrl,
                        type   = linkType
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(qualityName)
                        this.headers = mapOf("Origin" to mainUrl)
                    })
                    linksFound++
                }

            } catch (e: Exception) {
                logError(e)
            }
        }

        android.util.Log.d("RiveStream", "=== PIPELINE SELESAI | TOTAL LINKS: $linksFound ===")
        return linksFound > 0
    }

    /**
     * IMPLEMENTASI EMBED MODE AGREGATOR DENGAN DUKUNGAN UPGRADE TURNSTILE & STREAMTAPE
     */
    suspend fun invokeEmbedMode(
        data: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?season=")
        val cleanId = cleanData.substringBefore("?").substringAfterLast("/")
        
        val type = if (isMovie) "movie" else "tv"
        
        val urlS = if (isMovie) {
            "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=$type"
        } else {
            val season  = cleanData.substringAfter("?season=").substringBefore("&")
            val episode = cleanData.substringAfter("&episode=")
            "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=$type&season=$season&episode=$episode"
        }

        val standardHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer"    to "https://www.rivestream.app/"
        )

        var linksFound = 0
        try {
            // 1. Ambil manifes daftar server kunci agregator
            val resS = app.get(urlS, headers = standardHeaders, timeout = 10).text
            val parsedS = tryParseJson<PrimeSrcServerResponse>(resS)
            
            parsedS?.servers?.forEach { server ->
                val key = server.key ?: return@forEach
                val urlL = "https://primesrc.me/api/v1/l?key=$key"
                
                try {
                    // 2. Jabat Tangan Kriptografi Preemptive untuk /api/v1/l
                    var resL = ""
                    try {
                        val response = app.get(urlL, headers = standardHeaders + mapOf("Origin" to "https://primesrc.me"), timeout = 10)
                        
                        // Proteksi Deteksi Darurat Interseptor
                        if (response.code == 403 || response.text.contains("window._cf_chl_opt") || response.text.contains("Just a moment...")) {
                            throw IOException("Cloudflare Turnstile Active")
                        }
                        resL = response.text
                    } catch (challengeException: Exception) {
                        android.util.Log.d("RiveStream", "[AMANGOKIL] Memulai Rejuvenation Sesi via WebViewResolver...")
                        
                        // Gunakan WebViewResolver Terbaru dengan Aturan Mengunci useOkhttp = false
                        val mainEmbedUrl = "https://primesrc.me/embed/movie?tmdb=$cleanId&type=$type"
                        val resolver = WebViewResolver(interceptUrl = Regex(".*api/v1/l.*"), useOkhttp = false)
                        resolver.resolveUsingWebView(mainEmbedUrl, headers = standardHeaders)
                        
                        // Panen Cookie Paspor dari Sesi WebView Engine
                        val systemCookie = android.webkit.CookieManager.getInstance().getCookie("https://primesrc.me")
                        resL = app.get(
                            urlL, 
                            headers = standardHeaders + mapOf(
                                "Origin" to "https://primesrc.me",
                                "Cookie" to (systemCookie ?: "")
                            ), 
                            timeout = 10
                        ).text
                    }

                    val parsedL = tryParseJson<PrimeSrcLinkResponse>(resL)
                    val rawLink = parsedL?.link ?: return@forEach
                    
                    // INTERSEPSI ENGINE ROUTER KHUSUS STREAMTAPE (HILIR EXTRACTION)
                    if (rawLink.contains("streamta.site") || rawLink.contains("streamtape.com")) {
                        android.util.Log.d("RiveStream", "[AMANGOKIL] Mengeksekusi Jalur Distribusi Hilir Streamtape: $rawLink")
                        
                        // A. Ambil halaman HTML embed untuk memicu Set-Cookie: _b=...
                        val embedHtml = app.get(rawLink, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://primesrc.me/")).text
                        
                        // B. Pemecahan Obfuskasi Substring DOM via Regex Parser
                        val botlinkRegex = """document\.getElementById\(['"]botlink['"]\)\.innerHTML\s*=\s*['"]//streamta['"]\s*\+\s*\(['"]([^'"]+)['"]\)\.substring\((\d+)\)""".toRegex()
                        val matchResult = botlinkRegex.find(embedHtml)
                        
                        val cleanLink = if (matchResult != null) {
                            val rawString = matchResult.groupValues[1]
                            val index = matchResult.groupValues[2].toInt()
                            
                            // Simulasi pemotongan string substring(n) dan injeksi parameter stream
                            "https://streamta" + rawString.substring(index) + "&stream=1"
                        } else {
                            null
                        }

                        if (cleanLink != null) {
                            android.util.Log.d("RiveStream", "[AMANGOKIL] Sukses De-obfuskasi URL /get_video: $cleanLink")
                            
                            // C. Intersepsi Lapangan Pengalihan HTTP 302 Found menuju CDN Storage Mentah
                            val clientWithoutRedirects = app.okhttpClient.newBuilder()
                                .followRedirects(false)
                                .followSslRedirects(false)
                                .build()
                                
                            val req = Request.Builder()
                                .url(cleanLink)
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", rawLink)
                                .build()
                                
                            withContext(Dispatchers.IO) {
                                clientWithoutRedirects.newCall(req).execute().use { response ->
                                    val realVideoUrl = response.header("Location")
                                    if (!realVideoUrl.isNullOrEmpty()) {
                                        android.util.Log.d("RiveStream", "[AMANGOKIL] Sukses Mencegat 302! CDN Terowong: $realVideoUrl")
                                        
                                        // Ditiupkan secara Asynchronous Callback Streaming (Aturan Versi Terbaru)
                                        callback(newExtractorLink(
                                            source = "Streamtape",
                                            name   = "Streamtape High Quality",
                                            url    = realVideoUrl,
                                            type   = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://streamtape.com/"
                                        })
                                        linksFound++
                                    }
                                }
                            }
                        }
                    } else {
                        // 3. REWRITE HOST UTK PROVIDER NON-STREAMTAPE ASLI
                        val rewrittenUrl = rawLink.replace("streamta.site", "streamtape.com")
                        
                        // 4. DELEGASI CORE EXTRACTOR ASLI
                        val extLoaded = loadExtractor(rewrittenUrl, subtitleCallback, callback)
                        if (extLoaded) linksFound++
                    }
                    
                } catch (e: Exception) {
                    logError(e)
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
        
        android.util.Log.d("RiveStream", "=== EMBED PIPELINE SELESAI | LINKS BERHASIL DIDUKUNG: $linksFound ===")
        return linksFound > 0
    }
}

// ─── DATA CLASSES RESPONS BACKEND RIVESTREAM ─────────────────────────────────

data class BackendFetchResponse(
    @JsonProperty("data") val data: BackendData?
)

data class BackendData(
    @JsonProperty("sources")  val sources:  List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)

data class BackendDataclassSource(
    @JsonProperty("quality") val quality: Any?,
    @JsonProperty("url")     val url:     String?,
    @JsonProperty("source")  val source:  String?,
    @JsonProperty("format")  val format:  String?
)

data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file")  val file:  String?
)

// ─── DATA CLASSES RESPONS EMBED AGREGATOR PRIMESRC ───────────────────────────

data class PrimeSrcServerResponse(
    @JsonProperty("servers") val servers: List<PrimeSrcServer>?
)

data class PrimeSrcServer(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key")  val key:  String?
)

data class PrimeSrcLinkResponse(
    @JsonProperty("link") val link: String?,
    @JsonProperty("host") val host: String?
)
