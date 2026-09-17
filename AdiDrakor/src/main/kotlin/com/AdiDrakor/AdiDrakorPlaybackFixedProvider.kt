package com.AdiDrakor

import android.util.Base64
import android.util.Log
import com.Adicinemax21.Adicinemax21VidSrcShared
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okhttp3.Interceptor
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class AdiDrakorPlaybackFixedProvider : AdiDrakor() {
    companion object {
        private const val SOURCE_GRACE_MS = 12_000L
        private const val GRACE_POLL_MS = 75L
        private const val TARGET_SOURCE_COUNT = 3
        private const val FINAL_SETTLE_MS = 250L
    }

    private fun decodeBase64Url(value: String): String? {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return sequenceOf(Base64.DEFAULT, Base64.URL_SAFE).mapNotNull { flags ->
            try {
                String(Base64.decode(padded, flags), Charsets.UTF_8).trim()
                    .takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            } catch (_: Exception) {
                null
            }
        }.firstOrNull()
    }

    private fun resolveFromUrlPrefix(signCookie: String): String? {
        val encoded = Regex("""urlprefix=([^:;,\s]+)""", RegexOption.IGNORE_CASE)
            .find(signCookie)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val prefix = decodeBase64Url(encoded) ?: return null
        val lower = prefix.lowercase()
        return when {
            lower.endsWith(".mpd") || lower.endsWith(".m3u8") -> prefix
            lower.contains("/hls/") -> prefix.trimEnd('/') + "/index.m3u8"
            else -> prefix.trimEnd('/') + "/index.mpd"
        }
    }

    private fun resolveDashFromCloudFrontPolicy(signCookie: String): String? {
        val policyRaw = signCookie.split(';').asSequence().map { it.trim() }
            .firstOrNull { it.startsWith("CloudFront-Policy=", true) }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        var normalized = buildString(policyRaw.length) {
            policyRaw.forEach { c ->
                append(
                    when (c) {
                        '-' -> '+'
                        '_' -> '='
                        '~' -> '/'
                        else -> c
                    }
                )
            }
        }
        normalized += "=".repeat((4 - normalized.length % 4) % 4)

        val policyJson = try {
            String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }

        val resource = try {
            JSONObject(policyJson).optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource")
                ?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: return null

        val base = resource.trimEnd('*').trimEnd('/')
            .takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?: return null

        return if (base.endsWith(".mpd", true) || base.endsWith(".m3u8", true)) base else "$base/index.mpd"
    }

    private fun isUpdateDummy(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("/notice.mp4") ||
            lower.contains("notice") ||
            (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private fun recoveredMediaUrl(link: ExtractorLink): String? {
        val cookie = link.headers["Cookie"].orEmpty()
        if (!cookie.contains("CloudFront-Policy=", true) && !isUpdateDummy(link.url)) return null
        return resolveFromUrlPrefix(cookie) ?: resolveDashFromCloudFrontPolicy(cookie)
    }

    private fun recoveredType(url: String, fallback: ExtractorLinkType): ExtractorLinkType {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".mpd") -> ExtractorLinkType.DASH
            clean.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            else -> fallback
        }
    }

    @Suppress("DEPRECATION")
    private fun buildRecoveredLink(original: ExtractorLink, recoveredUrl: String): ExtractorLink {
        return ExtractorLink(
            source = "MovieBox",
            name = "MovieBox",
            url = recoveredUrl,
            referer = original.referer,
            quality = original.quality,
            headers = original.headers,
            extractorData = original.extractorData,
            type = recoveredType(recoveredUrl, original.type),
            audioTracks = original.audioTracks
        )
    }

    private fun sourceKey(link: ExtractorLink): String {
        val raw = link.source.ifBlank { link.name }.trim().lowercase()
        return when {
            raw.contains("moviebox") -> "moviebox"
            raw.contains("vidsrc") -> "vidsrc"
            raw.contains("idlix") -> "idlix"
            else -> raw
        }
    }

    private fun graceForwarder(
        emitted: AtomicInteger,
        firstReady: CompletableDeferred<Boolean>,
        sourceKeys: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): (ExtractorLink) -> Unit = { original ->
        val recoveredUrl = recoveredMediaUrl(original)
        val output = when {
            recoveredUrl != null -> {
                Log.d("AdiDrakor", "[MOVIEBOX-FIX] signed manifest recovered")
                buildRecoveredLink(original, recoveredUrl)
            }
            isUpdateDummy(original.url) -> {
                Log.e("AdiDrakor", "[MOVIEBOX-FIX] update dummy suppressed; signed manifest unavailable")
                null
            }
            else -> original
        }

        if (output != null) {
            val family = sourceKey(output)
            sourceKeys.add(family)
            val position = emitted.incrementAndGet()
            callback(output)
            Log.i("AdiDrakor", "[THREE-SOURCE] callback|FAMILY=$family|LINKS=$position|FAMILIES=${sourceKeys.size}")
            if (position == 1) {
                Log.i("AdiDrakor", "[THREE-SOURCE] first=${output.source}|ACTION=bounded-wait")
                firstReady.complete(true)
            }
        }
    }

    private suspend fun loadBaseSources(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        super.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    private suspend fun loadVidSrcSource(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val payload = JSONObject(data)
            val tmdbId = payload.optInt("id", 0)
            if (tmdbId <= 0) return

            val type = payload.optString("type").takeIf { it.isNotBlank() }
            val season = if (payload.has("season") && !payload.isNull("season")) payload.optInt("season") else null
            val episode = if (payload.has("episode") && !payload.isNull("episode")) payload.optInt("episode") else null

            Adicinemax21VidSrcShared.invokeVidSrc(
                tmdbId = tmdbId,
                type = type,
                season = season,
                episode = episode,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e("AdiDrakor", "[VIDSRC] gagal resolve: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private suspend fun loadAllSources(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        supervisorScope {
            val baseJob = launch {
                try {
                    loadBaseSources(data, isCasting, subtitleCallback, callback)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e("AdiDrakor", "[THREE-SOURCE] base sources error: ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            val vidSrcJob = launch {
                loadVidSrcSource(data, subtitleCallback, callback)
            }
            joinAll(baseJob, vidSrcJob)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = supervisorScope {
        val emitted = AtomicInteger(0)
        val firstReady = CompletableDeferred<Boolean>()
        val sourceKeys = ConcurrentHashMap.newKeySet<String>()
        val forward = graceForwarder(emitted, firstReady, sourceKeys, callback)

        val loaderJob = launch {
            try {
                loadAllSources(data, isCasting, subtitleCallback, forward)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("AdiDrakor", "[THREE-SOURCE] resolver error: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                if (!firstReady.isCompleted) firstReady.complete(emitted.get() > 0)
            }
        }

        val ready = firstReady.await()
        if (!ready) {
            loaderJob.join()
            return@supervisorScope false
        }

        val graceStartedNs = System.nanoTime()
        while (loaderJob.isActive && sourceKeys.size < TARGET_SOURCE_COUNT) {
            val elapsedMs = (System.nanoTime() - graceStartedNs) / 1_000_000L
            if (elapsedMs >= SOURCE_GRACE_MS) break
            delay(minOf(GRACE_POLL_MS, SOURCE_GRACE_MS - elapsedMs))
        }

        if (loaderJob.isActive && sourceKeys.size >= TARGET_SOURCE_COUNT) {
            Log.i("AdiDrakor", "[THREE-SOURCE] all-families-ready|FAMILIES=${sourceKeys.size}")
            delay(FINAL_SETTLE_MS)
        }

        if (loaderJob.isActive) loaderJob.cancelAndJoin()

        Log.i(
            "AdiDrakor",
            "[THREE-SOURCE] release-player|LINKS=${emitted.get()}|FAMILIES=${sourceKeys.joinToString(",")}"
        )
        true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val cookie = extractorLink.headers["Cookie"]
        if (cookie.isNullOrBlank()) return super.getVideoInterceptor(extractorLink)
        val userAgent = extractorLink.headers["User-Agent"]

        return Interceptor { chain ->
            val builder = chain.request().newBuilder().header("Cookie", cookie)
            if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
            chain.proceed(builder.build())
        }
    }
}