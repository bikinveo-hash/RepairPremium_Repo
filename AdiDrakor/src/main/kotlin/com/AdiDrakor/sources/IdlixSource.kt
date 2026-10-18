package com.AdiDrakor.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
