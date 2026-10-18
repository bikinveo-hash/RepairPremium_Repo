package com.adixtream.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup

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
