package com.michat88

import android.util.Log
import com.Adicinemax21.Adicinemax21VidSrcShared
import com.Adicinemax21.MovieBoxV2Shared
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
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

class AdiFilmSemiPlaybackFixedProvider : AdiFilmSemi() {
    companion object {
        private const val SOURCE_GRACE_MS = 12_000L
        private const val GRACE_POLL_MS = 75L
        private const val TARGET_SOURCE_COUNT = 3
        private const val FINAL_SETTLE_MS = 250L
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
    ): (ExtractorLink) -> Unit = { link ->
        val family = sourceKey(link)
        sourceKeys.add(family)
        val position = emitted.incrementAndGet()
        callback(link)
        Log.i("AdiFilmSemi", "[THREE-SOURCE] callback|FAMILY=$family|LINKS=$position|FAMILIES=${sourceKeys.size}")
        if (position == 1) firstReady.complete(true)
    }

    private fun intOrNull(payload: JSONObject, key: String): Int? =
        if (payload.has(key) && !payload.isNull(key)) payload.optInt(key) else null

    private fun stringOrNull(payload: JSONObject, key: String): String? =
        payload.optString(key).takeIf { it.isNotBlank() }

    private suspend fun loadMovieBox(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val payload = JSONObject(data)
        val title = stringOrNull(payload, "title") ?: return
        MovieBoxV2Shared.invokeMoviebox(
            sourceTag = "AdiFilmSemi",
            title = title,
            orgTitle = stringOrNull(payload, "orgTitle"),
            altTitle = stringOrNull(payload, "jpTitle"),
            year = intOrNull(payload, "year"),
            airedYear = intOrNull(payload, "airedYear"),
            season = intOrNull(payload, "season"),
            episode = intOrNull(payload, "episode"),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun loadIdlix(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val payload = JSONObject(data)
        val title = stringOrNull(payload, "title") ?: return
        val season = intOrNull(payload, "season")
        val year = if (season != null) {
            intOrNull(payload, "airedYear") ?: intOrNull(payload, "year")
        } else {
            intOrNull(payload, "year")
        }
        AdiFilmSemiIdlix.invokeIdlix(
            title = title,
            orgTitle = stringOrNull(payload, "orgTitle"),
            altTitle = stringOrNull(payload, "jpTitle"),
            year = year,
            season = season,
            episode = intOrNull(payload, "episode"),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun loadVidSrc(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val payload = JSONObject(data)
        val tmdbId = intOrNull(payload, "id") ?: return
        Adicinemax21VidSrcShared.invokeVidSrc(
            tmdbId = tmdbId,
            type = stringOrNull(payload, "type"),
            season = intOrNull(payload, "season"),
            episode = intOrNull(payload, "episode"),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun loadAllSources(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = supervisorScope {
        val movieBox = launch {
            try { loadMovieBox(data, subtitleCallback, callback) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("AdiFilmSemi", "[MOVIEBOX-V2] ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        val idlix = launch {
            try { loadIdlix(data, subtitleCallback, callback) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("AdiFilmSemi", "[IDLIX] ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        val vidSrc = launch {
            try { loadVidSrc(data, subtitleCallback, callback) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("AdiFilmSemi", "[VIDSRC] ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        joinAll(movieBox, idlix, vidSrc)
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
                loadAllSources(data, subtitleCallback, forward)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("AdiFilmSemi", "[THREE-SOURCE] resolver error: ${error.message}")
            } finally {
                if (!firstReady.isCompleted) firstReady.complete(emitted.get() > 0)
            }
        }

        val ready = firstReady.await()
        if (!ready) {
            loaderJob.join()
            return@supervisorScope false
        }

        val started = System.nanoTime()
        while (loaderJob.isActive && sourceKeys.size < TARGET_SOURCE_COUNT) {
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            if (elapsed >= SOURCE_GRACE_MS) break
            delay(minOf(GRACE_POLL_MS, SOURCE_GRACE_MS - elapsed))
        }

        if (loaderJob.isActive && sourceKeys.size >= TARGET_SOURCE_COUNT) delay(FINAL_SETTLE_MS)
        if (loaderJob.isActive) loaderJob.cancelAndJoin()

        Log.i("AdiFilmSemi", "[THREE-SOURCE] release-player|LINKS=${emitted.get()}|FAMILIES=${sourceKeys.joinToString(",")}")
        emitted.get() > 0
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
