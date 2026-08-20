package com.michat88

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ================== IDLIX SOURCE ==================
 *
 * Sumber tambahan untuk AdiFilmSemi. Katalog, detail, pencarian, dan metadata
 * tetap sepenuhnya dari TMDB; berkas ini HANYA menghasilkan ExtractorLink dan
 * SubtitleFile, sama seperti peran MovieBox dan Kisskh.
 *
 * Diambil dari IdlixProvider + Majorplay tanpa membawa mainPage/getMainPage/
 * search()/load() miliknya, karena semua itu sudah ditangani TMDB.
 *
 * Alur: TMDB load() -> LinkData -> loadLinks() -> invokeIdlix()
 *       -> /api/search        (cari slug dari judul TMDB)
 *       -> /api/movies|series  (verifikasi tahun, ambil id)
 *       -> /api/series/../season/N  (khusus serial: ambil id episode)
 *       -> /api/watch/play-info    (gateToken, + fallback WebViewResolver)
 *       -> delay time-lock
 *       -> /api/watch/session/claim (claim)
 *       -> Majorplay /api/play      (master m3u8 + subtitle)
 *
 * DIAGNOSTIK: filter logcat dengan tag "AdiFilmSemiIDX".
 *
 * Seluruh DTO sengaja nested di dalam object ini supaya nama seperti Cast dan
 * Genre tidak bentrok dengan data class TMDB milik AdiFilmSemi.
 */
object AdiFilmSemiIdlix {

    private const val IDX_TAG = "AdiFilmSemiIDX"

    private const val MAIN_URL = "https://z2.idlixku.com"

    // Mengikuti implementasi Idlix asli: batas tunggu time-lock 30 detik.
    private const val MAX_DELAY_MS = 30_000L

    // Maksimum kandidat hasil search yang boleh diverifikasi lewat request detail.
    private const val IDX_MAX_CANDIDATES = 3

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // ---------------------------------------------------------------
    // PENCOCOKAN JUDUL
    // ---------------------------------------------------------------

    private fun clean(s: String?): String =
        s?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase().orEmpty()

    /**
     * Skor kemiripan judul. Semakin kecil semakin cocok, null berarti ditolak.
     * Sengaja ketat: hanya sama persis atau containment yang proporsional.
     */
    private fun rank(candidate: String?, queries: List<String>): Int? {
        val c = clean(candidate)
        if (c.isEmpty()) return null
        var best: Int? = null
        for (q in queries) {
            val cq = clean(q)
            if (cq.isEmpty()) continue
            val score = when {
                c == cq -> 0
                c.contains(cq) && cq.length * 10 >= c.length * 6 -> 1
                cq.contains(c) && c.length >= 6 && c.length * 10 >= cq.length * 6 -> 2
                else -> continue
            }
            val b = best
            if (b == null || score < b) best = score
        }
        return best
    }

