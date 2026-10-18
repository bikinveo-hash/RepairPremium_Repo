package com.Adicinemax21

import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*

/** Request-local pacing/cache. Never shared with another provider or retained after resolution. */
internal class Adicinemax21MovieboxMedia(
    private val client: OkHttpClient = Adicinemax21MovieboxPlayback.client,
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
    private val cache = linkedMapOf<String, Pair<Long, Adicinemax21MovieboxPlayback.Sample>>()

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
    ): Adicinemax21MovieboxPlayback.Sample? =
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
                    !Adicinemax21MovieboxPlayback.http(target) ||
                        Adicinemax21MovieboxPlayback.notice(target)
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
                    Adicinemax21MovieboxPlayback.scopeHeaders(headers, scope, target)
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
                    return@withLock Adicinemax21MovieboxPlayback.Sample(
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
                    val next =
                        raw.location?.let { Adicinemax21MovieboxPlayback.resolve(target, it) }
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
                    Adicinemax21MovieboxPlayback.Sample(
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
