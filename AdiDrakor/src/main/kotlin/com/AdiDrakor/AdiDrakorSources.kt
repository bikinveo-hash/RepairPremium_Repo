package com.AdiDrakor

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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.lagradost.api.Log
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
    private val providerName: String,
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
            source = if (group == "Idlix") providerName else group,
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
        val headers = mapOf(
            "Referer" to ref,
            "Origin" to base,
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to IdlixPlayback.USER_AGENT,
        )
        val contentType = if (r.type == 1) "movie" else "episode"
        val playInfoHttp = app.get(
            "$base/api/watch/play-info/$contentType/$contentId",
            headers = headers, allowRedirects = false, timeout = 15,
        )
        if (playInfoHttp.code != 200) return
        val info = AppUtils.parseJson<PlayInfoResponse>(playInfoHttp.text)
        // Carry server-issued cookies even when the host app has no CookieJar.
        val sessionCookies = playInfoHttp.cookies
        val gateToken = info.gateToken?.takeIf { it.isNotBlank() } ?: return
        val waitMs = IdlixPlayback.waitMillis(
            info.serverNow, info.unlockAt, info.preroll?.countdownSec,
        ) ?: return
        delay(waitMs)
        val claimHttp = app.post(
            "$base/api/watch/session/claim",
            headers = headers,
            cookies = sessionCookies,
            requestBody = mapOf("gateToken" to gateToken).toJson()
                .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
            allowRedirects = false, timeout = 15,
        )
        if (claimHttp.code != 200) return
        val session = AppUtils.parseJson<SessionClaimResponse>(claimHttp.text)
        if (session.videoId.isNullOrBlank()) return
        Majorplay().resolveClaim(
            session, ref, sub, link, sessionCookies + claimHttp.cookies,
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
        callback: (ExtractorLink) -> Unit
    ) {
        val parsed = url.toHttpUrlOrNull() ?: return
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
        providerName: String = "Idlix"
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
        if (redeem.code != 200) return IdlixPlayback.reject("redeem", "http_${redeem.code}")
        val response = AppUtils.parseJson<NewMajorplayResponse>(redeem.text)
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

        val masterConfigUrl = IdlixPlayback.authorize(master, master).toString()
        val manifest = app.get(masterConfigUrl, headers = safeHeaders, allowRedirects = false, timeout = 15)
        IdlixPlayback.trace("master.response", "http=${manifest.code}")
        if (manifest.code != 200) return IdlixPlayback.reject("master", "http_${manifest.code}")
        val manifestText = manifest.text.trimStart('\uFEFF', ' ', '\r', '\n')
        if (manifestText.length > 262_144 || !manifestText.startsWith("#EXTM3U") ||
            !(manifestText.contains("#EXT-X-STREAM-INF:") || manifestText.contains("#EXTINF:"))) return IdlixPlayback.reject("master", "not_hls")
        IdlixPlayback.trace("master.valid", "videoId=$videoId hls=true bytes=${manifestText.length}")

        response.subtitles?.forEach { sub ->
            val subUrl = sub.path?.toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && IdlixPlayback.mediaHost(it.host)
            }?.newBuilder()?.host(master.host)?.build()?.toString() ?: return@forEach
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
    const val BUILD_MARKER = "IDLIX_PLAYBACK_V3_PROVIDER_ROUTE"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // Callers supply IDs, statuses and fixed reason codes, never credentials/full URLs.
    fun trace(stage: String, detail: String) = Log.i("IdlixProvider", "[IDLIX-V3] $stage $detail")

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
                    trace("media.request", "kind=$kind host=${request.url.host} path=${request.url.encodedPath} scoped=${master != null && videoId(request.url) == videoId(master)} rewritten=${authorized != request.url} http=${response.code}")
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

data class PlayInfoResponse(
    @JsonProperty("gateToken") val gateToken: String? = null,
    @JsonProperty("serverNow") val serverNow: Long? = null,
    @JsonProperty("unlockAt") val unlockAt: Long? = null,
    @JsonProperty("preroll") val preroll: PrerollData? = null
)

data class PrerollData(
    @JsonProperty("countdownSec") val countdownSec: Long? = null
)

data class SessionClaimResponse(
    @JsonProperty("claim") val claim: String? = null,
    @JsonProperty("redeemUrl") val redeemUrl: String? = null,
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("durationSec") val durationSec: Long? = null
)