    // ---------------------------------------------------------------
    // ENTRY POINT
    // ---------------------------------------------------------------

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
        try {
            val isSeries = season != null
            val queries = listOfNotNull(title, orgTitle, altTitle).distinct()
            Log.d(IDX_TAG, "[1-MULAI] title=$title org=$orgTitle alt=$altTitle year=$year s=$season e=$episode")

            // ---------- 1. SEARCH ----------
            val found = LinkedHashMap<String, IdxContent>()
            for (q in queries) {
                val encoded = java.net.URLEncoder.encode(q, "utf-8")
                val text = runCatching { app.get("$MAIN_URL/api/search?q=$encoded").text }.getOrNull() ?: continue
                val parsed = runCatching { AppUtils.parseJson<IdxSearchResponse>(text) }.getOrNull() ?: continue
                (parsed.data ?: parsed.results).orEmpty().forEach { item ->
                    val slug = item.slug ?: return@forEach
                    if (!found.containsKey(slug)) found[slug] = item
                }
            }
            Log.d(IDX_TAG, "[2-SEARCH] kandidat mentah=${found.size}")
            if (found.isEmpty()) return

            // ---------- 2. SARING & URUTKAN BERDASARKAN JUDUL ----------
            val wantSeries = isSeries
            val ranked = found.values.mapNotNull { item ->
                val itemIsSeries = (item.contentType ?: "").contains("series", true)
                if (itemIsSeries != wantSeries) return@mapNotNull null
                val score = rank(item.title, queries) ?: rank(item.originalTitle, queries) ?: return@mapNotNull null
                score to item
            }.sortedBy { it.first }.take(IDX_MAX_CANDIDATES)

            Log.d(IDX_TAG, "[3-RANK] lolos judul=${ranked.size} -> " +
                    ranked.joinToString { "${it.second.slug}(skor=${it.first})" })
            if (ranked.isEmpty()) return

            // ---------- 3. VERIFIKASI TAHUN LEWAT DETAIL ----------
            var chosenSlug: String? = null
            var chosenId: String? = null
            var fallbackSlug: String? = null
            var fallbackId: String? = null

            for ((score, item) in ranked) {
                val slug = item.slug ?: continue
                val apiUrl = "$MAIN_URL/api/${if (wantSeries) "series" else "movies"}/$slug"
                val detail = runCatching {
                    AppUtils.parseJson<IdxDetail>(app.get(apiUrl).text)
                }.getOrNull() ?: continue

                val detailId = detail.id ?: slug
                val detailYear = (detail.releaseDate ?: detail.firstAirDate)
                    ?.split("-")?.firstOrNull()?.toIntOrNull()

                if (fallbackSlug == null) {
                    fallbackSlug = slug
                    fallbackId = detailId
                }

                val yearOk = year == null || detailYear == null ||
                        kotlin.math.abs(detailYear - year) <= 1

                Log.d(IDX_TAG, "[4-DETAIL] slug=$slug skor=$score tahunIdlix=$detailYear tahunTMDB=$year cocok=$yearOk")

                if (yearOk) {
                    chosenSlug = slug
                    chosenId = detailId
                    break
                }
            }

            if (chosenSlug == null) {
                chosenSlug = fallbackSlug
                chosenId = fallbackId
                Log.d(IDX_TAG, "[4-DETAIL] tidak ada yang cocok tahunnya, fallback ke judul termirip: $chosenSlug")
            }

            val slug = chosenSlug ?: return
            val movieId = chosenId ?: return
            val refererUrl = "$MAIN_URL/${if (wantSeries) "series" else "movie"}/$slug"

            // ---------- 4. TENTUKAN contentType + contentId ----------
            val contentType: String
            val contentId: String

            if (wantSeries) {
                val seasonNum = season
                val epNum = episode ?: return
                val seasonUrl = "$MAIN_URL/api/series/$slug/season/$seasonNum"
                val parsedSeason = runCatching {
                    AppUtils.parseJson<IdxSeasonResponse>(app.get(seasonUrl).text)
                }.getOrNull()

                val ep = parsedSeason?.season?.episodes
                    ?.firstOrNull { it.episodeNumber == epNum && it.hasVideo == true }

                val epId = ep?.id
                if (epId == null) {
                    Log.d(IDX_TAG, "[5-EPISODE] S${seasonNum}E$epNum tidak ada di $slug")
                    return
                }
                contentType = "episode"
                contentId = epId
                Log.d(IDX_TAG, "[5-EPISODE] S${seasonNum}E$epNum -> id=$epId")
            } else {
                contentType = "movie"
                contentId = movieId
                Log.d(IDX_TAG, "[5-MOVIE] id=$contentId")
            }

            // ---------- 5. ALUR PLAY (mengikuti IdlixProvider.loadLinks apa adanya) ----------
            // Pancing CookieJar agar mengaktifkan Cloudflare Solver internal
            app.get(MAIN_URL)

            val randomDid = buildString {
                repeat(32) { append((('a'..'f') + ('0'..'9')).random()) }
            }
            val customCookies = mapOf(
                "did" to randomDid,
                "NEXT_LOCALE" to "id"
            )

            val headers = mapOf(
                "Referer" to refererUrl,
                "Origin" to MAIN_URL,
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to UA
            )

            val targetPlayInfoUrl = "$MAIN_URL/api/watch/play-info/$contentType/$contentId"

            var playInfoResText = runCatching {
                app.get(url = targetPlayInfoUrl, headers = headers, cookies = customCookies).text
            }.getOrNull() ?: ""

            var playInfoRes = runCatching {
                AppUtils.parseJson<IdxPlayInfo>(playInfoResText)
            }.getOrNull()

            // Fallback WebViewResolver bila diblokir Cloudflare
            if (playInfoRes?.gateToken == null) {
                Log.d(IDX_TAG, "[6-PLAYINFO] gateToken null, menjalankan fallback WebViewResolver")

                val webViewResolver = WebViewResolver(
                    interceptUrl = Regex(".*api/watch/play-info.*"),
                    useOkhttp = false
                )

                webViewResolver.resolveUsingWebView(
                    url = targetPlayInfoUrl,
                    headers = headers
                )

                playInfoResText = app.get(
                    url = targetPlayInfoUrl,
                    headers = headers,
                    cookies = customCookies
                ).text

                playInfoRes = runCatching {
                    AppUtils.parseJson<IdxPlayInfo>(playInfoResText)
                }.getOrNull()
            }

            val gateToken = playInfoRes?.gateToken ?: run {
                Log.d(IDX_TAG, "[6-PLAYINFO] gagal mendapatkan gateToken")
                return
            }

            // Bypass time-lock dengan cap maksimum delay
            val serverNow = playInfoRes.serverNow ?: 0L
            val unlockAt = playInfoRes.unlockAt ?: 0L
            val countdownSec = playInfoRes.preroll?.countdownSec ?: 7L

            val diffTimeMs = unlockAt - serverNow
            val baseWaitMs = countdownSec * 1000L
            val finalWaitMs = (maxOf(baseWaitMs, diffTimeMs) + 1000L).coerceAtMost(MAX_DELAY_MS)
            Log.d(IDX_TAG, "[7-DELAY] menunggu ${finalWaitMs}ms")
            delay(finalWaitMs)

            // Klaim token streaming
            val jsonMediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
            val requestBodyData = mapOf("gateToken" to gateToken).toJson().toRequestBody(jsonMediaType)

            val claimResText = app.post(
                url = "$MAIN_URL/api/watch/session/claim",
                headers = headers,
                cookies = customCookies,
                requestBody = requestBodyData
            ).text

            val claim = runCatching {
                AppUtils.parseJson<IdxSessionClaim>(claimResText)
            }.getOrNull()?.claim ?: run {
                Log.d(IDX_TAG, "[8-CLAIM] gagal mendapatkan claim")
                return
            }

            // ---------- 6. MAJORPLAY ----------
            // Class Majorplay dipakai apa adanya (URL, request, dan logic tidak diubah).
            // Hanya label ExtractorLink yang di-relabel menjadi "Idlix - Auto" lewat
            // pembungkus callback, supaya sumbernya jelas di daftar CloudStream.
            val majorplay = Majorplay()
            val fakeUrl = "${majorplay.mainUrl}/play?claim=$claim"

            // newExtractorLink adalah fungsi suspend, sedangkan callback Majorplay
            // bertipe non-suspend. Jadi link dikumpulkan dulu, lalu di-relabel di
            // badan suspend ini. Subtitle diteruskan langsung tanpa perubahan.
            val collected = mutableListOf<ExtractorLink>()
            majorplay.getUrl(fakeUrl, refererUrl, subtitleCallback) { collected.add(it) }

            for (link in collected) {
                callback.invoke(
                    newExtractorLink(
                        source = "Idlix",
                        name = "Idlix - Auto",
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                    }
                )
            }
            Log.d(IDX_TAG, "[9-SELESAI] ExtractorLink=${collected.size}")
        } catch (e: Exception) {
            Log.e(IDX_TAG, "[ERROR] ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------------------------------------------------------
    // DATA CLASSES  (nested; hanya yang dipakai jalur source)
    // ---------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxSearchResponse(
        @JsonProperty("data") val data: List<IdxContent>? = null,
        @JsonProperty("results") val results: List<IdxContent>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxContent(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("originalTitle") val originalTitle: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("contentType") val contentType: String? = null,
        @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxSeasonResponse(
        @JsonProperty("season") val season: IdxSeasonDetail? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxSeasonDetail(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("episodes") val episodes: List<IdxEpisode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxEpisode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("hasVideo") val hasVideo: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxPlayInfo(
        @JsonProperty("gateToken") val gateToken: String? = null,
        @JsonProperty("serverNow") val serverNow: Long? = null,
        @JsonProperty("unlockAt") val unlockAt: Long? = null,
        @JsonProperty("preroll") val preroll: IdxPreroll? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxPreroll(
        @JsonProperty("countdownSec") val countdownSec: Long? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class IdxSessionClaim(
        @JsonProperty("claim") val claim: String? = null,
        @JsonProperty("redeemUrl") val redeemUrl: String? = null,
        @JsonProperty("videoId") val videoId: String? = null
    )
}

// ============================================================================
// MAJORPLAY  -- disalin apa adanya dari Majorplay.kt milik IdlixProvider.
// URL, header, request body, parsing, dan alur TIDAK diubah sedikit pun.
// Tidak didaftarkan lewat registerExtractorAPI karena dipanggil langsung.
// ============================================================================

class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val claimToken = url.substringAfter("claim=").substringBefore("&")
        if (claimToken.isEmpty()) return

        val effectiveReferer = if (!referer.isNullOrBlank()) referer else "https://z2.idlixku.com/"
        val effectiveOrigin = effectiveReferer.trimEnd('/')
            .let { runCatching { java.net.URI(it).let { u -> "${u.scheme}://${u.host}" } }.getOrDefault("https://z2.idlixku.com") }

        val safeHeaders = mapOf(
            "Origin" to effectiveOrigin,
            "Referer" to effectiveReferer,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )

        val jsonMediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
        val requestBodyData = mapOf("claim" to claimToken).toJson()
            .toRequestBody(jsonMediaType)

        val responseText = app.post(
            url = "$mainUrl/api/play",
            headers = safeHeaders,
            requestBody = requestBodyData
        ).text

        val response = AppUtils.parseJson<NewMajorplayResponse>(responseText)

        val masterConfigUrl = response.url ?: return

        response.subtitles?.forEach { sub ->
            val subUrl = sub.path ?: return@forEach
            val lang = sub.label ?: sub.lang ?: "Indo"
            subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Majorplay Auto Quality",
                url = masterConfigUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = safeHeaders
                this.referer = effectiveReferer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class NewMajorplayResponse(
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
