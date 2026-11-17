package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

import com.lagradost.api.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val parsed = url.toHttpUrlOrNull() ?: return
        if (!parsed.isHttps || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            !(parsed.host == "majorplay.net" || parsed.host.endsWith(".majorplay.net"))) return
        val claim = parsed.queryParameter("claim")?.takeIf { it.isNotBlank() } ?: return
        resolveClaim(SessionClaimResponse(claim = claim,
            redeemUrl = parsed.queryParameter("redeemUrl") ?: "/watch/session/redeem"),
            referer ?: "https://z2.idlixku.com/", subtitleCallback, callback)
    }

    suspend fun resolveClaim(
        session: SessionClaimResponse,
        effectiveReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        sessionCookies: Map<String, String> = emptyMap(),
        providerName: String = "Idlix",
        allowWebView: Boolean = true
    ): Boolean {
        val claimToken = session.claim?.takeIf { it.isNotBlank() } ?: return IdlixPlayback.reject("redeem", "missing_claim")
        val page = effectiveReferer.toHttpUrlOrNull() ?: return IdlixPlayback.reject("redeem", "invalid_referer")
        val effectiveOrigin = "${page.scheme}://${page.host}"
        val target = IdlixPlayback.redeemUrl(session.redeemUrl, effectiveOrigin) ?: return IdlixPlayback.reject("redeem", "invalid_endpoint")
        IdlixPlayback.trace("redeem.start", "videoId=${session.videoId} host=${target.toHttpUrlOrNull()?.host}")
        val safeHeaders = mapOf(
            "Origin" to effectiveOrigin,
            "Referer" to effectiveReferer,
            "User-Agent" to IdlixPlayback.USER_AGENT
        )
        val jsonMediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
        val requestBodyData = mapOf("claim" to claimToken, "mode" to "browser").toJson().toRequestBody(jsonMediaType)
        val redeemCookies = if (target.toHttpUrlOrNull()?.host == page.host) sessionCookies else emptyMap()
        val redeem = app.post(target, headers = safeHeaders, cookies = redeemCookies, requestBody = requestBodyData,
            allowRedirects = false, timeout = 15)
        IdlixPlayback.trace("redeem.response", "http=${redeem.code}")
        if (redeem.code != 200) {
            redeem.okhttpResponse.close()
            return IdlixPlayback.reject("redeem", "http_${redeem.code}")
        }
        val responseText = IdlixPlayback.readText(redeem) ?: return IdlixPlayback.reject("redeem", "response_too_large")
        val response = AppUtils.parseJson<NewMajorplayResponse>(responseText)
        if (response.code != "ok" || response.mode != "browser") return IdlixPlayback.reject("redeem", "invalid_response_contract")
        val master = response.url?.toHttpUrlOrNull() ?: return IdlixPlayback.reject("master", "invalid_url")
        val videoId = IdlixPlayback.videoId(master) ?: return IdlixPlayback.reject("master", "invalid_host_or_path_layout")
        if (session.videoId != null && session.videoId != videoId) return IdlixPlayback.reject("master", "claim_video_mismatch")
        if (response.videoId != null && response.videoId != videoId) return IdlixPlayback.reject("master", "redeem_video_mismatch")
        if (master.queryParameter("t").isNullOrBlank()) return IdlixPlayback.reject("master", "missing_media_session")
        val expiresAt = response.expiresAt ?: return IdlixPlayback.reject("master", "missing_expiry")
        val serverTime = redeem.headers["Date"]?.let {
            runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(it)?.time }.getOrNull()
        } ?: System.currentTimeMillis()
        if (expiresAt <= serverTime / 1000L + 30L || expiresAt > serverTime / 1000L + 86_400L) return IdlixPlayback.reject("master", "expiry_outside_window")
        IdlixPlayback.trace("master.identity", "videoId=$videoId layout=${if (master.pathSegments[1] == videoId) "legacy" else "sharded"} durationSec=${session.durationSec}")

        val authorized = IdlixPlayback.authorize(master, master)
        // Only transport failures permit rendering. Access/identity/manifest rejection is terminal.
        val direct = IdlixPlayback.validateMedia(authorized, videoId, safeHeaders)
        val resolved = direct.url ?: if (allowWebView && direct.transientFailure) {
            IdlixPlayback.browserMedia(page, videoId, safeHeaders, target.toHttpUrlOrNull())
        } else null
        if (resolved == null) return IdlixPlayback.reject("master", "no_valid_media")
        val masterConfigUrl = resolved.toString()

        val seenSubtitles = hashSetOf<String>()
        response.subtitles?.take(64)?.forEach { sub ->
            val subUrl = IdlixPlayback.subtitleUrl(sub.path, resolved) ?: return@forEach
            if (!seenSubtitles.add(subUrl)) return@forEach
            subtitleCallback.invoke(newSubtitleFile(sub.label ?: sub.lang ?: "Indo", subUrl))
        }
        // CS3IPlayer resolves getVideoInterceptor via APIHolder.getApiFromNameNull(link.source).
        // source must identify the MainAPI, not this extractor's display name.
        callback.invoke(newExtractorLink(providerName, "Majorplay Auto Quality", masterConfigUrl, ExtractorLinkType.M3U8) {
            this.headers = safeHeaders
            this.referer = effectiveReferer
            this.quality = Qualities.Unknown.value
        })
        IdlixPlayback.trace("extractor.complete", "source=$providerName videoId=$videoId callbacks=1 type=M3U8")
        return true
    }
}

