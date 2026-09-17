package com.Adicinemax21

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

internal class Adicinemax21VidSrcResolver(
    private val applicationContext: Context,
    private val sourceName: String,
    private val logMarkerCallback: (String) -> Unit,
    private val safeHostCallback: (String?) -> String,
    private val resolveHttpUrlCallback: (String, String?) -> String?,
    private val getIframeUrlsCallback: (Document, String) -> List<String>,
    private val sha256PrefixCallback: (String) -> String
) {

    companion object {
        private const val MAX_RESOLVER_DEPTH = 6
        private const val MAX_RESOLVER_PAGES = 12
        private const val MEDIA_PROBE_LIMIT = 131_072
        private const val WASM_TIMEOUT_MS = 15_000L
    }

    private fun logMarker(message: String) =
        logMarkerCallback(message)

    private fun safeHost(url: String?) =
        safeHostCallback(url)

    private fun resolveHttpUrl(
        baseUrl: String,
        value: String?
    ) = resolveHttpUrlCallback(baseUrl, value)

    private fun getIframeUrls(
        document: Document,
        baseUrl: String
    ) = getIframeUrlsCallback(document, baseUrl)

    private fun sha256Prefix(value: String) =
        sha256PrefixCallback(value)

    private data class GateApiResponse(
        @param:JsonProperty("src")
        val src: String? = null
    )

    private data class StreamApiResponse(
        @param:JsonProperty("data")
        val data: StreamApiData? = null,

        @param:JsonProperty("vs")
        val vs: WasmEnvelope? = null
    )

    private data class StreamApiData(
        @param:JsonProperty("stream_urls")
        val streamUrls: Any? = null
    )

    private data class WasmEnvelope(
        @param:JsonProperty("wasm_url")
        val wasmUrl: String? = null,

        @param:JsonProperty("wasm")
        val inlineWasm: String? = null
    )

    private data class PlayerRuntimeConfig(
        val playerUrl: String,
        val exactApi: String?,
        val streamBase: String?,
        val metaApi: String?,
        val season: String,
        val episode: String
    )

    private data class MediaCandidate(
        val url: String,
        val type: ExtractorLinkType,
        val referer: String,
        val headers: Map<String, String>
    )

    private data class MediaProbe(
        val code: Int,
        val contentType: String,
        val bytes: ByteArray
    )

    private data class ResolverBudget(
        var pages: Int = 0
    )

    private class WasmBridge(
        private val successCallback: (String) -> Unit,
        private val failureCallback: (String) -> Unit
    ) {
        @JavascriptInterface
        fun success(value: String) {
            successCallback(value)
        }

        @JavascriptInterface
        fun failure(value: String) {
            failureCallback(value)
        }
    }

    suspend fun resolveValidatedPage(
        pageUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return resolveValidatedPage(
            pageUrl = pageUrl,
            referer = referer,
            depth = 0,
            visited = mutableSetOf(),
            budget = ResolverBudget(),
            callback = callback
        )
    }

    private fun decodeJsValue(value: String): String {
        var output =
            value
                .replace("\\/", "/")
                .replace("\\x26", "&", true)
                .replace("&amp;", "&")

        output =
            Regex(
                """\\u([0-9a-fA-F]{4})"""
            ).replace(output) { match ->
                match
                    .groupValues[1]
                    .toInt(16)
                    .toChar()
                    .toString()
            }

        return output
    }

    private fun getConfigString(
        source: String,
        key: String
    ): String? {

        val escapedKey = Regex.escape(key)

        val patterns = listOf(
            Regex(
                """(?:["']$escapedKey["']|\b$escapedKey\b)\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:CONFIG|CFG)\.$escapedKey\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
        )

        return patterns
            .asSequence()
            .mapNotNull { pattern ->
                pattern
                    .find(source)
                    ?.groupValues
                    ?.getOrNull(1)
            }
            .map(::decodeJsValue)
            .firstOrNull {
                it.isNotBlank()
            }
    }

    private fun getConfigScalar(
        source: String,
        key: String
    ): String? {

        getConfigString(
            source,
            key
        )?.let {
            return it
        }

        val escapedKey = Regex.escape(key)

        return Regex(
            """(?:["']$escapedKey["']|\b$escapedKey\b)\s*:\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun encodeComponent(value: String): String {
        return URLEncoder
            .encode(value, "UTF-8")
            .replace("+", "%20")
    }

    private fun originOf(url: String): String? {
        return try {
            val parsed = URI(url)
            val authority =
                parsed.rawAuthority
                    ?: return null

            "${parsed.scheme}://$authority"
        } catch (_: Exception) {
            null
        }
    }

    private fun isHtmlPayload(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return false
        }

        val prefix =
            bytes
                .copyOfRange(
                    0,
                    minOf(bytes.size, 4096)
                )
                .toString(Charsets.UTF_8)
                .trimStart()
                .lowercase()

        return prefix.startsWith("<!doctype") ||
            prefix.startsWith("<html") ||
            prefix.contains("<title>access denied") ||
            prefix.contains("cf-chl-") ||
            prefix.contains("cloudflare ray id")
    }

    private fun isMpegTs(bytes: ByteArray): Boolean {
        if (bytes.size < 188 * 3) {
            return false
        }

        return bytes[0] == 0x47.toByte() &&
            bytes[188] == 0x47.toByte() &&
            bytes[376] == 0x47.toByte()
    }

    private fun isMp4(bytes: ByteArray): Boolean {
        return bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }

    private fun isM3u8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || isHtmlPayload(bytes)) {
            return false
        }

        val text =
            bytes
                .toString(Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .trimStart()

        return text.startsWith("#EXTM3U") &&
            (
                text.contains("#EXT-X-STREAM-INF") ||
                    text.contains("#EXTINF") ||
                    text.contains("#EXT-X-PART")
                )
    }

    private fun readLimited(
        input: java.io.InputStream,
        limit: Int
    ): ByteArray {

        val buffer = ByteArray(limit)
        var total = 0

        while (total < limit) {
            val count =
                input.read(
                    buffer,
                    total,
                    limit - total
                )

            if (count <= 0) {
                break
            }

            total += count
        }

        return buffer.copyOf(total)
    }

    private suspend fun probeMedia(
        url: String,
        referer: String?,
        requestHeaders: Map<String, String>
    ): MediaProbe? {

        return try {
            val response =
                app.get(
                    url = url,
                    headers =
                        requestHeaders +
                            mapOf(
                                "Range" to
                                    "bytes=0-${MEDIA_PROBE_LIMIT - 1}"
                            ),
                    referer = referer
                )

            val body = response.body

            val bytes =
                body
                    .byteStream()
                    .use { input ->
                        readLimited(
                            input,
                            MEDIA_PROBE_LIMIT
                        )
                    }

            body.close()

            MediaProbe(
                code = response.okhttpResponse.code,
                contentType =
                    response
                        .okhttpResponse
                        .header("Content-Type")
                        .orEmpty(),
                bytes = bytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            logMarker(
                "HLS_RESOLVE|STAGE=probe|" +
                    "HOST=${safeHost(url)}|" +
                    "RESULT=fail|" +
                    "ERROR=${error.javaClass.simpleName}"
            )

            null
        }
    }

    private suspend fun validateMediaCandidate(
        url: String,
        referer: String?,
        headers: Map<String, String>
    ): MediaCandidate? {

        val response =
            probeMedia(
                url,
                referer,
                headers
            ) ?: return null

        val success =
            response.code == 200 ||
                response.code == 206

        val type =
            when {
                success &&
                    !isHtmlPayload(response.bytes) &&
                    isM3u8(response.bytes) ->
                    ExtractorLinkType.M3U8

                success &&
                    !isHtmlPayload(response.bytes) &&
                    (
                        isMp4(response.bytes) ||
                            isMpegTs(response.bytes)
                        ) ->
                    ExtractorLinkType.VIDEO

                else ->
                    null
            }

        logMarker(
            "HLS_RESOLVE|HOST=${safeHost(url)}|" +
                "HTTP=${response.code}|" +
                "CONTENT_TYPE=${response.contentType.take(80)}|" +
                "PLAYLIST=${if (type == ExtractorLinkType.M3U8) "yes" else "no"}|" +
                "RESULT=${if (type != null) "pass" else "fail"}"
        )

        return type?.let {
            MediaCandidate(
                url = url,
                type = it,
                referer = referer.orEmpty(),
                headers = headers
            )
        }
    }

    private fun parseToken(body: String): String? {
        val clean = body.trim()

        if (
            clean.isBlank() ||
            clean.startsWith("<") ||
            clean.length > 8192
        ) {
            return null
        }

        if (
            clean.startsWith("\"") &&
            clean.endsWith("\"") &&
            clean.length > 2
        ) {
            return clean
                .substring(1, clean.length - 1)
                .replace("\\\"", "\"")
                .takeIf { it.isNotBlank() }
        }

        if (clean.startsWith("{")) {
            return Regex(
                """["'](?:token|data|string|result)["']\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(clean)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
        }

        return clean
    }

    private suspend fun generateToken(
        mediaUrl: String,
        playerUrl: String
    ): String? {

        val origin =
            originOf(mediaUrl)
                ?: return null

        val endpoint = "$origin/generate.php"

        return try {
            val response =
                app.get(
                    url = endpoint,
                    referer = playerUrl
                )

            val token =
                if (
                    response.okhttpResponse.code in
                    200..299
                ) {
                    parseToken(response.text)
                } else {
                    null
                }

            logMarker(
                "TOKEN_FLOW|TOKEN_GENERATED|" +
                    "STATUS=${if (token != null) "pass" else "fail"}|" +
                    "TOKEN_HOST=${safeHost(mediaUrl)}|" +
                    "HTTP=${response.okhttpResponse.code}"
            )

            token
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            logMarker(
                "TOKEN_FLOW|TOKEN_GENERATED|" +
                    "STATUS=fail|" +
                    "TOKEN_HOST=${safeHost(mediaUrl)}|" +
                    "ERROR=${error.javaClass.simpleName}"
            )

            null
        }
    }

    private fun applyToken(
        mediaUrl: String,
        token: String
    ): Pair<String, String> {

        return if (mediaUrl.contains("__TOKEN__")) {
            mediaUrl.replace(
                "__TOKEN__",
                token
            ) to "placeholder-replace"
        } else {
            mediaUrl +
                if (mediaUrl.contains("?")) {
                    "&token=$token"
                } else {
                    "?token=$token"
                } to "query-append"
        }
    }

    private fun queryValue(
        url: String,
        key: String
    ): String? {
        return try {
            URI(url)
                .rawQuery
                ?.split("&")
                ?.firstOrNull { part ->
                    part.substringBefore("=") == key
                }
                ?.substringAfter(
                    delimiter = "=",
                    missingDelimiterValue = ""
                )
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasWasmMagic(bytes: ByteArray): Boolean {
        return bytes.size >= 8 &&
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x61.toByte() &&
            bytes[2] == 0x73.toByte() &&
            bytes[3] == 0x6d.toByte() &&
            bytes[4] == 0x01.toByte() &&
            bytes[5] == 0x00.toByte() &&
            bytes[6] == 0x00.toByte() &&
            bytes[7] == 0x00.toByte()
    }

    private suspend fun loadWasmBytes(
        envelope: WasmEnvelope,
        sourceApiUrl: String,
        playerUrl: String
    ): ByteArray? {

        val inline =
            envelope
                .inlineWasm
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        if (inline != null) {
            val decoded =
                try {
                    Base64.decode(
                        inline,
                        Base64.DEFAULT
                    )
                } catch (_: Exception) {
                    null
                }

            logMarker(
                "STREAM_DECRYPT|WASM_SOURCE=inline|" +
                    "MAGIC=${if (decoded != null && hasWasmMagic(decoded)) "pass" else "fail"}"
            )

            return decoded
                ?.takeIf(::hasWasmMagic)
        }

        val wasmUrl =
            resolveHttpUrl(
                sourceApiUrl,
                envelope.wasmUrl
            ) ?: return null

        return try {
            val response =
                app.get(
                    url = wasmUrl,
                    referer = playerUrl
                )

            val body = response.body
            val bytes = body.bytes()
            body.close()

            val valid =
                response.okhttpResponse.code in 200..299 &&
                    hasWasmMagic(bytes)

            logMarker(
                "STREAM_DECRYPT|WASM_SOURCE=url|" +
                    "HOST=${safeHost(wasmUrl)}|" +
                    "HTTP=${response.okhttpResponse.code}|" +
                    "MAGIC=${if (valid) "pass" else "fail"}"
            )

            bytes.takeIf { valid }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            logMarker(
                "STREAM_DECRYPT|WASM_SOURCE=url|" +
                    "HOST=${safeHost(wasmUrl)}|" +
                    "RESULT=fail|" +
                    "ERROR=${error.javaClass.simpleName}"
            )

            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun decryptWithActualWasm(
        encryptedBase64: String,
        wasmBytes: ByteArray
    ): String? {

        return try {
            withTimeoutOrNull(WASM_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val completed = AtomicBoolean(false)
                    val mainHandler =
                        Handler(
                            Looper.getMainLooper()
                        )

                    val webViewHolder =
                        arrayOfNulls<WebView>(1)

                    fun destroyWebView() {
                        mainHandler.post {
                            webViewHolder[0]
                                ?.removeJavascriptInterface(
                                    "StreamzyBridge"
                                )

                            webViewHolder[0]
                                ?.stopLoading()

                            webViewHolder[0]
                                ?.destroy()

                            webViewHolder[0] = null
                        }
                    }

                    fun finish(value: String?) {
                        if (!completed.compareAndSet(false, true)) {
                            return
                        }

                        destroyWebView()

                        if (continuation.isActive) {
                            continuation.resume(value)
                        }
                    }

                    continuation.invokeOnCancellation {
                        if (
                            completed.compareAndSet(
                                false,
                                true
                            )
                        ) {
                            destroyWebView()
                        }
                    }

                    mainHandler.post {
                        try {
                            val wasmBase64 =
                                Base64.encodeToString(
                                    wasmBytes,
                                    Base64.NO_WRAP
                                )

                            val bridge =
                                WasmBridge(
                                    successCallback = { encoded ->
                                        val plain =
                                            try {
                                                Base64.decode(
                                                    encoded,
                                                    Base64.DEFAULT
                                                ).toString(
                                                    Charsets.UTF_8
                                                )
                                            } catch (_: Exception) {
                                                null
                                            }

                                        finish(plain)
                                    },
                                    failureCallback = {
                                        finish(null)
                                    }
                                )

                            val webView =
                                WebView(
                                    applicationContext
                                )

                            webViewHolder[0] = webView
                            webView.settings.javaScriptEnabled = true
                            webView.settings.domStorageEnabled = false
                            webView.settings.allowFileAccess = false
                            webView.settings.allowContentAccess = false
                            webView.addJavascriptInterface(
                                bridge,
                                "StreamzyBridge"
                            )

                            val html =
                                """
                                <!doctype html><meta charset="utf-8">
                                <script>
                                (function () {
                                  'use strict';
                                  function b64(s) {
                                    var bin = atob(s);
                                    var out = new Uint8Array(bin.length);
                                    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
                                    return out;
                                  }
                                  function outB64(bytes) {
                                    var value = '';
                                    var chunk = 8192;
                                    for (var i = 0; i < bytes.length; i += chunk) {
                                      value += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                                    }
                                    return btoa(value);
                                  }
                                  var wasm = b64('$wasmBase64');
                                  var enc = b64('$encryptedBase64');
                                  WebAssembly.instantiate(wasm.buffer, {}).then(function (result) {
                                    var ex = result.instance.exports;
                                    if (!ex.memory || !ex.alloc || !ex.decrypt) throw new Error('missing-export');
                                    var ptr = ex.alloc(enc.length);
                                    new Uint8Array(ex.memory.buffer, ptr, enc.length).set(enc);
                                    var outLen = ex.decrypt(ptr, enc.length);
                                    if (outLen < 0 || ptr + 12 + outLen > ex.memory.buffer.byteLength) {
                                      throw new Error('invalid-output-range');
                                    }
                                    var output = new Uint8Array(ex.memory.buffer, ptr + 12, outLen);
                                    StreamzyBridge.success(outB64(output));
                                  }).catch(function () {
                                    StreamzyBridge.failure('wasm-runtime-failed');
                                  });
                                })();
                                </script>
                                """.trimIndent()

                            webView.loadDataWithBaseURL(
                                "https://streamzy.local/",
                                html,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        } catch (_: Exception) {
                            finish(null)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            null
        }
    }

    private suspend fun getStreamUrls(
        sourceApiUrl: String,
        playerUrl: String
    ): List<String> {

        val response =
            try {
                app.get(
                    url = sourceApiUrl,
                    headers = mapOf(
                        "Accept" to "application/json"
                    ),
                    referer = playerUrl
                )
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAM_API|HOST=${safeHost(sourceApiUrl)}|" +
                        "RESULT=fail|" +
                        "ERROR=${error.javaClass.simpleName}"
                )

                return emptyList()
            }

        val payload =
            response.parsedSafe<StreamApiResponse>()

        val rawStreams =
            payload
                ?.data
                ?.streamUrls

        logMarker(
            "STREAM_API|HOST=${safeHost(sourceApiUrl)}|" +
                "HTTP=${response.okhttpResponse.code}|" +
                "STREAM_URLS_TYPE=" +
                when (rawStreams) {
                    is String -> "string"
                    is List<*> -> "array"
                    null -> "missing"
                    else -> "unsupported"
                }
        )

        if (rawStreams is List<*>) {
            return rawStreams
                .filterIsInstance<String>()
                .mapNotNull { stream ->
                    resolveHttpUrl(
                        sourceApiUrl,
                        stream
                    )
                }
                .distinct()
        }

        val encrypted =
            (rawStreams as? String)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()

        val envelope =
            payload.vs
                ?: return emptyList()

        val wasmBytes =
            loadWasmBytes(
                envelope,
                sourceApiUrl,
                playerUrl
            ) ?: return emptyList()

        val decrypted =
            decryptWithActualWasm(
                encrypted,
                wasmBytes
            )

        val urls =
            decrypted
                ?.lineSequence()
                ?.map { line ->
                    line.trim()
                }
                ?.filter { line ->
                    line.isNotBlank()
                }
                ?.mapNotNull { stream ->
                    resolveHttpUrl(
                        sourceApiUrl,
                        stream
                    )
                }
                ?.distinct()
                ?.toList()
                .orEmpty()

        logMarker(
            "STREAM_DECRYPT|RESULT=" +
                if (urls.isNotEmpty()) {
                    "pass|URLS=${urls.size}"
                } else {
                    "fail|URLS=0"
                }
        )

        return urls
    }

    private fun getPlayerRuntimeConfig(
        html: String,
        playerUrl: String
    ): PlayerRuntimeConfig? {

        val exactApi =
            getConfigString(
                html,
                "api"
            )

        val streamBase =
            getConfigString(
                html,
                "streamBase"
            )

        if (
            exactApi == null &&
            streamBase == null
        ) {
            if (
                playerUrl.contains(
                    "/embed/player/",
                    true
                )
            ) {
                logMarker(
                    "PLAYER_CONFIG|RESULT=missing|" +
                        "API_PRESENT=no|STREAM_BASE_PRESENT=no|" +
                        "PLAYER_HOST=${safeHost(playerUrl)}"
                )
            }

            return null
        }

        val tvPath =
            Regex(
                """/embed/player/tv/\d+/(\d+)/(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(playerUrl)

        val season =
            getConfigScalar(
                html,
                "season"
            ) ?: tvPath
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

        val episode =
            getConfigScalar(
                html,
                "episode"
            ) ?: tvPath
                ?.groupValues
                ?.getOrNull(2)
                .orEmpty()

        return PlayerRuntimeConfig(
            playerUrl = playerUrl,
            exactApi = exactApi,
            streamBase = streamBase,
            metaApi =
                getConfigString(
                    html,
                    "metaApi"
                ),
            season = season,
            episode = episode
        )
    }

    private fun buildSourceApiUrl(
        config: PlayerRuntimeConfig
    ): String {

        config.exactApi?.let { exactApi ->
            return exactApi
        }

        val streamBase =
            config.streamBase
                ?: return ""

        // Exact apiFor() shape proven by player.js. The final
        // stream_urls parameter is deliberately bare, not =1.
        return streamBase +
            "&season=${encodeComponent(config.season)}" +
            "&episode=${encodeComponent(config.episode)}" +
            "&stream_urls"
    }

    private suspend fun selectMediaCandidate(
        streamUrls: List<String>,
        playerUrl: String
    ): MediaCandidate? {

        val playerHeaders =
            originOf(playerUrl)
                ?.let { origin ->
                    mapOf(
                        "Origin" to origin
                    )
                }
                .orEmpty()

        for (
            (zeroBasedIndex, rawUrl) in
            streamUrls.withIndex()
        ) {
            val index = zeroBasedIndex + 1

            logMarker(
                "STREAM_CANDIDATE_SOURCE|INDEX=$index|" +
                    "SOURCE=stream_urls"
            )

            logMarker(
                "STREAM_CANDIDATE|INDEX=$index|" +
                    "HOST=${safeHost(rawUrl)}|" +
                    "STAGE=raw"
            )

            validateMediaCandidate(
                url = rawUrl,
                referer = null,
                headers = emptyMap()
            )?.let { candidate ->
                logMarker(
                    "STREAM_CANDIDATE|INDEX=$index|" +
                        "RESULT=pass|TOKEN=not-required"
                )

                return candidate
            }

            val token =
                generateToken(
                    rawUrl,
                    playerUrl
                )

            if (token == null) {
                logMarker(
                    "STREAM_CANDIDATE|INDEX=$index|" +
                        "RESULT=fail|REASON=token-unavailable"
                )

                continue
            }

            val (
                tokenizedUrl,
                applyMethod
            ) = applyToken(
                rawUrl,
                token
            )

            logMarker(
                "TOKEN_FLOW|TOKEN_APPLIED|" +
                    "METHOD=$applyMethod|" +
                    "TOKEN_HOST=${safeHost(rawUrl)}"
            )

            validateMediaCandidate(
                url = tokenizedUrl,
                referer = null,
                headers = emptyMap()
            )?.let { candidate ->
                logMarker(
                    "STREAM_CANDIDATE|INDEX=$index|" +
                        "RESULT=pass|CONTEXT=no-explicit-header"
                )

                return candidate
            }

            validateMediaCandidate(
                url = tokenizedUrl,
                referer = playerUrl,
                headers = playerHeaders
            )?.let { candidate ->
                logMarker(
                    "STREAM_CANDIDATE|INDEX=$index|" +
                        "RESULT=pass|CONTEXT=player"
                )

                return candidate
            }

            logMarker(
                "STREAM_CANDIDATE|INDEX=$index|" +
                    "RESULT=fail|REASON=media-validation"
            )
        }

        return null
    }

    private suspend fun resolvePlayerStreams(
        config: PlayerRuntimeConfig,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val sourceApiMode =
            if (config.exactApi != null) {
                "config-exact"
            } else {
                "apiFor"
            }

        logMarker(
            "PLAYER_CONFIG|PLAYER_HOST=" +
                safeHost(config.playerUrl) +
                "|STREAM_API_HOST=" +
                safeHost(
                    config.exactApi
                        ?: config.streamBase
                ) +
                "|META_API_HOST=" +
                safeHost(config.metaApi) +
                "|SOURCE_API_MODE=$sourceApiMode" +
                "|SEASON_PRESENT=" +
                config.season.isNotBlank() +
                "|EPISODE_PRESENT=" +
                config.episode.isNotBlank()
        )

        val sourceApiUrl =
            buildSourceApiUrl(config)

        if (sourceApiUrl.isBlank()) {
            logMarker(
                "STREAM_API|DERIVATION=failed|" +
                    "REASON=missing-api-and-stream-base"
            )

            return false
        }

        val hasBareStreamUrls =
            Regex(
                "(?:[?&])stream_urls(?:[=&]|$)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(sourceApiUrl)

        logMarker(
            "STREAM_API|DERIVATION=$sourceApiMode|" +
                "HOST=${safeHost(sourceApiUrl)}|" +
                "BARE_STREAM_URLS=${if (hasBareStreamUrls) "yes" else "no"}"
        )

        val streamUrls =
            getStreamUrls(
                sourceApiUrl,
                config.playerUrl
            )

        if (streamUrls.isEmpty()) {
            return false
        }

        logMarker(
            "STREAM_API|CANDIDATE_SOURCE=stream_urls|" +
                "COUNT=${streamUrls.size}|" +
                "METADATA_MEDIA=excluded"
        )

        val selected =
            selectMediaCandidate(
                streamUrls,
                config.playerUrl
            ) ?: return false

        if (selected.type == ExtractorLinkType.M3U8) {
            val token =
                queryValue(
                    selected.url,
                    "token"
                )

            logMarker(
                "HLS_PLAYBACK|MASTER|" +
                    "HOST=${safeHost(selected.url)}|" +
                    "TOKEN_PRESENT=${if (token == null) "no" else "yes"}|" +
                    "TOKEN_HASH=${token?.let(::sha256Prefix) ?: "none"}"
            )

            logMarker(
                "HLS_URI_ACTUAL|TYPE=master|" +
                    "SCOPE=provider-handoff|" +
                    "QUERY_TOKEN=${if (token == null) "no" else "yes"}"
            )

            logMarker(
                "TOKEN_PROPAGATION|VARIANT=UNKNOWN|SEGMENT=UNKNOWN|" +
                    "REASON=player-requests-not-exposed-to-provider"
            )

            logMarker(
                "NETWORK_FAMILY|STATUS=UNKNOWN|" +
                    "REASON=socket-route-not-exposed-to-provider"
            )
        }

        callback(
            newExtractorLink(
                source = sourceName,
                name = "$sourceName ${safeHost(selected.url)}",
                url = selected.url,
                type = selected.type
            ) {
                referer = selected.referer
                quality = Qualities.Unknown.value
                headers = selected.headers
            }
        )

        logMarker(
            "STREAM_SELECTED|HOST=${safeHost(selected.url)}|" +
                "TYPE=${selected.type}|" +
                "PLAYBACK_HEADERS=${selected.headers.keys.sorted().joinToString(",").ifBlank { "none" }}|" +
                "REFERER=${if (selected.referer.isBlank()) "none" else "player"}"
        )

        return true
    }

    private suspend fun resolveValidatedPage(
        pageUrl: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>,
        budget: ResolverBudget,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (
            depth > MAX_RESOLVER_DEPTH ||
            budget.pages >= MAX_RESOLVER_PAGES ||
            !visited.add(pageUrl)
        ) {
            return false
        }

        budget.pages += 1

        logMarker(
            "STREAMZY_RESOLVE|STAGE=page|" +
                "DEPTH=$depth|" +
                "HOST=${safeHost(pageUrl)}"
        )

        val html =
            try {
                app.get(
                    url = pageUrl,
                    referer = referer
                ).text
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAMZY_RESOLVE|STAGE=page|" +
                        "HOST=${safeHost(pageUrl)}|" +
                        "RESULT=fail|" +
                        "ERROR=${error.javaClass.simpleName}"
                )

                return false
            }

        getPlayerRuntimeConfig(
            html,
            pageUrl
        )?.let { config ->
            if (
                resolvePlayerStreams(
                    config,
                    callback
                )
            ) {
                return true
            }
        }

        val document =
            Jsoup.parse(
                html,
                pageUrl
            )

        val dataApiUrls =
            document
                .select("[data-api]")
                .mapNotNull { element ->
                    resolveHttpUrl(
                        pageUrl,
                        element.attr("data-api")
                    )
                }
                .distinct()

        for (dataApiUrl in dataApiUrls) {
            val gateSrc =
                try {
                    app.get(
                        url = dataApiUrl,
                        headers = mapOf(
                            "Accept" to "application/json"
                        ),
                        referer = pageUrl
                    )
                        .parsedSafe<GateApiResponse>()
                        ?.src
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        throw error
                    }

                    null
                }

            val gateUrl =
                resolveHttpUrl(
                    dataApiUrl,
                    gateSrc
                ) ?: continue

            logMarker(
                "STREAMZY_RESOLVE|STAGE=gate|" +
                    "API_HOST=${safeHost(dataApiUrl)}|" +
                    "NEXT_HOST=${safeHost(gateUrl)}"
            )

            if (
                resolveValidatedPage(
                    pageUrl = gateUrl,
                    referer = pageUrl,
                    depth = depth + 1,
                    visited = visited,
                    budget = budget,
                    callback = callback
                )
            ) {
                return true
            }
        }

        val playerUrl =
            resolveHttpUrl(
                pageUrl,
                getConfigString(
                    html,
                    "playerUrl"
                )
            )

        if (playerUrl != null) {
            logMarker(
                "STREAMZY_RESOLVE|STAGE=player-url|" +
                    "HOST=${safeHost(playerUrl)}"
            )

            if (
                resolveValidatedPage(
                    pageUrl = playerUrl,
                    referer = pageUrl,
                    depth = depth + 1,
                    visited = visited,
                    budget = budget,
                    callback = callback
                )
            ) {
                return true
            }
        }

        for (
            iframeUrl in
            getIframeUrls(
                document,
                pageUrl
            )
        ) {
            if (
                resolveValidatedPage(
                    pageUrl = iframeUrl,
                    referer = pageUrl,
                    depth = depth + 1,
                    visited = visited,
                    budget = budget,
                    callback = callback
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun exactHostMatch(
        urlHost: String,
        extractorHost: String
    ): Boolean {
        return urlHost == extractorHost ||
            urlHost.endsWith(".$extractorHost")
    }

    suspend fun tryExactExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val urlHost = safeHost(url)

        if (
            urlHost == "-" ||
            urlHost == "invalid"
        ) {
            return false
        }

        val exactExtractors =
            mutableListOf<ExtractorApi>()

        for (
            index in
            extractorApis.lastIndex downTo 0
        ) {
            val extractor = extractorApis[index]
            val extractorHost =
                safeHost(
                    extractor.mainUrl
                )

            if (
                extractorHost != "-" &&
                extractorHost != "invalid" &&
                exactHostMatch(
                    urlHost,
                    extractorHost
                )
            ) {
                exactExtractors.add(extractor)
            }
        }

        for (extractor in exactExtractors) {
            var callbackCount = 0

            logMarker(
                "STREAMZY_RESOLVE|STAGE=extractor|" +
                    "HOST=$urlHost|" +
                    "EXTRACTOR=${extractor.name}|" +
                    "MATCH=exact-host"
            )

            try {
                extractor.getUrl(
                    url = url,
                    referer = referer,
                    subtitleCallback =
                        subtitleCallback,
                    callback = { link ->
                        callbackCount += 1
                        callback(link)
                    }
                )
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAMZY_RESOLVE|STAGE=extractor|" +
                        "HOST=$urlHost|" +
                        "EXTRACTOR=${extractor.name}|" +
                        "RESULT=exception|" +
                        "ERROR=${error.javaClass.simpleName}"
                )
            }

            logMarker(
                "STREAMZY_RESOLVE|STAGE=extractor|" +
                    "HOST=$urlHost|" +
                    "EXTRACTOR=${extractor.name}|" +
                    "CALLBACKS=$callbackCount"
            )

            if (callbackCount > 0) {
                return true
            }
        }

        return false
    }

}
