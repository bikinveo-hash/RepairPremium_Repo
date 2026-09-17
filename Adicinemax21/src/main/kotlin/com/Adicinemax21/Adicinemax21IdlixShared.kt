package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared Idlix playback engine for the Adi providers.
 *
 * The expensive title -> Idlix slug/id matching is prefetched while the detail
 * page is open. Playback then only needs episode mapping (for series), the
 * server-enforced gate/countdown, claim, and Majorplay request.
 *
 * This file intentionally contains no @CloudstreamPlugin entry point so it can
 * be source-shared by AdiDrakor/AdiFilmSemi/AdiXtream safely.
 */
internal object Adicinemax21IdlixShared {
    private const val TAG = "AdiSharedIDX"
    private const val MAIN_URL = "https://z2.idlixku.com"
    private const val MAJORPLAY_URL = "https://e2e.majorplay.net"
    private const val MAX_DELAY_MS = 30_000L
    private const val MAX_CANDIDATES = 3
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<BaseMatch?>>()

    private data class BaseMatch(
        val slug: String,
        val contentId: String,
        val refererUrl: String,
        val isSeries: Boolean
    )

    private data class CacheEntry(
        val value: BaseMatch,
        val createdAt: Long = System.currentTimeMillis()
    )

    private fun clean(value: String?): String =
        value?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase().orEmpty()

    private fun cacheKey(title: String, year: Int?, isSeries: Boolean): String =
        "${if (isSeries) "tv" else "movie"}|${clean(title)}|${year ?: 0}"

    private fun rank(candidate: String?, queries: List<String>): Int? {
        val c = clean(candidate)
        if (c.isEmpty()) return null
        var best: Int? = null
        for (query in queries) {
            val q = clean(query)
            if (q.isEmpty()) continue
            val score = when {
                c == q -> 0
                c.contains(q) && q.length * 10 >= c.length * 6 -> 1
                q.contains(c) && c.length >= 6 && c.length * 10 >= q.length * 6 -> 2
                else -> continue
            }
            if (best == null || score < best) best = score
        }
        return best
    }

