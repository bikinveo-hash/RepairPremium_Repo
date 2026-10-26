package com.AdiDrakor

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Local port of Moviebox H5 at 4a4d272. No catalog UI, plugin registration or cross-module state.
 */
internal class AdiDrakorMovieboxEngine(
    private val name: String,
    private val tmdbApiKey: String? = null,
) {
    private val catalogCache = AdiDrakorMovieboxMapping.CatalogCache()
    private val mainUrl = "https://api3.aoneroom.com"

    companion object {
        private const val TAG = "AdiDrakorMovieboxEngine"
        private const val CS_USER_AGENT =
            "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        private const val PLAYBACK_TOKEN_URL =
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
        private const val PLAYBACK_USER_AGENT =
            "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
        private const val PLAYBACK_VERSION_NAME = "4.0.03.0920.03"
        private const val PLAYBACK_VERSION_CODE = 50020130L

        private fun clientInfo(): String =
            "{\"package_name\":\"com.community.oneroom\",\"version_name\":\"3.0.13.0325.03\"," +
                "\"version_code\":50020088,\"os\":\"android\",\"os_version\":\"13\"," +
                "\"device_id\":\"${deviceId()}\",\"install_store\":\"ps\"," +
                "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"US\"," +
                "\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

        private const val ID_PREFS = "adidrakor_moviebox_identity"
        private const val ID_KEY = "apkdeviceid"
        private val ID_FORMAT = Regex("^[0-9a-f]{32}$")

        @Volatile private var appContext: Context? = null
        @Volatile private var cachedDeviceId: String? = null
        @Volatile private var persisted = false

        /** Attached by this module before its MainAPI is registered. */
        fun attachContext(context: Context) {
            appContext = context.applicationContext
            deviceId()
        }

        /**
         * Storage adalah sumber kebenaran. Nilai hanya dibuat sekali, lalu dipakai selamanya. Nilai
         * in-memory hanya dipakai bila storage sedang tidak tersedia, dan akan dipersist pada
         * kesempatan pertama sehingga identity tidak berganti antar-restart.
         */
        private fun deviceId(): String {
            val cached = cachedDeviceId
            if (cached != null && persisted) return cached

            val ctx = appContext
            if (ctx != null) {
                try {
                    val sp = ctx.getSharedPreferences(ID_PREFS, Context.MODE_PRIVATE)
                    val existing = sp.getString(ID_KEY, null)
                    if (!existing.isNullOrBlank() && ID_FORMAT.matches(existing)) {
                        cachedDeviceId = existing
                        persisted = true
                        return existing
                    }
                    val fresh = cached ?: md5(UUID.randomUUID().toString())
                    sp.edit().putString(ID_KEY, fresh).apply()
                    cachedDeviceId = fresh
                    persisted = true
                    return fresh
                } catch (e: Exception) {
                    Log.e(TAG, "[IDENTITY] storage tidak tersedia: ${e.message}")
                }
            }

            return cached ?: md5(UUID.randomUUID().toString()).also { cachedDeviceId = it }
        }

        private val SECRET_BYTES: ByteArray by lazy {
            val step1 =
                String(
                    Base64.decode(
                        "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==",
                        Base64.DEFAULT,
                    ),
                    Charsets.UTF_8,
                )
            Base64.decode(step1, Base64.DEFAULT)
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
        }

        /**
         * Canonical string mengikuti GatewaySignManager.doSign pada APK resmi.
         *
         * Tujuh baris dipisah "\n":
         * 1. HTTP method (huruf besar)
         * 2. accept
         * 3. content-type
         * 4. panjang body -> kosong bila tanpa body
         * 5. timestamp
         * 6. md5 hex body -> kosong bila tanpa body
         * 7. path (+query)
         *
         * Tanpa body, baris 4 dan 6 kosong sehingga hasilnya IDENTIK dengan canonical GET yang
         * selama ini bekerja. Karena itu satu fungsi ini aman dipakai untuk GET maupun POST.
         *
         * Kegagalan search sebelumnya terjadi karena percobaan hanya mengisi SALAH SATU dari baris
         * 4 atau 6, tidak pernah keduanya sekaligus.
         */
        private fun buildCanonical(
            method: String,
            pathWithQuery: String,
            ts: String,
            body: String,
        ): String {
            val length = if (body.isEmpty()) "" else body.length.toString()
            val digest = if (body.isEmpty()) "" else md5(body)
            return listOf(
                    method.uppercase(),
                    "application/json",
                    "application/json",
                    length,
                    ts,
                    digest,
                    pathWithQuery,
                )
                .joinToString("\n")
        }

        private fun generateSignature(
            method: String,
            pathWithQuery: String,
            ts: String,
            body: String = "",
        ): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val hmacBytes =
                mac.doFinal(
                    buildCanonical(method, pathWithQuery, ts, body).toByteArray(Charsets.UTF_8)
                )
            return "$ts|2|${Base64.encodeToString(hmacBytes, Base64.NO_WRAP)}"
        }

        private fun generateGuestToken(ts: String): String = "$ts,${md5(ts.reversed())}"
    }

    private fun headersFor(ts: String, signature: String, bearer: String?): Map<String, String> {
        val h =
            mutableMapOf(
                "user-agent" to CS_USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to signature,
                "x-client-info" to clientInfo(),
                "x-client-status" to "0",
            )
        if (!bearer.isNullOrBlank()) h["authorization"] = "Bearer $bearer"
        return h
    }

    private suspend fun postSigned(path: String, body: String, bearer: String?): String? {
        val ts = System.currentTimeMillis().toString()
        val sig = generateSignature("POST", path, ts, body)
        return try {
            val res =
                app.post(
                    "$mainUrl$path",
                    headers = headersFor(ts, sig, bearer),
                    timeout = 8,
                    allowRedirects = false,
                    requestBody =
                        body
                            .toByteArray(Charsets.UTF_8)
                            .toRequestBody("application/json".toMediaTypeOrNull()),
                )
            Log.d(TAG, "POST $path HTTP=${res.code} bytes=${res.text.length}")
            if (res.code == 200) res.text else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "POST $path gagal: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private val catalogSessionMutex = Mutex()
    private var catalogSession: Pair<Long, String?>? = null

    private suspend fun getBearerToken(): String? =
        catalogSessionMutex.withLock {
            val now = System.nanoTime() / 1_000_000
            catalogSession
                ?.takeIf { now - it.first in 0 until (if (it.second == null) 15_000 else 60_000) }
                ?.let {
                    return@withLock it.second
                }
            val token =
                try {
                    bootstrapCatalogBearer()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            catalogSession = (System.nanoTime() / 1_000_000) to token
            token
        }

    private suspend fun bootstrapCatalogBearer(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "page=1&perPage=1&tabId=0"

        val response =
            app.get(
                "$mainUrl$path?$query",
                headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), null),
                timeout = 8,
                allowRedirects = false,
            )

        val xUserHeader = response.headers["x-user"] ?: return null
        return """"token"\s*:\s*"([^"]+)"""".toRegex().find(xUserHeader)?.groupValues?.get(1)
    }

    private val modernPlaybackDeviceId: String by lazy {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    private fun modernPlaybackClientInfo(): String =
        JSONObject()
            .put("package_name", "com.community.mbox.in")
            .put("version_name", PLAYBACK_VERSION_NAME)
            .put("version_code", PLAYBACK_VERSION_CODE)
            .put("os", "android")
            .put("os_version", "14")
            .put("device_id", modernPlaybackDeviceId)
            .put("install_store", "official")
            .put("brand", "Google")
            .put("model", "Pixel 8")
            .put("system_language", "en")
            .put("net", "NETWORK_WIFI")
            .put("region", "IN")
            .put("timezone", "Asia/Calcutta")
            .put("sp_code", "")
            .toString()

    /**
     * Attached CS3 v36 canonicalizes an absolute request URL by sorting query keys before signing.
     * URI.query is intentionally used (not rawQuery), matching java.net.URI#getQuery from the
     * compiled provider.
     */
    private fun modernPlaybackCanonicalPath(url: String): String {
        val uri = URI(url)
        val path = uri.path.orEmpty()
        val query = uri.query.orEmpty()
        if (query.isBlank()) return path

        val sortedQuery =
            query
                .split("&")
                .filter { it.isNotBlank() }
                .map { entry ->
                    val parts = entry.split("=", limit = 2)
                    parts[0] to parts.getOrElse(1) { "" }
                }
                .sortedBy { it.first }
                .joinToString("&") { "${it.first}=${it.second}" }

        return if (sortedQuery.isBlank()) path else "$path?$sortedQuery"
    }

    private fun modernPlaybackSignature(url: String, ts: String): String {
        val canonical =
            listOf(
                    "GET",
                    "application/json",
                    "application/json",
                    "",
                    ts,
                    "",
                    modernPlaybackCanonicalPath(url),
                )
                .joinToString("\n")

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
        val signed = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return "$ts|2|${Base64.encodeToString(signed, Base64.NO_WRAP)}"
    }

    private fun modernPlaybackHeaders(url: String, bearer: String? = null): Map<String, String> {
        val ts = System.currentTimeMillis().toString()
        val headers =
            mutableMapOf(
                "user-agent" to PLAYBACK_USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to modernPlaybackSignature(url, ts),
                "x-client-info" to modernPlaybackClientInfo(),
                "x-client-status" to "0",
            )
        if (!bearer.isNullOrBlank()) headers["Authorization"] = "Bearer $bearer"
        return headers
    }

    private suspend fun fetchModernPlaybackBearer(): String? {
        return try {
            val response =
                app.get(
                    PLAYBACK_TOKEN_URL,
                    headers = modernPlaybackHeaders(PLAYBACK_TOKEN_URL),
                    timeout = 8,
                    allowRedirects = false,
                )

            val rawXUser = response.headers["x-user"]
            if (rawXUser.isNullOrBlank()) {
                Log.e(TAG, "[PLAYBACK-AUTH] x-user token header kosong HTTP=${response.code}")
                null
            } else {
                val token = JSONObject(rawXUser).optString("token", "")
                if (token.isBlank()) {
                    Log.e(TAG, "[PLAYBACK-AUTH] token anonymous kosong HTTP=${response.code}")
                    null
                } else {
                    token
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                TAG,
                "[PLAYBACK-AUTH] gagal mengambil token anonymous: " + "${e.javaClass.simpleName}",
            )
            null
        }
    }

    private fun decodeEdgeUrlPrefix(signCookie: String): String? {
        val encoded =
            Regex("""Edge-Cache-Cookie=urlprefix=([^:;\s]+)""", RegexOption.IGNORE_CASE)
                .find(signCookie)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() } ?: return null

        return try {
            val normalized =
                encoded.replace('_', '/').replace('-', '+').let {
                    it + "=".repeat((4 - it.length % 4) % 4)
                }

            val prefix =
                String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8).trimEnd('/')

            "$prefix/index.mpd"
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeCloudFrontPolicyResource(signCookie: String): String? {
        val encoded =
            Regex("""CloudFront-Policy=([^;]+)""", RegexOption.IGNORE_CASE)
                .find(signCookie)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() } ?: return null

        return try {
            val normalized =
                encoded.replace('-', '+').replace('~', '/').replace('_', '=').let {
                    it + "=".repeat((4 - it.length % 4) % 4)
                }

            val policyJson = String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)

            val resource =
                JSONObject(policyJson)
                    .optJSONArray("Statement")
                    ?.optJSONObject(0)
                    ?.optString("Resource")
                    ?.trim()
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: return null

            val uri = URI(resource)
            val path = uri.rawPath.trimEnd('*').trimEnd('/')
            val mediaPath =
                if (
                    path.endsWith(".mpd", true) ||
                        path.endsWith(".m3u8", true) ||
                        path.endsWith(".mp4", true)
                )
                    path
                else "$path/index.mpd"
            "${uri.scheme}://${uri.rawAuthority}$mediaPath" + (uri.rawQuery?.let { "?$it" } ?: "")
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPlaybackPolicyResource(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        return decodeEdgeUrlPrefix(signCookie) ?: decodeCloudFrontPolicyResource(signCookie)
    }

    data class EpData(val subjectId: String, val se: Int, val ep: Int, val subjectType: Int = 1)

    fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val scope = extractorLink.url
        val mediaClient =
            AdiDrakorMovieboxPlayback.client
                .newBuilder()
                .followRedirects(true)
                .followSslRedirects(false)
                .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val permitted =
                        AdiDrakorMovieboxPlayback.scopeHeaders(
                            extractorLink.headers,
                            scope,
                            request.url.toString(),
                        )
                    val builder = request.newBuilder()
                    extractorLink.headers.keys
                        .filter { key -> key !in permitted }
                        .forEach { builder.removeHeader(it) }
                    // No API bearer ever belongs on a media request.
                    builder.removeHeader("Authorization")
                    if (
                        AdiDrakorMovieboxPlayback.scopeHeaders(
                                mapOf("Cookie" to "scope-check"),
                                scope,
                                request.url.toString(),
                            )["Cookie"] == null
                    ) {
                        builder.removeHeader("Cookie").removeHeader("X-MB-Token")
                    }
                    chain.proceed(builder.build())
                }
                .build()
        return Interceptor { chain -> mediaClient.newCall(chain.request()).execute() }
    }

    /** Do not log response bodies, full signed URLs, cookies or bearer values. */
    private suspend fun playbackRequest(
        url: String,
        headers: Map<String, String>,
    ): Pair<Int, String>? =
        try {
            val response = app.get(url, headers = headers, timeout = 8, allowRedirects = false)
            Log.d(TAG, "[PLAYBACK] ${URI(url).host}${URI(url).path} HTTP=${response.code}")
            response.code to response.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "[PLAYBACK] ${URI(url).host}${URI(url).path} ${e.javaClass.simpleName}")
            null
        }

    private data class PlaybackChoice(
        val stream: AdiDrakorMovieboxPlayback.Stream,
        val headers: Map<String, String>,
        val referer: String,
        val format: AdiDrakorMovieboxPlayback.Format,
    )

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        metadataContext: Map<String, JSONObject> = emptyMap(),
        mediaSession: AdiDrakorMovieboxMedia = AdiDrakorMovieboxMedia(),
        onStage: (String) -> Unit = {},
    ): Boolean {
        val selected = AppUtils.tryParseJson<EpData>(data) ?: return false
        if (!selected.subjectId.matches(Regex("[0-9]+")) || selected.se < 0 || selected.ep < 0)
            return false
        // Movie 0/0; serial EXACTLY the requested season/episode. Never retry a different episode.
        val se = if (selected.subjectType == 1) 0 else selected.se
        val ep = if (selected.subjectType == 1) 0 else selected.ep
        val api = mainUrl.trimEnd('/')
        // Mobile API remains metadata/caption authentication only; media is exclusively H5.
        var modernBearer = fetchModernPlaybackBearer()
        var normalBearer: String? = null
        var legacyBootstrapAttempted = false
        val choices = linkedMapOf<String, PlaybackChoice>()
        val captionIds = linkedMapOf<Pair<String, String>, String>()
        val subtitleUrls = linkedSetOf<String>()
        val validatedUrls = mutableMapOf<String, AdiDrakorMovieboxPlayback.Format>()
        val failedUrls = mutableSetOf<String>()

        suspend fun legacyBearer(): String? {
            if (!legacyBootstrapAttempted) {
                legacyBootstrapAttempted = true
                normalBearer =
                    try {
                        withTimeoutOrNull(8000) { getBearerToken() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
            }
            return normalBearer
        }
        fun webHeaders(host: String, referer: String): Map<String, String> =
            mutableMapOf(
                    "User-Agent" to AdiDrakorMovieboxPlayback.WEB_UA,
                    "Referer" to referer,
                    "Accept" to "application/json",
                    "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                    "x-request-lang" to "en",
                    "x-source" to "",
                )
                .apply {
                    // API bearer is never forwarded to third-party web aliases or media.
                    if (host == "https://h5-api.aoneroom.com" && modernBearer != null)
                        put("Authorization", "Bearer $modernBearer")
                }
        suspend fun metadata(subject: String): JSONObject? {
            metadataContext[subject]
                ?.takeIf { AdiDrakorMovieboxPlayback.text(it, "subjectId") == subject }
                ?.let {
                    return it
                }
            val url = "$api/wefeed-mobile-bff/subject-api/get?subjectId=$subject"
            var response = playbackRequest(url, modernPlaybackHeaders(url, modernBearer))
            if (response?.first in listOf(401, 441)) {
                modernBearer = fetchModernPlaybackBearer()
                response = playbackRequest(url, modernPlaybackHeaders(url, modernBearer))
            }
            AdiDrakorMovieboxPlayback.metadata(
                    response?.takeIf { it.first == 200 }?.second,
                    subject,
                )
                ?.let {
                    return it
                }
            // Preserve the established catalog session as a metadata fallback. Do not request
            // play-info.
            val bearer = legacyBearer()
            if (bearer != null) {
                val ts = System.currentTimeMillis().toString()
                val path = URI(url).let { it.rawPath + "?" + it.rawQuery }
                response =
                    playbackRequest(url, headersFor(ts, generateSignature("GET", path, ts), bearer))
                AdiDrakorMovieboxPlayback.metadata(
                        response?.takeIf { it.first == 200 }?.second,
                        subject,
                    )
                    ?.let {
                        return it
                    }
            }
            // Public H5 detail uses the same exact subject identity; never search/fuzzy-match
            // another title.
            val host = AdiDrakorMovieboxPlayback.webDomains.first()
            val webUrl = "$host/wefeed-h5api-bff/detail?subjectId=$subject"
            val web = playbackRequest(webUrl, webHeaders(host, "$host/"))
            return AdiDrakorMovieboxPlayback.metadata(
                web?.takeIf { it.first == 200 }?.second,
                subject,
            )
        }

        val labelCounts = mutableMapOf<String, Int>()
        val completedGroups = mutableSetOf<String>()
        suspend fun emit(choice: PlaybackChoice) {
            val stream = choice.stream
            val label = AdiDrakorMovieboxPlayback.label(stream, choice.format)
            val group = "$label|${stream.quality}"
            val number = (labelCounts[group] ?: 0) + 1
            labelCounts[group] = number
            val type =
                when (choice.format) {
                    AdiDrakorMovieboxPlayback.Format.DASH -> ExtractorLinkType.DASH
                    AdiDrakorMovieboxPlayback.Format.HLS -> ExtractorLinkType.M3U8
                    AdiDrakorMovieboxPlayback.Format.VIDEO -> ExtractorLinkType.VIDEO
                }
            callback(
                newExtractorLink(
                    name,
                    label + if (number > 1) " • Server $number" else "",
                    stream.url,
                    type,
                ) {
                    this.headers = choice.headers
                    this.referer = choice.referer
                    stream.quality?.let { this.quality = it }
                }
            )
        }
        suspend fun collectStreams(
            raw: String,
            subject: String,
            origin: String,
            referer: String,
            audio: String,
            originalLanguage: String,
        ): Int {
            val parsed =
                try {
                    AdiDrakorMovieboxPlayback.parse(
                        raw,
                        "H5",
                        subject,
                        se,
                        ep,
                        audio,
                        originalLanguage,
                    )
                } catch (_: Exception) {
                    emptyList()
                }
            // Policy resources are media candidates, not authorization to strip signed query
            // parameters.
            val candidates =
                parsed
                    .filter { it.category != AdiDrakorMovieboxPlayback.AudioCategory.UNSUPPORTED }
                    .flatMap { stream ->
                        val policy = extractPlaybackPolicyResource(stream.cookie)
                        if (
                            policy != null &&
                                AdiDrakorMovieboxPlayback.http(policy) &&
                                policy != stream.url
                        ) {
                            listOf(
                                stream.copy(url = policy, format = "DASH"),
                                stream.copy(
                                    cookie =
                                        if (
                                            AdiDrakorMovieboxPlayback.scopeHeaders(
                                                    mapOf("Cookie" to "scope-check"),
                                                    policy,
                                                    stream.url,
                                                )["Cookie"] != null
                                        )
                                            stream.cookie
                                        else null
                                ),
                            )
                        } else listOf(stream)
                    }
                    .filterNot { AdiDrakorMovieboxPlayback.notice(it.url) }
                    .distinctBy { AdiDrakorMovieboxPlayback.requestKey(it) }
                    .sortedWith(
                        compareBy<AdiDrakorMovieboxPlayback.Stream> {
                                it.codec.contains("hevc", true)
                            }
                            .thenBy { it.format.equals("DASH", true) }
                            .thenByDescending { it.quality ?: 0 }
                    )
            var count = 0
            // Sequential preferred candidates avoid CDN bursts. Cache identical transport
            // requests across qualities; skip redundant servers only after that group validates.
            for (stream in candidates) {
                val group =
                    "${stream.category}|${stream.quality}|${AdiDrakorMovieboxPlayback.codecName(stream.codec)}|${stream.format}"
                if (group in completedGroups) continue
                val headers =
                    AdiDrakorMovieboxPlayback.mediaHeaders(
                        stream,
                        origin,
                        referer,
                        AdiDrakorMovieboxPlayback.WEB_UA,
                    )
                val cacheKey = stream.url + "|" + headers.toSortedMap()
                val valid =
                    validatedUrls[cacheKey]?.let { AdiDrakorMovieboxPlayback.Validated(stream, it) }
                        ?: if (cacheKey in failedUrls) null
                        else
                            try {
                                AdiDrakorMovieboxPlayback.validateObserved(stream, headers, {}) { url, h, manifest ->
                                    mediaSession.sample(url, h, stream.url, manifest)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                if (valid == null) {
                    failedUrls += cacheKey
                    continue
                }
                validatedUrls[cacheKey] = valid.format
                completedGroups += group
                val captionKey = subject to stream.id
                if (stream.id.isNotBlank() && captionKey !in captionIds)
                    captionIds[captionKey] = stream.audio
                val key = AdiDrakorMovieboxPlayback.key(stream)
                if (key in choices) continue
                val choice = PlaybackChoice(stream, headers, referer, valid.format)
                choices[key] = choice
                count++
                // Publish each validated choice immediately, before slower optional
                // sources/captions.
                emit(choice)
            }
            Log.d(
                TAG,
                "[PLAYBACK-H5] subject=$subject parsed=${parsed.size} audioFiltered=${parsed.count { it.category == AdiDrakorMovieboxPlayback.AudioCategory.UNSUPPORTED }} candidates=${candidates.size} validated=$count se=$se ep=$ep",
            )
            onStage(
                if (candidates.isEmpty()) "H5_NO_STREAM"
                else if (count == 0) "H5_MEDIA_INVALID" else "PLAYBACK_SUCCESS"
            )
            return count
        }
        fun query(subject: String) = "subjectId=$subject&se=$se&ep=$ep"
        // Detail is read solely for H5 paths and explicitly declared audio subjects.
        val detail = metadata(selected.subjectId)
        val subjects = mutableListOf(Triple(selected.subjectId, "", detail))
        // Same-ID server metadata can identify the selected subject's original language.
        val originalLanguage =
            AdiDrakorMovieboxPlayback.audioLanguages(detail ?: JSONObject()).firstOrNull()
                ?: AdiDrakorMovieboxPlayback.objects(detail?.optJSONArray("dubs"))
                    .firstOrNull {
                        AdiDrakorMovieboxPlayback.text(it, "subjectId") == selected.subjectId
                    }
                    ?.let { AdiDrakorMovieboxPlayback.audioLanguages(it).firstOrNull() }
                    .orEmpty()
        AdiDrakorMovieboxPlayback.objects(detail?.optJSONArray("dubs")).forEach { dub ->
            val sid = AdiDrakorMovieboxPlayback.text(dub, "subjectId")
            val category = AdiDrakorMovieboxPlayback.dubCategory(dub)
            if (
                category != AdiDrakorMovieboxPlayback.AudioCategory.UNSUPPORTED &&
                    sid.matches(Regex("[0-9]+")) &&
                    subjects.none { it.first == sid }
            )
                subjects += Triple(sid, AdiDrakorMovieboxPlayback.audioLanguages(dub).first(), dub)
        }
        val orderedSubjects =
            subjects.sortedWith(
                compareBy<Triple<String, String, JSONObject?>> {
                        if (it.first == selected.subjectId) -1
                        else AdiDrakorMovieboxPlayback.audioOrder(it.second)
                    }
                    .thenBy { AdiDrakorMovieboxPlayback.audioName(it.second) }
            )
        var preferredHost: String? = null
        for ((subject, audio, initialDetail) in orderedSubjects) {
            val meta = if (subject == selected.subjectId) initialDetail else metadata(subject)
            val detail = meta ?: initialDetail ?: JSONObject()
            val path = AdiDrakorMovieboxPlayback.detailPath(detail, subject)
            Log.d(
                TAG,
                "[PLAYBACK-H5] subject=$subject metadata=${meta != null} detailPathAvailable=${path.isNotBlank()} se=$se ep=$ep",
            )
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val webDetail = AdiDrakorMovieboxPlayback.text(detail, "detailUrl")
            val fromDetail =
                if (AdiDrakorMovieboxPlayback.http(webDetail))
                    URI(webDetail).let { "${it.scheme}://${it.host}" }
                else null
            val hosts =
                (listOfNotNull(
                        preferredHost,
                        fromDetail?.takeIf { it in AdiDrakorMovieboxPlayback.webDomains },
                    ) + AdiDrakorMovieboxPlayback.webDomains)
                    .distinct()
            // A failed/empty alias must not hide H5 sources on later aliases, including dubbed
            // subjects.
            for (host in hosts) {
                val url =
                    "$host/wefeed-h5api-bff/subject/play?${query(subject)}&detailPath=$encodedPath" +
                        "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                val referer =
                    "$host/movies/$encodedPath?id=$subject&type=/movie/detail&detailSe=&detailEp=&lang=en"
                val response = playbackRequest(url, webHeaders(host, referer))
                if (response == null) {
                    continue
                }
                if (
                    response.first == 200 &&
                        collectStreams(
                            response.second,
                            subject,
                            host,
                            referer,
                            audio,
                            if (subject == selected.subjectId) originalLanguage else "",
                        ) > 0
                ) {
                    preferredHost = host
                    break
                }
            }
        }
        // Global, deterministic labels: one audio name, one media format, one codec.
        // CloudStream adds the quality itself; do not repeat "1080p" in the source name.
        val orderedChoices =
            choices.values
                .filter {
                    it.stream.category != AdiDrakorMovieboxPlayback.AudioCategory.UNSUPPORTED
                }
                .sortedWith(
                    compareBy<PlaybackChoice> { it.stream.category.ordinal }
                        .thenByDescending { it.stream.quality ?: 0 }
                        .thenBy { AdiDrakorMovieboxPlayback.codecOrder(it.stream.codec) }
                        .thenBy { it.format.ordinal }
                )
        // Each validated stream/resource ID owns its captions; the selected serial index is
        // unchanged.
        for ((identity, audio) in captionIds) {
            val (subject, stream) = identity
            val params =
                listOf(
                    "get-stream-captions" to
                        "streamId=${URLEncoder.encode(stream,"UTF-8")}&subjectId=$subject",
                    "get-ext-captions" to
                        "episode=0&resourceId=${URLEncoder.encode(stream,"UTF-8")}&subjectId=$subject",
                )
            for ((endpoint, q) in params) {
                val url = "$api/wefeed-mobile-bff/subject-api/$endpoint?$q"
                var response = playbackRequest(url, modernPlaybackHeaders(url, modernBearer))
                if (response == null || response.first != 200) {
                    val bearer = legacyBearer()
                    if (bearer != null) {
                        val ts = System.currentTimeMillis().toString()
                        val pathQuery = URI(url).let { it.rawPath + "?" + it.rawQuery }
                        response =
                            playbackRequest(
                                url,
                                headersFor(ts, generateSignature("GET", pathQuery, ts), bearer),
                            )
                    }
                }
                if (response == null || response.first != 200) continue
                try {
                    AdiDrakorMovieboxPlayback.captionEntries(response.second).forEach {
                        (language, url) ->
                        if (subtitleUrls.add(url))
                            subtitleCallback(
                                newSubtitleFile(
                                    language +
                                        audio
                                            .takeIf { it.isNotBlank() }
                                            ?.let {
                                                " (${AdiDrakorMovieboxPlayback.audioName(it)})"
                                            }
                                            .orEmpty(),
                                    url,
                                )
                            )
                    }
                } catch (_: Exception) {
                    Log.d(TAG, "[SUBTITLE] invalid $endpoint response")
                }
            }
        }
        if (choices.isEmpty())
            Log.e(
                TAG,
                "[PLAYBACK-H5] no validated resource subject=${selected.subjectId} se=$se ep=$ep",
            )
        return orderedChoices.isNotEmpty()
    }

    suspend fun resolve(
        titles: List<String>,
        year: Int?,
        type: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        tmdbId: String? = null,
        imdbId: String? = null,
        overview: String? = null,
        runtimeMinutes: Int? = null,
        castNames: List<String> = emptyList(),
    ): Boolean {
        val started = System.nanoTime()
        fun report(stage: String, reason: String, candidates: Int = 0, verified: Int = 0) {
            Log.d(
                TAG,
                "[FINDER] stage=$stage reason=$reason candidates=$candidates verified=$verified elapsedMs=${(System.nanoTime()-started)/1_000_000} type=$type se=$season ep=$episode",
            )
        }
        val completed =
            withTimeoutOrNull(90_000) {
                try {
                    coroutineScope {
                        var request =
                            AdiDrakorMovieboxMapping.Request(
                                titles,
                                year,
                                type,
                                season,
                                episode,
                                tmdbId,
                                imdbId,
                                overview,
                                runtimeMinutes,
                                castNames,
                            )
                        if (!AdiDrakorMovieboxMapping.valid(request)) {
                            report("identity", "NO_CANDIDATE")
                            return@coroutineScope false
                        }
                        val bearer = async {
                            withTimeoutOrNull(2500) {
                                    try {
                                        getBearerToken()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                                .also { if (it == null) report("auth", "AUTH_UNAVAILABLE") }
                        }
                        val catalog = "https://moviebox.ph"
                        val catalogRows =
                            java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
                        val webHeaders =
                            mapOf(
                                "User-Agent" to AdiDrakorMovieboxPlayback.WEB_UA,
                                "Referer" to "$catalog/",
                            )
                        suspend fun page(url: String): String? =
                            withTimeoutOrNull(6000) {
                                playbackRequest(url, webHeaders)?.takeIf { it.first == 200 }?.second
                            }
                        val primaryPublic = async {
                            val title = titles.first()
                            val key = "search:$type:${tmdbId.orEmpty()}:$title"
                            catalogCache.get(key)
                                ?: page(
                                        "$catalog/web/searchResult?keyword=${URLEncoder.encode(title,"UTF-8")}"
                                    )
                                    ?.let { AdiDrakorMovieboxMapping.catalogSearch(it) }
                                    ?.also { catalogCache.put(key, it) }
                        }
                        suspend fun native(id: String, endpoint: String): String? {
                            val token = bearer.await() ?: return null
                            val path = "/wefeed-mobile-bff/subject-api/$endpoint?subjectId=$id"
                            val ts = System.currentTimeMillis().toString()
                            return withTimeoutOrNull(5000) {
                                playbackRequest(
                                        "$mainUrl$path",
                                        headersFor(ts, generateSignature("GET", path, ts), token),
                                    )
                                    ?.takeIf { it.first == 200 }
                                    ?.second
                            }
                        }
                        var discoveryBudget = 35_000L
                        suspend fun discover(
                            block: suspend () -> AdiDrakorMovieboxMapping.Result
                        ): AdiDrakorMovieboxMapping.Result? {
                            if (discoveryBudget <= 0) return null
                            val before = System.nanoTime()
                            return try {
                                withTimeoutOrNull(discoveryBudget) { block() }
                            } finally {
                                discoveryBudget -= (System.nanoTime() - before) / 1_000_000
                            }
                        }
                        var rediscover:
                            (suspend (Set<String>) -> AdiDrakorMovieboxMapping.Result)? =
                            null
                        val discovery = discover {
                            // Old saved playback payloads may lack synopsis/cast. Hydrate only the
                            // selected TMDB ID, never the first search result or an inferred work.
                            if (tmdbId != null && tmdbApiKey != null) {
                                val kind = if (type == 1) "movie" else "tv"
                                if (request.tmdbId != null) {
                                    val metadataKey = "tmdb:$kind:$tmdbId"
                                    val raw =
                                        catalogCache.get(metadataKey)
                                            ?: withTimeoutOrNull(5000) {
                                                try {
                                                    app.get(
                                                            "https://api.themoviedb.org/3/$kind/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,external_ids,alternative_titles,translations&language=en-US",
                                                            timeout = 5,
                                                        )
                                                        .takeIf { it.code == 200 }
                                                        ?.text
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    null
                                                }
                                            }
                                    raw?.let { catalogCache.put(metadataKey, it) }
                                    val meta =
                                        try {
                                            JSONObject(raw ?: "{}")
                                        } catch (_: Exception) {
                                            JSONObject()
                                        }
                                    request = AdiDrakorMovieboxMapping.hydrate(request, meta)
                                }
                                val q = URLEncoder.encode(request.titles.first(), "UTF-8")
                                val yearKey = if (type == 1) "year" else "first_air_date_year"
                                val yearQuery = request.year?.let { "&$yearKey=$it" }.orEmpty()
                                val raw =
                                    withTimeoutOrNull(5000) {
                                        try {
                                            app.get(
                                                    "https://api.themoviedb.org/3/search/$kind?api_key=$tmdbApiKey&query=$q$yearQuery&language=en-US",
                                                    timeout = 5,
                                                )
                                                .takeIf { it.code == 200 }
                                                ?.text
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                request =
                                    request.copy(
                                        works = AdiDrakorMovieboxMapping.tmdbWorks(raw, request)
                                    )
                            }
                            val details = java.util.concurrent.ConcurrentHashMap<String, String>()
                            val detailAttempts =
                                java.util.concurrent.ConcurrentHashMap<String, Int>()
                            val searches = mutableMapOf<Pair<String, Int>, String?>()
                            val seasonReads = mutableMapOf<String, String?>()
                            suspend fun read(id: String): String? {
                                details[id]?.let {
                                    return it
                                }
                                if ((detailAttempts[id] ?: 0) >= 2) return null
                                detailAttempts[id] = (detailAttempts[id] ?: 0) + 1
                                return coroutineScope {
                                    val mobile = async { native(id, "get") }
                                    val row = catalogRows[id]
                                    val path =
                                        row?.let { AdiDrakorMovieboxPlayback.detailPath(it, id) }
                                            .orEmpty()
                                    // Native and public detail are alternatives for the SAME
                                    // subject.
                                    val web =
                                        if (path.matches(Regex("[A-Za-z0-9_-]{1,220}"))) {
                                            val key = "detail:$id:$path"
                                            val cached = catalogCache.get(key)
                                            if (cached != null) JSONObject(cached)
                                            else {
                                                val html =
                                                    page(
                                                        "$catalog/moviedetail/$path?id=$id&type=/movie/detail"
                                                    )
                                                html
                                                    ?.let {
                                                        AdiDrakorMovieboxMapping.catalogPage(it)
                                                            .firstOrNull { item ->
                                                                AdiDrakorMovieboxPlayback.text(
                                                                    item,
                                                                    "subjectId",
                                                                ) == id
                                                            }
                                                    }
                                                    ?.also { catalogCache.put(key, it.toString()) }
                                            }
                                        } else null
                                    val native =
                                        AdiDrakorMovieboxPlayback.metadata(mobile.await(), id)
                                    val combined =
                                        if (web != null)
                                            AdiDrakorMovieboxMapping.mergeMetadata(native, web)
                                        else native
                                    combined?.let { meta ->
                                        AdiDrakorMovieboxPlayback.objects(meta.optJSONArray("dubs"))
                                            .forEach { dub ->
                                                val did =
                                                    AdiDrakorMovieboxPlayback.text(dub, "subjectId")
                                                if (did.matches(Regex("[0-9]+")))
                                                    catalogRows.putIfAbsent(did, dub)
                                            }
                                        JSONObject()
                                            .put("code", 0)
                                            .put("data", meta)
                                            .toString()
                                            .also { details[id] = it }
                                    }
                                }
                            }
                            suspend fun search(title: String, number: Int): String? {
                                val key = title to number
                                if (searches.containsKey(key)) return searches[key]
                                return coroutineScope {
                                    val native = async {
                                        val token = bearer.await()
                                        if (token == null) null
                                        else
                                            withTimeoutOrNull(5000) {
                                                val body =
                                                    JSONObject()
                                                        .put("page", number)
                                                        .put("perPage", 20)
                                                        .put("keyword", title)
                                                        .put("tabId", "")
                                                        .toString()
                                                postSigned(
                                                    "/wefeed-mobile-bff/subject-api/search/v2",
                                                    body,
                                                    token,
                                                )
                                            }
                                    }
                                    val public =
                                        if (number == 1) {
                                            val key = "search:$type:${tmdbId.orEmpty()}:$title"
                                            if (title == titles.first()) primaryPublic.await()
                                            else
                                                catalogCache.get(key)
                                                    ?: page(
                                                            "$catalog/web/searchResult?keyword=${URLEncoder.encode(title,"UTF-8")}"
                                                        )
                                                        ?.let {
                                                            AdiDrakorMovieboxMapping.catalogSearch(
                                                                it
                                                            )
                                                        }
                                                        ?.also { catalogCache.put(key, it) }
                                        } else null
                                    val web = AdiDrakorMovieboxMapping.searchObjects(public)
                                    web.forEach {
                                        catalogRows[
                                            AdiDrakorMovieboxPlayback.text(it, "subjectId")] = it
                                    }
                                    val raw = native.await()
                                    val rows =
                                        (AdiDrakorMovieboxMapping.searchObjects(raw) + web)
                                            .groupBy {
                                                AdiDrakorMovieboxPlayback.text(it, "subjectId")
                                            }
                                            .values
                                            .map { group ->
                                                group.reduce { a, b ->
                                                    AdiDrakorMovieboxMapping.mergeMetadata(a, b)
                                                }
                                            }
                                    val result =
                                        if (public == null && rows.isEmpty()) raw
                                        else
                                            JSONObject()
                                                .put("code", 0)
                                                .put(
                                                    "data",
                                                    JSONObject()
                                                        .put("items", org.json.JSONArray(rows))
                                                        .put(
                                                            "pager",
                                                            JSONObject()
                                                                .put(
                                                                    "hasMore",
                                                                    AdiDrakorMovieboxMapping
                                                                        .hasMore(raw),
                                                                ),
                                                        ),
                                                )
                                                .toString()
                                    searches[key] = result
                                    result
                                }
                            }
                            rediscover = { excluded ->
                                AdiDrakorMovieboxMapping.resolve(
                                    request.copy(excludedSubjects = excluded),
                                    { search(it, 1) },
                                    { read(it) },
                                    { id ->
                                        if (!seasonReads.containsKey(id))
                                            seasonReads[id] = native(id, "season-info")
                                        seasonReads[id]
                                    },
                                    { title, number -> search(title, number) },
                                )
                            }
                            rediscover!!(emptySet())
                        }
                        if (discovery == null) {
                            report("discovery", "SEARCH_TIMEOUT")
                            return@coroutineScope false
                        }
                        val subject = discovery.subject
                        if (subject == null) {
                            val reason =
                                when (discovery.reason) {
                                    "SEARCH_API_FAILED",
                                    "SEARCH_API_INVALID",
                                    "SEARCH_API_REJECTED" -> "TITLE_DISCOVERY_FAILED"
                                    "SUBJECT_NOT_FOUND_OR_IDENTITY_MISMATCH" -> "NO_CANDIDATE"
                                    "AMBIGUOUS_SUBJECT",
                                    "AMBIGUOUS_TMDB_WORK",
                                    "TMDB_IDENTITY_UNVERIFIED" -> "IDENTITY_AMBIGUOUS"
                                    "SUBJECT_DETAIL_UNAVAILABLE" -> "DETAIL_UNAVAILABLE"
                                    else -> discovery.reason
                                }
                            report("identity", reason, discovery.candidates, discovery.verified)
                            return@coroutineScope false
                        }
                        report("identity", "VERIFIED", discovery.candidates, discovery.verified)
                        val mediaSession = AdiDrakorMovieboxMedia()
                        var current: AdiDrakorMovieboxMapping.Result = discovery
                        val attempted = mutableSetOf<String>()
                        while (attempted.size < 3) {
                            val editions =
                                current.editions
                                    .ifEmpty {
                                        listOfNotNull(
                                            current.metadata?.let {
                                                AdiDrakorMovieboxMapping.Edition(
                                                    current.subject ?: subject,
                                                    it,
                                                )
                                            }
                                        )
                                    }
                                    .filter { it.subject.id !in attempted }
                                    .take(3 - attempted.size)
                            if (editions.isEmpty()) break
                            for (edition in editions) {
                                attempted += edition.subject.id
                                val selected =
                                    EpData(
                                        edition.subject.id,
                                        if (type == 1) 0 else season!!,
                                        if (type == 1) 0 else episode!!,
                                        type,
                                    )
                                val context =
                                    mapOf(
                                        edition.subject.id to
                                            AdiDrakorMovieboxMapping.playbackMetadata(
                                                edition.metadata
                                            )
                                    )
                                var h5Reason = "H5_NO_STREAM"
                                val played =
                                    loadLinks(
                                        with(AppUtils) { selected.toJson() },
                                        false,
                                        subtitleCallback,
                                        callback,
                                        context,
                                        mediaSession,
                                    ) { stage ->
                                        if (
                                            stage == "H5_MEDIA_INVALID" ||
                                                h5Reason != "H5_MEDIA_INVALID"
                                        )
                                            h5Reason = stage
                                    }
                                report(
                                    "h5",
                                    if (played) "PLAYBACK_SUCCESS" else h5Reason,
                                    current.candidates,
                                    current.verified,
                                )
                                if (played) return@coroutineScope true
                            }
                            // A verified identity without usable media is not a completed search.
                            // Continue remaining aliases/pages with request-local IO deduplication;
                            // every later edition must independently pass the SAME identity gate.
                            if (attempted.size >= 3) break
                            val next = discover { rediscover!!(attempted.toSet()) } ?: break
                            if (next.subject == null) break
                            current = next
                        }
                        false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("discovery", "DETAIL_UNAVAILABLE")
                    false
                }
            }
        if (completed == null) report("h5", "SEARCH_TIMEOUT")
        return completed ?: false
    }
}

/** Request-local pacing/cache. Never shared with another provider or retained after resolution. */
internal class AdiDrakorMovieboxMedia(
    private val client: OkHttpClient = AdiDrakorMovieboxPlayback.client,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wall: () -> Long = { System.currentTimeMillis() },
) {
    data class Event(
        val stage: String,
        val reason: String,
        val host: String = "",
        val status: Int? = null,
        val mime: String = "",
        val redirect: Boolean = false,
        val attempt: Int = 0,
        val elapsedMs: Long = 0,
        val ranged: Boolean = false,
        val retryAfterMs: Long? = null,
        val protocol: String = "",
    )

    private data class Raw(
        val code: Int,
        val mime: String,
        val bytes: ByteArray,
        val truncated: Boolean,
        val location: String?,
        val retryAfter: String?,
        val protocol: String,
    )

    private val gate = Mutex()
    private val cooldown = mutableMapOf<String, Long>()
    private val cache = linkedMapOf<String, Pair<Long, AdiDrakorMovieboxPlayback.Sample>>()

    fun retryMillis(value: String?): Long? {
        if (value == null) return null
        value
            .trim()
            .toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.let {
                return it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
            }
        return try {
            (java.text
                    .SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    .apply { isLenient = false }
                    .parse(value)!!
                    .time - wall())
                .coerceAtLeast(0)
        } catch (_: Exception) {
            null
        }
    }

    private fun host(url: String): String =
        try {
            URI(url).host.orEmpty()
        } catch (_: Exception) {
            ""
        }

    private fun origin(url: String): String =
        try {
            URI(url).let { "${it.scheme}://${it.host}:${it.port}" }
        } catch (_: Exception) {
            ""
        }

    private fun reason(code: Int): String =
        when (code) {
            429 -> "MEDIA_HTTP_429"
            426 -> "MEDIA_HTTP_426"
            401,
            403 -> "MEDIA_AUTH_FAILED"
            else -> if (code in 200..299) "MEDIA_RESPONSE" else "MEDIA_HTTP_ERROR"
        }

    suspend fun sample(
        url: String,
        headers: Map<String, String>,
        scope: String,
        manifest: Boolean,
        observe: (Event) -> Unit = {},
    ): AdiDrakorMovieboxPlayback.Sample? =
        gate.withLock {
            val expiry =
                try {
                    URI(url)
                        .rawQuery
                        .orEmpty()
                        .split('&')
                        .firstOrNull {
                            it.substringBefore('=').lowercase() in
                                setOf("expires", "cloudfront-expires")
                        }
                        ?.substringAfter('=')
                        ?.toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            if (expiry != null && expiry <= wall() / 1000) {
                observe(Event("MEDIA-PREFLIGHT", "MEDIA_AUTH_FAILED", host(url), 403))
                return@withLock null
            }
            // Full signed identity and scoped headers are used only as an in-memory key.
            val key = url + "|" + manifest + "|" + headers.toSortedMap()
            cache[key]
                ?.takeIf { clock() - it.first in 0 until 5000 }
                ?.let {
                    observe(Event("MEDIA-PREFLIGHT", "MEDIA_CACHE_HIT", host(url)))
                    return@withLock it.second
                }
            var target = url
            var ranged = !manifest
            var rangeFallback = false
            var redirects = 0
            var attempts = 0
            while (attempts < 5) {
                if (
                    !AdiDrakorMovieboxPlayback.http(target) ||
                        AdiDrakorMovieboxPlayback.notice(target)
                ) {
                    observe(Event("MEDIA-REDIRECT", "MEDIA_REDIRECT_REJECTED", host(target)))
                    return@withLock null
                }
                val until = cooldown[origin(target)] ?: 0
                if (until > clock()) {
                    observe(
                        Event(
                            "MEDIA-PREFLIGHT",
                            "MEDIA_HTTP_429",
                            host(target),
                            429,
                            attempt = 0,
                            retryAfterMs = until - clock(),
                        )
                    )
                    return@withLock null
                }
                val h =
                    AdiDrakorMovieboxPlayback.scopeHeaders(headers, scope, target)
                        .filterKeys {
                            !it.equals("Authorization", true) &&
                                !it.equals("Proxy-Authorization", true) &&
                                !it.equals("Range", true)
                        }
                        .toMutableMap()
                h["Accept-Encoding"] = "identity"
                if (ranged) h["Range"] = "bytes=0-4095"
                val started = clock()
                attempts++
                val raw =
                    read(target, h, if (manifest) 262144 else 4096) { error ->
                        observe(
                            Event(
                                "MEDIA-PREFLIGHT",
                                if (error is java.io.InterruptedIOException) "MEDIA_TIMEOUT"
                                else "MEDIA_TRANSPORT_FAILED",
                                host(target),
                                attempt = attempts,
                                elapsedMs = clock() - started,
                                ranged = ranged,
                            )
                        )
                    } ?: return@withLock null
                val retry = retryMillis(raw.retryAfter)
                observe(
                    Event(
                        "MEDIA-PREFLIGHT",
                        reason(raw.code),
                        host(target),
                        raw.code,
                        raw.mime,
                        raw.code in listOf(301, 302, 303, 307, 308),
                        attempts,
                        clock() - started,
                        ranged,
                        retry,
                        raw.protocol,
                    )
                )
                if (raw.code == 429) {
                    val wait = (retry ?: 5000L).coerceAtLeast(1000)
                    cooldown[origin(target)] = clock() + wait.coerceAtMost(Long.MAX_VALUE - clock())
                    return@withLock AdiDrakorMovieboxPlayback.Sample(
                        target,
                        raw.code,
                        raw.mime,
                        raw.bytes,
                        raw.truncated,
                    )
                }
                // Only a range-specific rejection authorizes one bounded normal GET.
                // Never use this to retry authentication, rate limiting or protocol rejection.
                if (raw.code == 416 && ranged && !rangeFallback) {
                    ranged = false
                    rangeFallback = true
                    continue
                }
                if (raw.code in listOf(301, 302, 303, 307, 308)) {
                    val next = raw.location?.let { AdiDrakorMovieboxPlayback.resolve(target, it) }
                    if (
                        ++redirects > 3 ||
                            next == null ||
                            (URI(url).scheme == "https" && URI(next).scheme != "https")
                    ) {
                        observe(
                            Event(
                                "MEDIA-REDIRECT",
                                "MEDIA_REDIRECT_REJECTED",
                                host(target),
                                raw.code,
                                redirect = true,
                                attempt = attempts,
                            )
                        )
                        return@withLock null
                    }
                    observe(
                        Event(
                            "MEDIA-REDIRECT",
                            "MEDIA_REDIRECT_SCOPED",
                            host(next),
                            raw.code,
                            redirect = true,
                            attempt = attempts,
                        )
                    )
                    target = next
                    continue
                }
                val result =
                    AdiDrakorMovieboxPlayback.Sample(
                        target,
                        raw.code,
                        raw.mime,
                        raw.bytes,
                        raw.truncated,
                    )
                if (raw.code in 200..299) {
                    if (cache.size >= 16) cache.remove(cache.keys.first())
                    cache[key] = clock() to result
                }
                return@withLock result
            }
            null
        }

    private suspend fun read(
        url: String,
        headers: Map<String, String>,
        limit: Int,
        failure: (IOException) -> Unit,
    ): Raw? = suspendCancellableCoroutine { cont ->
        val request =
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        failure(e)
                        cont.resume(null)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val raw =
                            response.use { r ->
                                val redirected = r.code in listOf(301, 302, 303, 307, 308)
                                val out = java.io.ByteArrayOutputStream()
                                if (!redirected)
                                    r.body.byteStream().use { input ->
                                        val buffer = ByteArray(4096)
                                        while (cont.isActive && out.size() <= limit) {
                                            val n =
                                                input.read(
                                                    buffer,
                                                    0,
                                                    minOf(buffer.size, limit + 1 - out.size()),
                                                )
                                            if (n < 0) break
                                            out.write(buffer, 0, n)
                                        }
                                    }
                                val total =
                                    r.header("Content-Range")
                                        ?.substringAfterLast('/')
                                        ?.toLongOrNull()
                                val partial = r.code == 206 && (total == null || total > out.size())
                                Raw(
                                    r.code,
                                    r.header("Content-Type")
                                        .orEmpty()
                                        .substringBefore(';')
                                        .takeIf {
                                            it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))
                                        }
                                        .orEmpty(),
                                    out.toByteArray().take(limit).toByteArray(),
                                    out.size() > limit || partial,
                                    r.header("Location"),
                                    r.header("Retry-After"),
                                    r.protocol.toString(),
                                )
                            }
                        if (cont.isActive) cont.resume(raw)
                    } catch (e: IOException) {
                        if (cont.isActive) {
                            failure(e)
                            cont.resume(null)
                        }
                    }
                }
            }
        )
    }
}
