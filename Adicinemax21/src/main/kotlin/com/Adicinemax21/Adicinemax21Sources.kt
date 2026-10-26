package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup

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

/**
 * The existing catalog -> play-info -> timed claim -> Majorplay flow, with catalog ID separation.
 */
internal class IdlixSource(
    private val base: String = "https://z2.idlixku.com",
    private val get: suspend (String, Map<String, String>, Map<String, String>) -> String? =
        { url, h, c ->
            app.get(url, headers = h, cookies = c, timeout = 10).takeIf { it.code == 200 }?.text
        },
    private val post:
        suspend (String, String, Map<String, String>, Map<String, String>) -> String? =
        { url, body, h, c ->
            app.post(
                    url,
                    headers = h,
                    cookies = c,
                    requestBody = body.toRequestBody("application/json".toMediaType()),
                    timeout = 10,
                )
                .takeIf { it.code == 200 }
                ?.text
        },
    private val major:
        suspend (String, String, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit =
        { u, r, s, l ->
            Majorplay().getUrl(u, r, s, l)
        },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun resolve(
        r: MediaIdentity.Request,
        sub: (SubtitleFile) -> Unit,
        link: (ExtractorLink) -> Unit,
    ) {
        val candidates = linkedMapOf<String, MediaIdentity.Candidate>()
        val visited = hashSetOf<String>()
        val jsonHeaders = mapOf("Accept" to "application/json")
        for (title in
            r.titles.filter { it.isNotBlank() }.distinctBy(MediaIdentity::normalize).take(6)) {
            val raw =
                get(
                    "$base/api/search?q=${URLEncoder.encode(title,"UTF-8")}",
                    jsonHeaders,
                    emptyMap(),
                ) ?: continue
            val root = JSONObject(raw)
            for (row in
                MediaIdentity.objects(root.optJSONArray("data") ?: root.optJSONArray("results"))
                    .take(12)) {
                val type =
                    when (MediaIdentity.text(row, "contentType", "type")) {
                        "movie" -> 1
                        "series" -> 2
                        else -> continue
                    }
                if (type != r.type) continue
                val names =
                    listOfNotNull(
                        MediaIdentity.text(row, "title", "name"),
                        MediaIdentity.text(row, "originalTitle", "original_title", "originalName"),
                    )
                if (
                    names.none { n ->
                        r.titles.any { MediaIdentity.normalize(it) == MediaIdentity.normalize(n) }
                    } &&
                        !(r.tmdbId != null &&
                            MediaIdentity.text(row, "tmdbId", "tmdb_id") == r.tmdbId)
                )
                    continue
                val slug =
                    MediaIdentity.text(row, "slug")?.takeIf {
                        it.matches(Regex("[\\p{L}\\p{N}_-]+"))
                    } ?: continue
                // Fetch details before accepting search results, which often omit years/IDs.
                val key = "${if(type==1) "movies" else "series"}/$slug"
                if (!visited.add(key)) continue
                val detailRaw = get("$base/api/$key", jsonHeaders, emptyMap()) ?: continue
                candidates[key] = MediaIdentity.idlix(key, JSONObject(detailRaw), type)
            }
        }
        val selected = MediaIdentity.select(r, candidates.values.toList()) ?: return
        val slug = selected.key.substringAfter('/')
        val ref = "$base/${if(r.type==1) "movie" else "series"}/$slug"
        val contentId: String
        if (r.type == 2) {
            val seasonRaw =
                get("$base/api/series/$slug/season/${r.season}", jsonHeaders, emptyMap()) ?: return
            val season = JSONObject(seasonRaw).optJSONObject("season") ?: return
            if (season.optInt("seasonNumber", -1) != r.season) return
            val ep =
                MediaIdentity.objects(season.optJSONArray("episodes"))
                    .filter {
                        it.optInt("episodeNumber", -1) == r.episode &&
                            it.optBoolean("hasVideo", false)
                    }
                    .singleOrNull() ?: return
            contentId = MediaIdentity.text(ep, "id") ?: return
        } else {
            val raw = get("$base/api/${selected.key}", jsonHeaders, emptyMap()) ?: return
            val detail = JSONObject(raw)
            if (!MediaIdentity.matches(r, MediaIdentity.idlix(selected.key, detail, 1))) return
            contentId = MediaIdentity.text(detail, "id") ?: return
        }
        if (!contentId.matches(Regex("[A-Za-z0-9_-]+"))) return
        val cookies =
            mapOf(
                "did" to java.util.UUID.randomUUID().toString().replace("-", ""),
                "NEXT_LOCALE" to "id",
            )
        val headers =
            mapOf(
                "Referer" to ref,
                "Origin" to base,
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            )
        get(base, headers, cookies) // same cookie jar warmup; a denial is never bypassed.
        val raw =
            get(
                "$base/api/watch/play-info/${if(r.type==1) "movie" else "episode"}/$contentId",
                headers,
                cookies,
            ) ?: return
        val info = JSONObject(raw)
        val token = MediaIdentity.text(info, "gateToken") ?: return
        val countdown = info.optJSONObject("preroll")?.optLong("countdownSec", 7L) ?: 7L
        val waitMs =
            maxOf(
                countdown.coerceAtLeast(0).coerceAtMost(3600) * 1000,
                info.optLong("unlockAt") - info.optLong("serverNow"),
            ) + 1000
        // Respect the server's wait; do not cap it then claim prematurely.
        if (waitMs !in 0..30_000) return
        wait(waitMs)
        val claimRaw =
            post(
                "$base/api/watch/session/claim",
                JSONObject().put("gateToken", token).toString(),
                headers,
                cookies,
            ) ?: return
        val claim = MediaIdentity.text(JSONObject(claimRaw), "claim") ?: return
        major(
            "https://e2e.majorplay.net/play?claim=${URLEncoder.encode(claim,"UTF-8")}",
            ref,
            sub,
            link,
        )
    }
}

internal class OppaSource(
    private val base: String = "https://oppadrama.io",
    private val get: suspend (String) -> String? = { url ->
        app.get(
                url,
                headers = mapOf("Cookie" to "user_is_human=true", "User-Agent" to USER_AGENT),
                timeout = 10,
            )
            .takeIf { it.code == 200 }
            ?.text
    },
    private val playback:
        suspend (String, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit =
        { url, s, l ->
            OppaPlayback().loadLinks(url, false, s, l)
            Unit
        },
) {
    private fun catalogUrl(raw: String): String? =
        sourceCatching {
                val u = URI(base).resolve(raw)
                u.toString().takeIf {
                    u.scheme == "https" &&
                        u.host == URI(base).host &&
                        u.userInfo == null &&
                        (u.port == -1 || u.port == 443)
                }
            }
            .getOrNull()

    suspend fun resolve(
        r: MediaIdentity.Request,
        sub: (SubtitleFile) -> Unit,
        link: (ExtractorLink) -> Unit,
    ) {
        val visited = hashSetOf<String>()
        val candidates =
            linkedMapOf<String, Pair<MediaIdentity.Candidate, org.jsoup.nodes.Document>>()
        for (title in
            r.titles.filter { it.isNotBlank() }.distinctBy(MediaIdentity::normalize).take(6)) {
            val html = get("$base/?s=${URLEncoder.encode(title,"UTF-8")}") ?: continue
            val doc = Jsoup.parse(html, base)
            for (a in doc.select(".listupd .bsx a[href], .bs .bsx a[href]").take(12)) {
                val url = catalogUrl(a.attr("href")) ?: continue
                if (!visited.add(url)) continue
                val name = a.selectFirst(".tt h2")?.text() ?: a.attr("title")
                val plain =
                    MediaIdentity.catalogTitle(name)
                        .replace(Regex("(?i)\\s+(?:season|s)\\s*\\d+\\s*$"), "")
                if (
                    name.isNotBlank() &&
                        r.titles.none {
                            MediaIdentity.normalize(it) == MediaIdentity.normalize(plain)
                        }
                )
                    continue
                val detailHtml = get(url) ?: continue
                val detail = Jsoup.parse(detailHtml, url)
                val candidate = MediaIdentity.oppa(url, detail) ?: continue
                candidates[url] = candidate to detail
            }
        }
        val selected = MediaIdentity.select(r, candidates.values.map { it.first }) ?: return
        val detail = candidates.getValue(selected.key).second
        if (r.type == 2) {
            val episode =
                MediaIdentity.episode(r, MediaIdentity.oppaEpisodes(detail, selected)) ?: return
            val url = catalogUrl(episode.url) ?: return
            playback(url, sub, link)
        } else {
            val versions =
                detail
                    .select(
                        ".eplister ul li a[href], .bxcl ul li a[href], #chapterlist ul li a[href], .episodelist ul li a[href]"
                    )
                    .mapNotNull { catalogUrl(it.attr("href")) }
                    .distinct()
            // Every version belongs to this verified movie's own list, never recommendations.
            if (versions.isEmpty()) playback(selected.key, sub, link)
            else versions.take(4).forEach { playback(it, sub, link) }
        }
    }
}

internal class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val claimToken =
            java.net.URLDecoder.decode(
                url.substringAfter("claim=", "").substringBefore("&"),
                "UTF-8",
            )
        if (claimToken.isEmpty()) return

        // FIX #8: Gunakan referer dari parameter jika tersedia, fallback ke idlixku
        val effectiveReferer = if (!referer.isNullOrBlank()) referer else "https://z2.idlixku.com/"
        val effectiveOrigin =
            effectiveReferer.trimEnd('/').let {
                runCatching { java.net.URI(it).let { u -> "${u.scheme}://${u.host}" } }
                    .getOrDefault("https://z2.idlixku.com")
            }

        val safeHeaders =
            mapOf(
                "Origin" to effectiveOrigin,
                "Referer" to effectiveReferer,
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            )

        // FIX #2: Gunakan application/json karena body-nya adalah JSON object
        val jsonMediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
        val requestBodyData = mapOf("claim" to claimToken).toJson().toRequestBody(jsonMediaType)

        val responseText =
            app.post(
                    url = "$mainUrl/api/play",
                    // FIX #2: Header Content-Type sekarang konsisten dengan body (JSON)
                    headers = safeHeaders,
                    requestBody = requestBodyData,
                )
                .text

        val response = AppUtils.parseJson<NewMajorplayResponse>(responseText)

        val masterConfigUrl = response.url ?: return

        // Handle subtitles
        response.subtitles?.forEach { sub ->
            val subUrl = sub.path ?: return@forEach
            val lang = sub.label ?: sub.lang ?: "Indo"
            subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
        }

        // FIX #3: Hapus trick "&.m3u8" yang tidak standar dan bisa merusak URL.
        // ExtractorLinkType.M3U8 sudah cukup untuk memberi tahu player bahwa ini M3U8.
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Majorplay Auto Quality",
                url = masterConfigUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = safeHeaders
                this.referer = effectiveReferer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// FIX #5: Data class dipindah ke luar class agar lebih sesuai konvensi Cloudstream
@JsonIgnoreProperties(ignoreUnknown = true)
data class NewMajorplayResponse(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null,
    @JsonProperty("label") val label: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NewMajorSubtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("path") val path: String? = null,
)