    fun prefetch(
        title: String,
        year: Int?,
        isSeries: Boolean,
        orgTitle: String? = null,
        altTitle: String? = null
    ) {
        val key = cacheKey(title, year, isSeries)
        val current = cache[key]
        if (current != null && System.currentTimeMillis() - current.createdAt < CACHE_TTL_MS) return

        scope.launch {
            runCatching {
                getBaseMatch(title, orgTitle, altTitle, year, isSeries)
            }.onFailure {
                Log.d(TAG, "[PREFETCH] gagal ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private suspend fun getBaseMatch(
        title: String,
        orgTitle: String?,
        altTitle: String?,
        year: Int?,
        isSeries: Boolean
    ): BaseMatch? {
        val key = cacheKey(title, year, isSeries)
        val current = cache[key]
        if (current != null && System.currentTimeMillis() - current.createdAt < CACHE_TTL_MS) {
            Log.d(TAG, "[CACHE] HIT key=$key slug=${current.value.slug}")
            return current.value
        }
        if (current != null) cache.remove(key, current)

        val mine = CompletableDeferred<BaseMatch?>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) {
            val shared = existing.await()
            if (shared != null) {
                Log.d(TAG, "[CACHE] JOIN key=$key slug=${shared.slug}")
                return shared
            }
            // A title-only prefetch may fail while richer aliases can still work.
            return resolveAndCache(key, title, orgTitle, altTitle, year, isSeries)
        }

        return try {
            val resolved = resolveBaseMatch(title, orgTitle, altTitle, year, isSeries)
            if (resolved != null) cache[key] = CacheEntry(resolved)
            mine.complete(resolved)
            resolved
        } catch (error: Throwable) {
            mine.complete(null)
            throw error
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private suspend fun resolveAndCache(
        key: String,
        title: String,
        orgTitle: String?,
        altTitle: String?,
        year: Int?,
        isSeries: Boolean
    ): BaseMatch? {
        val resolved = resolveBaseMatch(title, orgTitle, altTitle, year, isSeries)
        if (resolved != null) cache[key] = CacheEntry(resolved)
        return resolved
    }

    private suspend fun resolveBaseMatch(
        title: String,
        orgTitle: String?,
        altTitle: String?,
        year: Int?,
        isSeries: Boolean
    ): BaseMatch? {
        val started = System.nanoTime()
        val queries = listOfNotNull(title, orgTitle, altTitle)
            .filter { it.isNotBlank() }
            .distinct()
        if (queries.isEmpty()) return null

        val found = LinkedHashMap<String, SearchItem>()
        for (query in queries) {
            val encoded = java.net.URLEncoder.encode(query, "utf-8")
            val text = runCatching { app.get("$MAIN_URL/api/search?q=$encoded").text }
                .getOrNull() ?: continue
            val parsed = runCatching { AppUtils.parseJson<SearchResponse>(text) }.getOrNull() ?: continue
            (parsed.data ?: parsed.results).orEmpty().forEach { item ->
                val slug = item.slug ?: return@forEach
                if (!found.containsKey(slug)) found[slug] = item
            }
        }
        if (found.isEmpty()) {
            Log.d(TAG, "[PREFETCH] no-search-match title=$title")
            return null
        }

        val ranked = found.values.mapNotNull { item ->
            val itemIsSeries = (item.contentType ?: "").contains("series", true)
            if (itemIsSeries != isSeries) return@mapNotNull null
            val score = rank(item.title, queries) ?: rank(item.originalTitle, queries) ?: return@mapNotNull null
            score to item
        }.sortedBy { it.first }.take(MAX_CANDIDATES)

        var fallback: BaseMatch? = null
        for ((_, item) in ranked) {
            val slug = item.slug ?: continue
            val endpoint = "$MAIN_URL/api/${if (isSeries) "series" else "movies"}/$slug"
            val detail = runCatching {
                AppUtils.parseJson<DetailResponse>(app.get(endpoint).text)
            }.getOrNull() ?: continue

            val id = detail.id ?: slug
            val detailYear = (detail.releaseDate ?: detail.firstAirDate)
                ?.substringBefore('-')?.toIntOrNull()
            val match = BaseMatch(
                slug = slug,
                contentId = id,
                refererUrl = "$MAIN_URL/${if (isSeries) "series" else "movie"}/$slug",
                isSeries = isSeries
            )
            if (fallback == null) fallback = match

            val yearOk = year == null || detailYear == null || kotlin.math.abs(detailYear - year) <= 1
            if (yearOk) {
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                Log.d(TAG, "[PREFETCH] READY slug=$slug elapsed=${elapsed}ms")
                // Prime the shared CookieJar/Cloudflare solver while the detail page is open.
                runCatching { app.get(MAIN_URL) }
                return match
            }
        }

        fallback?.let {
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            Log.d(TAG, "[PREFETCH] FALLBACK slug=${it.slug} elapsed=${elapsed}ms")
            runCatching { app.get(MAIN_URL) }
        }
        return fallback
    }

    suspend fun invokeIdlix(
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val started = System.nanoTime()
        try {
            val isSeries = season != null
            val base = getBaseMatch(title, orgTitle, altTitle, year, isSeries) ?: return

            val contentType: String
            val contentId: String
            if (isSeries) {
                val seasonNum = season ?: return
                val episodeNum = episode ?: return
                val seasonText = runCatching {
                    app.get("$MAIN_URL/api/series/${base.slug}/season/$seasonNum").text
                }.getOrNull() ?: return
                val seasonData = runCatching { AppUtils.parseJson<SeasonResponse>(seasonText) }.getOrNull()
                val targetEpisode = seasonData?.season?.episodes
                    ?.firstOrNull { it.episodeNumber == episodeNum && it.hasVideo == true }
                    ?: return
                contentType = "episode"
                contentId = targetEpisode.id ?: return
            } else {
                contentType = "movie"
                contentId = base.contentId
            }

            // Keep parity with the working standalone Idlix provider.
            runCatching { app.get(MAIN_URL) }
            val randomDid = buildString {
                repeat(32) { append((('a'..'f') + ('0'..'9')).random()) }
            }
            val cookies = mapOf("did" to randomDid, "NEXT_LOCALE" to "id")
            val headers = mapOf(
                "Referer" to base.refererUrl,
                "Origin" to MAIN_URL,
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to UA
            )

            val playInfoUrl = "$MAIN_URL/api/watch/play-info/$contentType/$contentId"
            var playInfoText = runCatching {
                app.get(playInfoUrl, headers = headers, cookies = cookies).text
            }.getOrNull().orEmpty()
            var playInfo = runCatching { AppUtils.parseJson<PlayInfo>(playInfoText) }.getOrNull()

            if (playInfo?.gateToken == null) {
                Log.d(TAG, "[PLAYINFO] gateToken null -> WebViewResolver")
                val resolver = WebViewResolver(
                    interceptUrl = Regex(".*api/watch/play-info.*"),
                    useOkhttp = false
                )
                resolver.resolveUsingWebView(
                    url = playInfoUrl,
                    headers = headers
                )
                playInfoText = app.get(playInfoUrl, headers = headers, cookies = cookies).text
                playInfo = runCatching { AppUtils.parseJson<PlayInfo>(playInfoText) }.getOrNull()
            }

            val gateToken = playInfo?.gateToken ?: return
            val serverNow = playInfo.serverNow ?: 0L
            val unlockAt = playInfo.unlockAt ?: 0L
            val countdownSec = playInfo.preroll?.countdownSec ?: 7L
            val waitMs = (maxOf(countdownSec * 1000L, unlockAt - serverNow) + 1000L)
                .coerceAtMost(MAX_DELAY_MS)
            Log.d(TAG, "[GATE] wait=${waitMs}ms")
            delay(waitMs)

            val mediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
            val claimBody = mapOf("gateToken" to gateToken).toJson().toRequestBody(mediaType)
            val claimText = app.post(
                "$MAIN_URL/api/watch/session/claim",
                headers = headers,
                cookies = cookies,
                requestBody = claimBody
            ).text
            val claim = runCatching { AppUtils.parseJson<SessionClaim>(claimText) }
                .getOrNull()?.claim ?: return

            val playHeaders = mapOf(
                "Origin" to MAIN_URL,
                "Referer" to base.refererUrl,
                "User-Agent" to UA
            )
            val playBody = mapOf("claim" to claim).toJson().toRequestBody(mediaType)
            val playText = app.post(
                "$MAJORPLAY_URL/api/play",
                headers = playHeaders,
                requestBody = playBody
            ).text
            val play = runCatching { AppUtils.parseJson<MajorplayResponse>(playText) }.getOrNull() ?: return

            play.subtitles.orEmpty().forEach { subtitle ->
                val path = subtitle.path ?: return@forEach
                subtitleCallback(newSubtitleFile(subtitle.label ?: subtitle.lang ?: "Indo", path))
            }

            val mediaUrl = play.url ?: return
            callback(
                newExtractorLink(
                    source = "Idlix",
                    name = "Idlix - Auto",
                    url = mediaUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = base.refererUrl
                    quality = Qualities.Unknown.value
                    this.headers = playHeaders
                }
            )
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            Log.d(TAG, "[DONE] link-ready elapsed=${elapsed}ms")
        } catch (error: Throwable) {
            Log.e(TAG, "[ERROR] ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchResponse(
        @JsonProperty("data") val data: List<SearchItem>? = null,
        @JsonProperty("results") val results: List<SearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("originalTitle") val originalTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("contentType") val contentType: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DetailResponse(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SeasonResponse(
        @JsonProperty("season") val season: SeasonData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SeasonData(
        @JsonProperty("episodes") val episodes: List<EpisodeData>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class EpisodeData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("hasVideo") val hasVideo: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PlayInfo(
        @JsonProperty("gateToken") val gateToken: String? = null,
        @JsonProperty("serverNow") val serverNow: Long? = null,
        @JsonProperty("unlockAt") val unlockAt: Long? = null,
        @JsonProperty("preroll") val preroll: Preroll? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Preroll(
        @JsonProperty("countdownSec") val countdownSec: Long? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SessionClaim(
        @JsonProperty("claim") val claim: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MajorplayResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("subtitles") val subtitles: List<MajorSubtitle>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MajorSubtitle(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("path") val path: String? = null
    )
}