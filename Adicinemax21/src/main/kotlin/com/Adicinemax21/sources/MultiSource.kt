package com.Adicinemax21.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import kotlinx.coroutines.*
import org.json.JSONObject

internal inline fun <T> sourceCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** Request-local source tasks and serialized callbacks. Nothing owns or replaces Moviebox state. */
internal class MultiSource(
    private val tmdbKey: String,
    private val idlix:
        suspend (MediaIdentity.Request, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit =
        { r, s, l ->
            IdlixSource().resolve(r, s, l)
        },
    private val oppa:
        suspend (MediaIdentity.Request, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit =
        { r, s, l ->
            OppaSource().resolve(r, s, l)
        },
    private val enrich: (suspend (MediaIdentity.Request) -> MediaIdentity.Request)? = null,
    private val timeouts: List<Long> = listOf(95_000, 65_000, 50_000),
) {
    private suspend fun metadata(r: MediaIdentity.Request): MediaIdentity.Request {
        if (enrich != null) return enrich.invoke(r)
        if (r.tmdbId?.matches(Regex("[0-9]+")) != true) return r
        return withTimeoutOrNull(6000) {
            sourceCatching {
                    val kind = if (r.type == 1) "movie" else "tv"
                    val raw =
                        app.get(
                                "https://api.themoviedb.org/3/$kind/${r.tmdbId}?api_key=$tmdbKey&append_to_response=translations,alternative_titles,external_ids&language=en-US",
                                timeout = 5,
                            )
                            .takeIf { it.code == 200 }
                            ?.text ?: return@sourceCatching r
                    val o = JSONObject(raw)
                    if (MediaIdentity.text(o, "id") != r.tmdbId) return@sourceCatching r
                    val translations =
                        MediaIdentity.objects(
                                o.optJSONObject("translations")?.optJSONArray("translations")
                            )
                            .filter { MediaIdentity.text(it, "iso_639_1") in setOf("id", "en") }
                            .mapNotNull { it.optJSONObject("data") }
                            .flatMap { listOfNotNull(MediaIdentity.text(it, "title", "name")) }
                    val alternatives = o.optJSONObject("alternative_titles")
                    val aliases =
                        MediaIdentity.objects(
                                alternatives?.optJSONArray("titles")
                                    ?: alternatives?.optJSONArray("results")
                            )
                            .filter {
                                MediaIdentity.text(it, "iso_3166_1") in setOf("ID", "US", "GB")
                            }
                            .mapNotNull { MediaIdentity.text(it, "title") }
                    val seasonYear =
                        MediaIdentity.objects(o.optJSONArray("seasons"))
                            .firstOrNull { it.optInt("season_number", -1) == r.season }
                            ?.let { MediaIdentity.text(it, "air_date")?.take(4)?.toIntOrNull() }
                    r.copy(
                        seasonYear = r.seasonYear ?: seasonYear,
                        titles =
                            (translations +
                                    listOfNotNull(
                                        MediaIdentity.text(o, "title", "name"),
                                        MediaIdentity.text(o, "original_title", "original_name"),
                                    ) +
                                    r.titles +
                                    aliases)
                                .filter { it.isNotBlank() }
                                .distinctBy(MediaIdentity::normalize),
                        imdbId =
                            r.imdbId
                                ?: o.optJSONObject("external_ids")?.let {
                                    MediaIdentity.text(it, "imdb_id")
                                },
                    )
                }
                .getOrDefault(r)
        } ?: r
    }

    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    private fun labeled(group: String, l: ExtractorLink): ExtractorLink {
        val label = l.name.removePrefix("Moviebox • ").ifBlank { "Auto" }
        return ExtractorLink(
            source = group,
            name = "$group • $label",
            url = l.url,
            referer = l.referer,
            quality = l.quality,
            headers = l.headers,
            extractorData = l.extractorData,
            type = l.type,
            audioTracks = l.audioTracks,
        )
    }

    suspend fun resolve(
        r: MediaIdentity.Request,
        sub: (SubtitleFile) -> Unit,
        link: (ExtractorLink) -> Unit,
        moviebox: suspend ((SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit,
    ): Boolean = supervisorScope {
        val lock = Any()
        val urls = hashSetOf<String>()
        val subs = hashSetOf<String>()
        var active = true
        val job = currentCoroutineContext().job
        fun validUrl(url: String): Boolean =
            sourceCatching {
                    val u = URI(url)
                    u.scheme in setOf("http", "https") &&
                        !u.host.isNullOrBlank() &&
                        u.userInfo == null
                }
                .getOrDefault(false)
        fun emit(group: String, sourceJob: Job): (ExtractorLink) -> Unit = { l ->
            synchronized(lock) {
                job.ensureActive()
                sourceJob.ensureActive()
                if (active && validUrl(l.url) && urls.add(l.url)) link(labeled(group, l))
            }
        }
        fun emitSub(sourceJob: Job): (SubtitleFile) -> Unit = { s ->
            synchronized(lock) {
                job.ensureActive()
                sourceJob.ensureActive()
                if (active && validUrl(s.url) && subs.add(s.url)) sub(s)
            }
        }
        // Metadata hydration is independent of Moviebox and shared only by the two new adapters.
        val selected = async(Dispatchers.IO) { if (MediaIdentity.valid(r)) metadata(r) else r }
        val tasks =
            listOf<Pair<String, suspend ((SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit>>(
                "Moviebox" to { s, l -> moviebox(s, l) },
                "Idlix" to
                    { s, l ->
                        val v = selected.await()
                        if (MediaIdentity.valid(v)) idlix(v, s, l)
                    },
                "OppaDrama" to
                    { s, l ->
                        val v = selected.await()
                        if (MediaIdentity.valid(v)) oppa(v, s, l)
                    },
            )
        try {
            tasks
                .mapIndexed { i, (name, task) ->
                    launch(Dispatchers.IO) {
                        try {
                            val completed =
                                withTimeoutOrNull(timeouts[i]) {
                                    val sourceJob = currentCoroutineContext().job
                                    task(emitSub(sourceJob), emit(name, sourceJob))
                                    true
                                }
                            if (completed == null)
                                com.lagradost.api.Log.d("MultiSource", "$name timeout")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            com.lagradost.api.Log.d(
                                "MultiSource",
                                "$name ${e.javaClass.simpleName}",
                            )
                        }
                    }
                }
                .joinAll()
            currentCoroutineContext().ensureActive()
            synchronized(lock) { urls.isNotEmpty() }
        } finally {
            synchronized(lock) { active = false }
        }
    }

    companion object {
        fun isAdditional(link: ExtractorLink): Boolean = link.source in setOf("Idlix", "OppaDrama")

        /** Native media client retains resolver headers, Range and segment requests. */
        fun additionalInterceptor(): okhttp3.Interceptor =
            okhttp3.Interceptor { chain -> chain.proceed(chain.request()) }
    }
}