// FIX #5: Data class dipindah ke luar class agar lebih sesuai konvensi Cloudstream
@JsonIgnoreProperties(ignoreUnknown = true)
data class NewMajorplayResponse(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("mode") val mode: String? = null,
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("expiresAt") val expiresAt: Long? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null,
    @JsonProperty("label") val label: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NewMajorSubtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("path") val path: String? = null
)

/** Idlix's served browser player rewrites t/pm on every request for the selected video. */
internal object IdlixPlayback {
    const val BUILD_MARKER = "IDLIX_PLAYBACK_HTTP_BROWSER_V4"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    // Application context only; never retain an Activity through the plugin lifecycle.
    var applicationContext: Context? = null

    // Callers supply IDs, statuses and fixed reason codes, never credentials/full URLs.
    fun trace(stage: String, detail: String) = Log.i("IdlixProvider", "[IDLIX] $stage $detail")

    fun reject(stage: String, reason: String): Boolean {
        trace("$stage.reject", "reason=$reason callbacks=0")
        return false
    }

    // From the media-host contract in Idlix's served 2znv4-qfvxuz6.js (not ad hosts).
    private val cdnDomains = setOf(
        "academiclearning.site", "akademivo.website", "aspireheightsacademy.digital",
        "careerskills.site", "classroomlearning.space", "distancelearning.space",
        "e-learningtips.site", "edubambu.store", "educationalskills.space",
        "educationlearning.store", "educationonlinelearning.xyz", "edusparkonline.quest",
        "eldrinlorekeeper.store", "exampreparation.space", "highereducation.online",
        "homeworkhelp.site", "horizonacademy.site", "knowledgequest.store",
        "languagelearning.space", "learnleapinstitute.site", "mindbloomcourses.website",
        "onlineeducation.store", "onlinestudy.online", "scholarshipeducation.online",
        "skilldevelopment.space", "skillsprouthub.space", "stemeducation.site",
        "studentsuccess.space", "studycourses.site", "tutoringskills.site"
    )
    private val z4Domains = setOf(
        "academiclearning.site", "aspireheightsacademy.digital", "classroomlearning.space",
        "edubambu.store", "edusparkonline.quest", "eldrinlorekeeper.store",
        "horizonacademy.site", "knowledgequest.store", "learnleapinstitute.site",
        "mindbloomcourses.website", "onlinestudy.online", "skillsprouthub.space"
    )

