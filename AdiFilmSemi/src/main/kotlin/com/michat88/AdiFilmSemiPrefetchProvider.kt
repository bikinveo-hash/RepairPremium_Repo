package com.michat88

import android.util.Log
import com.Adicinemax21.Adicinemax21IdlixShared
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
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

class AdiFilmSemiPrefetchProvider : AdiFilmSemi() {
    companion object {
        private const val SOURCE_WINDOW_MS = 14_500L
        private const val POLL_MS = 75L
        private const val TARGET_FAMILIES = 3
        private const val FINAL_SETTLE_MS = 200L
    }

    private val playbackDelegate = AdiFilmSemiPlaybackFixedProvider()

    override suspend fun load(url: String): LoadResponse? {
        val result = super.load(url)
        if (result != null) {
            Adicinemax21IdlixShared.prefetch(
                title = result.name,
                year = result.year,
                isSeries = result is TvSeriesLoadResponse
            )
        }
        return result
    }

    private fun family(link: ExtractorLink): String {
        val raw = link.source.ifBlank { link.name }.lowercase()
        return when {
            raw.contains("moviebox") -> "moviebox"
            raw.contains("vidsrc") -> "vidsrc"
            raw.contains("idlix") || raw.contains("majorplay") -> "idlix"
            else -> raw.trim()
        }
    }

    private fun forwarder(
        emitted: AtomicInteger,
        firstReady: CompletableDeferred<Boolean>,
        families: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): (ExtractorLink) -> Unit = { link ->
        val key = family(link)
        val duplicateIdlix = key == "idlix" && families.contains("idlix")
        if (!duplicateIdlix) {
            families.add(key)
            val count = emitted.incrementAndGet()
            callback(link)
            Log.i("AdiFilmSemi", "[IDLIX-PREFETCH] callback|FAMILY=$key|LINKS=$count|FAMILIES=${families.size}")
            if (count == 1) firstReady.complete(true)
        }
    }

    private suspend fun loadFastIdlix(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val payload = JSONObject(data)
        val title = payload.optString("title").takeIf { it.isNotBlank() } ?: return
        val orgTitle = payload.optString("orgTitle").takeIf { it.isNotBlank() }
        val altTitle = payload.optString("altTitle").takeIf { it.isNotBlank() }
        val year = if (payload.has("year") && !payload.isNull("year")) payload.optInt("year") else null
        val season = if (payload.has("season") && !payload.isNull("season")) payload.optInt("season") else null
        val episode = if (payload.has("episode") && !payload.isNull("episode")) payload.optInt("episode") else null

        Adicinemax21IdlixShared.invokeIdlix(
            title = title,
            orgTitle = orgTitle,
            altTitle = altTitle,
            year = year,
            season = season,
            episode = episode,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = supervisorScope {
        val emitted = AtomicInteger(0)
        val firstReady = CompletableDeferred<Boolean>()
        val families = ConcurrentHashMap.newKeySet<String>()
        val forward = forwarder(emitted, firstReady, families, callback)

        val loader = launch {
            try {
                supervisorScope {
                    val proven = launch {
                        playbackDelegate.loadLinks(data, isCasting, subtitleCallback, forward)
                    }
                    val prefetchedIdlix = launch {
                        try {
                            loadFastIdlix(data, subtitleCallback, forward)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Log.e("AdiFilmSemi", "[IDLIX-PREFETCH] ${error.javaClass.simpleName}: ${error.message}")
                        }
                    }
                    joinAll(proven, prefetchedIdlix)
                }
            } finally {
                if (!firstReady.isCompleted) firstReady.complete(emitted.get() > 0)
            }
        }

        val ready = firstReady.await()
        if (!ready) {
            loader.join()
            return@supervisorScope false
        }

        val started = System.nanoTime()
        while (loader.isActive && families.size < TARGET_FAMILIES) {
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            if (elapsed >= SOURCE_WINDOW_MS) break
            delay(minOf(POLL_MS, SOURCE_WINDOW_MS - elapsed))
        }

        if (loader.isActive && families.size >= TARGET_FAMILIES) delay(FINAL_SETTLE_MS)
        if (loader.isActive) loader.cancelAndJoin()

        Log.i("AdiFilmSemi", "[IDLIX-PREFETCH] release|FAMILIES=${families.joinToString(",")}|LINKS=${emitted.get()}")
        emitted.get() > 0
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        playbackDelegate.getVideoInterceptor(extractorLink)
}
