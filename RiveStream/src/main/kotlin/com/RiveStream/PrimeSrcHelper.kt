package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

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
                val response   = app.get(finalApiUrl, headers = standardHeaders, timeout = 10)
                val httpStatus = response.code
                val rawBody    = response.text

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
     * Embed-mode fallback lewat primesrc.me.
     * Kalau endpoint link kena Cloudflare/Turnstile, bypass pakai WebViewResolver
     * (useOkhttp = false wajib, biar JS challenge-nya jalan murni di WebView engine,
     * gak ke-split ke OkHttp client), lalu cookie hasil clearance dipakai ulang
     * buat request manual ke endpoint yang sama.
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
            val resS = app.get(urlS, headers = standardHeaders, timeout = 10).text
            val parsedS = tryParseJson<PrimeSrcServerResponse>(resS)

            val serversList = parsedS?.servers
            if (serversList != null) {
                for (server in serversList) {
                    val key = server.key ?: continue
                    val urlL = "https://primesrc.me/api/v1/l?key=$key"
                    val urlPing = "https://primesrc.me/spiderman?l=$key" // Polymorphic Activation Gate Penjaga Sesi

                    try {
                        var resL = ""
                        try {
                            // Eksekusi Ketukan Ping Latar Belakang Lapis Pertama untuk Aktivasi Token Sesi
                            try { 
                                app.get(urlPing, headers = standardHeaders + mapOf("Origin" to "https://primesrc.me"), timeout = 5) 
                            } catch (_: Exception) {}

                            val response = app.get(
                                urlL,
                                headers = standardHeaders + mapOf("Origin" to "https://primesrc.me"),
                                timeout = 10
                            )
                            if (response.code == 403 ||
                                response.text.contains("window._cf_chl_opt") ||
                                response.text.contains("Just a moment...")
                            ) {
                                throw IOException("Cloudflare Challenge Detected")
                            }
                            resL = response.text
                        } catch (challengeException: Exception) {
                            android.util.Log.d("RiveStream", "[AMANGOKIL] Memicu Bypasser Turnstile via WebViewResolver...")

                            val mainEmbedUrl = "https://primesrc.me/embed/$type?tmdb=$cleanId&type=$type"
                            val resolver = WebViewResolver(
                                // Mengadopsi Dynamic Token Sniffer Universal untuk menjaring mutasi parameter lintas rute
                                interceptUrl = Regex(".*[a-zA-Z0-9_-]+\\?(key|l)=[a-zA-Z0-9]{5}.*"),
                                useOkhttp = false,
                                userAgent = null
                            )
                            val (interceptedRequest, _) = resolver.resolveUsingWebView(
                                mainEmbedUrl,
                                headers = standardHeaders
                            )

                            if (interceptedRequest == null) {
                                android.util.Log.w(
                                    "RiveStream",
                                    "[AMANGOKIL] WebViewResolver timeout/gagal intercept untuk key=$key, skip"
                                )
                                continue
                            }

                            val systemCookie = android.webkit.CookieManager.getInstance().getCookie("https://primesrc.me")
                            
                            // Memicu Ulang Ping Aktivasi Memanfaatkan Kuki Hasil Jaringan WebView Engine
                            try {
                                app.get(
                                    urlPing,
                                    headers = standardHeaders + mapOf(
                                        "Origin" to "https://primesrc.me",
                                        "Cookie" to (systemCookie ?: "")
                                    ),
                                    timeout = 5
                                )
                            } catch (_: Exception) {}

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
                        val rawLink = parsedL?.link ?: continue

                        if (rawLink.contains("streamta.site") || rawLink.contains("streamtape.com")) {
                            android.util.Log.d("RiveStream", "[AMANGOKIL] Memproses Rute Hilir Streamtape...")

                            val embedHtml = app.get(
                                rawLink,
                                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://primesrc.me/")
                            ).text

                            // ─── REVISI AMANGOKIL PIPELINE ENGINE: ANTI HONEYPOT & ALFANUMERIK KETAT ───
                            // Lapis 1: Isolasi string cipher mutlak hanya di dalam tanda petik tunggal yang memuat id=
                            val anchorRegex = """'([^']*[\?&]id=[^']*)'""".toRegex()
                            val anchorMatch = anchorRegex.find(embedHtml)?.groupValues?.get(1)

                            val cleanLink = if (anchorMatch != null) {
                                // Lapis 2: Memanen parameter secara kaku bebas ampas tag HTML </div
                                val id = """[\?&]id=([a-zA-Z0-9_-]+)""".toRegex().find(anchorMatch)?.groupValues?.get(1)
                                val expires = """[\?&]expires=([0-9]+)""".toRegex().find(anchorMatch)?.groupValues?.get(1)
                                val ip = """[\?&]ip=([a-zA-Z0-9_-]+)""".toRegex().find(anchorMatch)?.groupValues?.get(1)
                                val token = """[\?&]token=([a-zA-Z0-9_-]+)""".toRegex().find(anchorMatch)?.groupValues?.get(1)

                                if (id != null && expires != null && ip != null && token != null) {
                                    "https://streamta.site/get_video?id=$id&expires=$expires&ip=$ip&token=$token&stream=1"
                                } else {
                                    null
                                }
                            } else {
                                null
                            }

                            if (cleanLink != null) {
                                val clientWithoutRedirects = app.baseClient.newBuilder()
                                    .followRedirects(false)
                                    .followSslRedirects(false)
                                    .build()

                                val req = Request.Builder()
                                    .url(cleanLink)
                                    .header("User-Agent", USER_AGENT)
                                    .header("Referer", rawLink)
                                    .build()

                                withContext(Dispatchers.IO) {
                                    try {
                                        val response: okhttp3.Response = clientWithoutRedirects.newCall(req).execute()
                                        val realVideoUrl = response.header("Location")
                                        response.close()

                                        if (!realVideoUrl.isNullOrEmpty()) {
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
                                    } catch (err: Exception) {
                                        logError(err)
                                    }
                                }
                            }
                        } else {
                            val rewrittenUrl = rawLink.replace("streamta.site", "streamtape.com")
                            val extLoaded = loadExtractor(rewrittenUrl, subtitleCallback, callback)
                            if (extLoaded) linksFound++
                        }

                    } catch (e: Exception) {
                        logError(e)
                    }
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackendFetchResponse(
    @JsonProperty("data") val data: BackendData?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackendData(
    @JsonProperty("sources")  val sources:  List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackendSource(
    @JsonProperty("quality") val quality: Any?,
    @JsonProperty("url")     val url:     String?,
    @JsonProperty("source")  val source:  String?,
    @JsonProperty("format")  val format:  String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file")  val file:  String?
)

// ─── DATA CLASSES RESPONS EMBED AGREGATOR PRIMESRC ───────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrimeSrcServerResponse(
    @JsonProperty("servers") val servers: List<PrimeSrcServer>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrimeSrcServer(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key")  val key:  String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PrimeSrcLinkResponse(
    @JsonProperty("link") val link: String?,
    @JsonProperty("host") val host: String?
)