    private fun secure(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null

    fun mediaHost(host: String): Boolean {
        if (host == "e2e.majorplay.net") return true
        val prefix = host.substringBefore('.')
        val domain = host.substringAfter('.', "")
        return domain in cdnDomains && (prefix in setOf("g1", "g2", "g3", "g5", "g6", "g7", "g8") ||
            prefix == if (domain in z4Domains) "z4" else "g4")
    }

    fun videoId(url: HttpUrl): String? {
        val path = url.pathSegments
        if (!secure(url) || !mediaHost(url.host) || path.size < 3 || path[0] != "v") return null
        // Observed layouts: /v/<videoId>/... (legacy) and /v/z7/<videoId>/... .
        // The old fixed index rejected valid legacy masters before emitting any link.
        val index = if (path[1].matches(Regex("z[0-9]+"))) 2 else 1
        if (index >= path.lastIndex || path.last().isEmpty()) return null
        return path[index].takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
    }

    fun authorize(url: HttpUrl, master: HttpUrl): HttpUrl {
        val id = videoId(master) ?: return url
        val token = master.queryParameter("t")?.takeIf { it.isNotBlank() } ?: return url
        if (videoId(url) != id) return url
        return url.newBuilder().setQueryParameter("t", token).setQueryParameter("pm", "browser").build()
    }

    fun redeemUrl(value: String?, origin: String): String? {
        val base = origin.toHttpUrlOrNull()?.takeIf(::secure) ?: return null
        if (value == "/watch/session/redeem") return base.resolve("/api/watch/session/redeem")?.toString()
        val url = value?.toHttpUrlOrNull()?.takeIf(::secure) ?: return null
        return url.takeIf {
            (it.host == "majorplay.net" || it.host.endsWith(".majorplay.net")) &&
                it.encodedPath.trimEnd('/') == "/api/play" && it.query == null
        }?.toString()
    }

    /** Reject excessive waits instead of claiming before the server's unlock time. */
    fun waitMillis(serverNow: Long?, unlockAt: Long?, countdownSec: Long?): Long? {
        val countdown = countdownSec ?: 7L
        if (countdown !in 0..29 || serverNow == null || unlockAt == null || serverNow < 0 || unlockAt < 0) return null
        val remaining = if (unlockAt > serverNow) unlockAt - serverNow else 0L
        if (remaining > 29_000L) return null
        return maxOf(countdown * 1000L, remaining) + 1000L
    }

    internal data class MediaResult(val url: HttpUrl? = null, val transientFailure: Boolean = false)

    suspend fun readText(response: com.lagradost.nicehttp.NiceResponse): String? {
        return try {
            withContext(Dispatchers.IO) {
                val bytes = response.okhttpResponse.peekBody(262_145L).bytes()
                if (bytes.size > 262_144) null else bytes.toString(Charsets.UTF_8)
            }
        } finally {
            response.okhttpResponse.close()
        }
    }

    /** URL extensions are only hints; require an HLS body and content-scoped child references. */
    fun validManifest(text: String, master: HttpUrl, expectedId: String): Boolean {
        if (text.length > 262_144 || videoId(master) != expectedId) return false
        val lines = text.trimStart('\uFEFF', ' ', '\r', '\n').lineSequence().map(String::trim).toList()
        if (lines.firstOrNull() != "#EXTM3U") return false
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXTINF:") }) return false
        // SAMPLE-AES/KEYFORMAT mark protected streams, unsupported by this clear-HLS resolver.
        if (lines.any { (it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:")) &&
                (Regex("""(?:[:,])METHOD=([^,]+)""").find(it)?.groupValues?.get(1) !in setOf("AES-128", "NONE") ||
                    it.contains("KEYFORMAT=")) }) return false
        var mediaReferences = 0
        for (line in lines) {
            val values = if (line.startsWith('#')) {
                Regex("""(?:^|[:,])URI="([^"]+)""", RegexOption.IGNORE_CASE)
                    .findAll(line).map { it.groupValues[1] }.toList()
            } else if (line.isNotEmpty()) listOf(line) else emptyList()
            for (value in values) {
                val child = master.resolve(value) ?: return false
                if (videoId(child) != expectedId) return false
                if (!line.startsWith('#')) mediaReferences++
            }
        }
        return mediaReferences > 0
    }

    suspend fun validateMedia(candidate: HttpUrl, expectedId: String, headers: Map<String, String>): MediaResult {
        if (videoId(candidate) != expectedId || candidate.queryParameter("t").isNullOrBlank()) return MediaResult()
        return try {
            withTimeoutOrNull(12_000L) {
                var target = candidate
                val visited = hashSetOf<String>()
                repeat(4) {
                    if (!visited.add(target.toString())) return@withTimeoutOrNull MediaResult()
                    val response = app.get(target.toString(), headers = headers, allowRedirects = false, timeout = 8)
                    try {
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            val next = response.headers["Location"]?.let(target::resolve)
                                ?: return@withTimeoutOrNull MediaResult()
                            if (videoId(next) != expectedId) return@withTimeoutOrNull MediaResult()
                            target = authorize(next, candidate)
                        } else {
                            if (response.code != 200) return@withTimeoutOrNull MediaResult(
                                transientFailure = response.code in setOf(408, 500, 502, 503, 504))
                            val mime = response.headers["Content-Type"].orEmpty().substringBefore(';').trim().lowercase()
                            val size = response.headers["Content-Length"]?.toLongOrNull()
                            if (size != null && size > 262_144) return@withTimeoutOrNull MediaResult()
                            if (mime.isNotEmpty() && mime !in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl",
                                    "audio/mpegurl", "audio/x-mpegurl", "application/octet-stream", "text/plain")) return@withTimeoutOrNull MediaResult()
                            val text = readText(response) ?: return@withTimeoutOrNull MediaResult()
                            if (!validManifest(text, target, expectedId)) {
                                trace("IDLIX_MEDIA_REJECTED", "reason=manifest_contract")
                                return@withTimeoutOrNull MediaResult()
                            }
                            trace("IDLIX_MEDIA_VALIDATED", "type=HLS")
                            return@withTimeoutOrNull MediaResult(target)
                        }
                    } finally {
                        response.okhttpResponse.close()
                    }
                }
                MediaResult()
            } ?: MediaResult(transientFailure = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            MediaResult(transientFailure = true)
        }
    }

    fun subtitleUrl(value: String?, master: HttpUrl): String? {
        if (value.isNullOrBlank() || value.length > 4096) return null
        val url = master.resolve(value)?.takeIf { secure(it) && mediaHost(it.host) } ?: return null
        val id = videoId(url)
        if (id != null && id != videoId(master)) return null
        if (url.pathSegments.lastOrNull()?.substringAfterLast('.')?.lowercase() in
            setOf("html", "js", "m3u8", "mp4", "mpd", "png", "jpg", "jpeg", "webp")) return null
        return url.newBuilder().host(master.host).build().toString()
    }

    private val browserSlot = Semaphore(1)

    /** Own the WebView lifecycle: SDK resolver lacks cancellation cleanup and logs signed URLs.
     * The site's movie component explicitly supports ?play=1. Series overview pages are excluded.
     * No injected JS, CAPTCHA solving, cookie export, or speculative player endpoints.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun browserMedia(page: HttpUrl, expectedId: String, headers: Map<String, String>, redeem: HttpUrl?): HttpUrl? {
        val path = page.pathSegments
        if (!secure(page) || page.host != "z2.idlixku.com" || path.size != 2 || path[0] != "movie" ||
            path[1].isBlank() || !browserSlot.tryAcquire()) return null
        val requests = Channel<HttpUrl>(8)
        var view: WebView? = null
        try {
            trace("IDLIX_WEBVIEW_START", "player=idlix_movie")
            return withTimeoutOrNull(30_000L) {
                withContext(Dispatchers.Main.immediate) {
                    val context = applicationContext ?: return@withContext
                    view = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkImage = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = USER_AGENT
                        webViewClient = object : WebViewClient() {
                            private fun blocked() = WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString().toHttpUrlOrNull()
                                return url == null || !secure(url) || url.host != page.host || url.encodedPath != page.encodedPath
                            }

                            @Suppress("OVERRIDE_DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                val target = url.toHttpUrlOrNull()
                                return target == null || !secure(target) || target.host != page.host || target.encodedPath != page.encodedPath
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val url = request.url.toString().toHttpUrlOrNull() ?: return blocked()
                                if (!secure(url)) return blocked()
                                if (videoId(url) == expectedId && url.encodedPath.endsWith(".m3u8", true) &&
                                    !url.queryParameter("t").isNullOrBlank()) {
                                    requests.trySend(authorize(url, url))
                                    return blocked()
                                }
                                // Allow site code/API only. Media downloads and foreign ad/tracking hosts are blocked.
                                return if (url.host == page.host ||
                                    (redeem != null && url.host == redeem.host && url.encodedPath == redeem.encodedPath)) null else blocked()
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.cancel()
                                requests.close()
                            }
                        }
                        loadUrl(page.newBuilder().setQueryParameter("play", "1").build().toString(),
                            mapOf("Referer" to page.toString()))
                    }
                }
                if (view == null) return@withTimeoutOrNull null
                val seen = hashSetOf<String>()
                for (candidate in requests) {
                    if (!seen.add(candidate.toString())) continue
                    validateMedia(candidate, expectedId, headers).url?.let {
                        trace("IDLIX_WEBVIEW_SUCCESS", "type=HLS")
                        return@withTimeoutOrNull it
                    }
                    if (seen.size >= 8) break
                }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace("IDLIX_WEBVIEW_FAILED", "exception=${e.javaClass.simpleName}")
            return null
        } finally {
            requests.close()
            try {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    view?.apply { stopLoading(); webViewClient = WebViewClient(); destroy() }
                    view = null
                }
            } finally {
                browserSlot.release()
                trace("IDLIX_CLEANUP", "player=webview")
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun interceptor(masterUrl: String): Interceptor {
        val master = masterUrl.toHttpUrlOrNull()
        val messages = AtomicInteger()
        trace("interceptor.attach", "videoId=${master?.let(::videoId)}")
        // An independent client keeps redirect handling inside the credential boundary.
        val scoped = client.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val authorized = master?.let { authorize(request.url, it) } ?: request.url
            val logRequest = messages.getAndIncrement() < 8
            try {
                val response = chain.proceed(request.newBuilder().url(authorized).build())
                if (logRequest || response.code >= 400) {
                    val contentType = response.header("Content-Type").orEmpty()
                    val kind = when {
                        master?.host == request.url.host && master.encodedPath == request.url.encodedPath -> "master"
                        contentType.contains("mpegurl", true) -> "playlist"
                        contentType.contains("vtt", true) -> "subtitle"
                        else -> "media_or_child"
                    }
                    trace("media.request", "kind=$kind scoped=${master != null && videoId(request.url) == videoId(master)} rewritten=${authorized != request.url} http=${response.code}")
                }
                response
            } catch (e: java.io.IOException) {
                trace("media.error", "host=${request.url.host} exception=${e.javaClass.simpleName}")
                throw e
            }
        }.build()
        return Interceptor { chain -> scoped.newCall(chain.request()).execute() }
    }
}
