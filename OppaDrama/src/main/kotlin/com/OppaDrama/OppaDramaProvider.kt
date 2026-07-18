package com.OppaDrama

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * OppaDrama plugin for CloudStream.
 *
 * Source: https://oppa.biz (OppaDrama, Indonesian subbed dramas/movies).
 *
 * Notes on the site:
 *  - WordPress + "dramastream" theme.
 *  - Backend serves a human-verification redirect on first hit (no CF challenge,
 *    just a tiny JS shim that appends `?verify_human=1`). The shared `app` handles
 *    this transparently because we issue GETs and follow redirects.
 *  - All listing cards live under `article.bs` inside `div.listupd`.
 *  - Series detail page exposes `div.eplister ul li > a` for the episode list.
 *  - Episode page exposes:
 *      1) `div.player-embed iframe`            – primary player
 *      2) `select.mirror option[value]`        – server mirror dropdown,
 *         each value is a **base64-encoded `<iframe>` tag** (decoded with
 *         `base64Decode` then parsed for `src` / `data-src`).
 *      3) `div.dlbox li span.e a`              – direct download links.
 */
class OppaDramaProvider : MainAPI() {

    // Use the canonical Cloudflare-fronted domain. If the user reports a domain
    // change, they can override it from the "Clone site" settings because
    // `canBeOverridden` stays `true` (the default in MainAPI).
    override var mainUrl = "https://oppa.biz"
    override var name = "OppaDrama"

    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Disable synchronous playback casting to keep behaviour predictable; the
    // site returns direct m3u8 / file URLs and CloudStream handles casting.
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // ------------------------------------------------------------
    // Homepage
    // ------------------------------------------------------------
    // Each pair is (relative URL, label). The trailing `&page=` placeholder is
    // appended in getMainPage() to keep query strings tidy.
    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update"                       to "Latest Update",
        "series/?status=Ongoing&type=&order=update"                to "Ongoing",
        "series/?status=Completed&type=Drama&order=update"         to "Completed Drama",
        "series/?country%5B%5D=china&type=Drama&order=update"      to "Drama China",
        "series/?country%5B%5D=japan&type=Drama&order=update"      to "Drama Jepang",
        "series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "series/?country%5B%5D=philippines&type=Drama&order=update" to "Drama Philippines",
        "series/?country%5B%5D=taiwan&type=Drama&order=update"     to "Drama Taiwan",
        "series/?country%5B%5D=thailand&type=Drama&order=update"   to "Drama Thailand",
        "series/?country%5B%5D=usa&type=Drama&order=update"        to "Drama Western",
        "series/?type=Movie&order=update"                          to "All Movies",
        "series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Korean Movie",
        "series/?country%5B%5D=japan&type=Movie&order=update"      to "Japan Movie",
        "series/?country%5B%5D=china&type=Movie&order=update"      to "Chinese Movie",
        "series/?country%5B%5D=thailand&type=Movie&order=update"   to "Thailand Movie",
        "series/?country%5B%5D=taiwan&type=Movie&order=update"     to "Taiwan Movie",
        "series/?country%5B%5D=philippines&type=Movie&order=update" to "Philippines Movie",
        "series/?country%5B%5D=india&type=Movie&order=update"      to "India Movie",
        "series/?country%5B%5D=united-states&type=Movie&order=update" to "Western Movie",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        // The site already paginates via `&page=N` for these listing endpoints.
        val url = "$mainUrl/${request.data}&page=$page"

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            // If first request was blocked by the verify_human shim, retry once
            // with the marker parameter so the upstream app can drop the cookie
            // it set during the redirect.
            app.get("$url&verify_human=1").document
        }

        val items = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, items, request.horizontalImages),
            hasNext = items.isNotEmpty(),
        )
    }

    // ------------------------------------------------------------
    // Search
    // ------------------------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        // Site uses WordPress-style `?s=...`. Pagination is achieved with
        // `&paged=N` (otherwise the page argument is ignored and we get the
        // same first page over and over).
        val url = if (page <= 1) {
            "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        } else {
            "$mainUrl/page/$page/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        }

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            return null
        }

        val results = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }

        // Detect "no results" sentinel – the page renders a "Nothing Found" block.
        val hasNext = results.isNotEmpty() &&
            document.selectFirst("div.navigation, nav.navigation, .nav-links") != null

        return newSearchResponseList(results, hasNext = hasNext)
    }

    // ------------------------------------------------------------
    // Load – fetch detail / episode data
    // ------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            return null
        }

        // Two distinct page shapes live under this template:
        //   (A) Single-episode page  ->  div#embed_holder + div.dlbox
        //   (B) Series index page    ->  div.epcheck > div.eplister > ul > li
        // We detect (B) first because if an episode lives inside a series, the
        // canonical URL is still the episode page and the series link can be
        // recovered from the `naveps` block.

        val episodeListItems = document.select("div.eplister ul > li > a")
        val hasEpisodeList = episodeListItems.isNotEmpty()

        if (hasEpisodeList) {
            return buildSeriesLoadResponse(url, document)
        }

        return buildEpisodeLoadResponse(url, document)
    }

    // ------------------------------------------------------------
    // loadLinks – extract playable URLs
    // ------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            return false
        }

        var dispatched = false

        // (1) Primary player iframe – usually the default server.
        document.selectFirst("div.player-embed iframe")?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                if (loadExtractor(httpsify(src), data, subtitleCallback, callback)) {
                    dispatched = true
                }
            }
        }

        // (2) Mirror dropdown – each `<option value=...>` is a base64-encoded
        // <iframe> tag. Decode, parse the iframe, dispatch to loadExtractor.
        val mirrorOptions = document.select("select.mirror option[value]:not([disabled])")
        for (option in mirrorOptions) {
            val encoded = option.attr("value").trim()
            if (encoded.isBlank() || encoded == "Pilih Server Video") continue
            try {
                val decoded = base64Decode(encoded.replace("\\s".toRegex(), ""))
                val mirrorSrc = Jsoup.parse(decoded).selectFirst("iframe")?.let { el ->
                    el.attr("src").ifBlank { el.attr("data-src") }
                }
                if (!mirrorSrc.isNullOrBlank()) {
                    if (loadExtractor(httpsify(mirrorSrc), data, subtitleCallback, callback)) {
                        dispatched = true
                    }
                }
            } catch (_: Exception) {
                // Skip broken mirrors silently.
            }
        }

        // (3) Direct download links (Buzzheavier, DataNodes, EarnVids, etc.).
        val downloadLinks = document.select("div.dlbox li span.e a[href]")
        for (a in downloadLinks) {
            val href = a.attr("href").trim()
            if (href.isNotBlank()) {
                if (loadExtractor(httpsify(href), data, subtitleCallback, callback)) {
                    dispatched = true
                }
            }
        }

        return dispatched
    }

    // ============================================================
    //  Builders
    // ============================================================

    private fun buildSeriesLoadResponse(
        url: String,
        document: org.jsoup.nodes.Document,
    ): LoadResponse? {
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        // The series page can use either `.bigcontent img` (older theme) or
        // `div.thumb img` (single info block). Fall back through both.
        val poster = document
            .selectFirst("div.bigcontent img, div.thumb img")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }

        val info = parseInfoBlock(document)
        val tags = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }

        val actors = document
            .select("div.spe span:has(b:matchesOwn(^Artis$)) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val trailer = document
            .selectFirst("div.bixbox.trailer iframe, div.trailer iframe")
            ?.attr("src")

        val recommendations = document
            .select("div.listupd article.bs")
            .mapNotNull { it.toRecommendation() }

        // Episode list – newest on top in DOM order, so reverse for natural numbering.
        val episodeAnchors = document.select("div.eplister ul > li > a").toList()
        val episodes = episodeAnchors.reversed().mapIndexedNotNull { index, anchor ->
            val hrefRaw = anchor.attr("href").ifBlank { return@mapIndexedNotNull null }
            val href = fixUrl(hrefRaw)
            val epNumber = anchor.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull()
                ?: (index + 1)
            newEpisode(href) {
                this.name = anchor.selectFirst("div.epl-title")?.text()?.trim()
                    ?: "Episode $epNumber"
                this.episode = epNumber
                this.date = anchor.selectFirst("div.epl-date")?.text()?.trim()
                    ?.let { runCatching { parseRelativeDate(it) }.getOrNull() }
                this.posterUrl = anchor.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
            }
        }

        val response = newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes,
        ) {
            this.posterUrl = poster
            this.year = info.year
            this.plot = info.plot
            this.tags = tags
            this.showStatus = info.status
            this.duration = info.duration
            this.recommendations = recommendations
            if (info.rating != null) addScore(info.rating.toString(), 10)
            if (actors.isNotEmpty()) addActors(actors)
            if (!trailer.isNullOrBlank()) addTrailer(trailer)
        }
        return response
    }

    private fun buildEpisodeLoadResponse(
        url: String,
        document: org.jsoup.nodes.Document,
    ): LoadResponse? {
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null
        val seriesName = document
            .selectFirst("h2[itemprop=partOfSeries], div.infolimit h2")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val poster = document
            .selectFirst("div.bigcontent img, div.thumb img")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }

        val info = parseInfoBlock(document)
        val tags = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }

        val actors = document
            .select("div.spe span:has(b:matchesOwn(^Artis$)) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val trailer = document
            .selectFirst("div.bixbox.trailer iframe, div.trailer iframe")
            ?.attr("src")

        val recommendations = document
            .select("div.listupd article.bs")
            .mapNotNull { it.toRecommendation() }

        // For a single episode page the playable URL is the page itself –
        // loadLinks will extract iframes / mirror dropdown / download links.
        val dataUrl = url

        val displayTitle = if (seriesName != null && !title.contains(seriesName, ignoreCase = true)) {
            "$seriesName – $title"
        } else title

        return newMovieLoadResponse(
            name = displayTitle,
            url = url,
            type = TvType.Movie,
            dataUrl = dataUrl,
        ) {
            this.posterUrl = poster
            this.year = info.year
            this.plot = info.plot
            this.tags = tags
            this.duration = info.duration
            this.recommendations = recommendations
            if (info.rating != null) addScore(info.rating.toString(), 10)
            if (actors.isNotEmpty()) addActors(actors)
            if (!trailer.isNullOrBlank()) addTrailer(trailer)
        }
    }

    // ============================================================
    //  Parsing helpers
    // ============================================================

    private data class SeriesInfo(
        val status: ShowStatus,
        val year: Int?,
        val plot: String?,
        val rating: Double?,
        val duration: Int?,
    )

    private fun parseInfoBlock(document: org.jsoup.nodes.Document): SeriesInfo {
        // Description block.
        val plot = document
            .select("div.entry-content p, div.desc p")
            .joinToString("\n") { it.text() }
            .trim()
            .ifBlank { null }

        // Walk every <span> in div.spe and pull values by the bold label.
        var status: ShowStatus = ShowStatus.Completed
        var year: Int? = null
        var durationMinutes: Int? = null
        var rating: Double? = null

        for (span in document.select("div.spe > span")) {
            val label = span.selectFirst("b")?.text()?.trim()?.removeSuffix(":") ?: continue
            val value = span.ownText().trim()
            when (label.lowercase()) {
                "status" -> status = when (value.lowercase()) {
                    "ongoing" -> ShowStatus.Ongoing
                    else -> ShowStatus.Completed
                }
                "dirilis" -> {
                    // Examples:
                    //   "Jul 10, 2026 - ?"
                    //   "Jun 26, 2026 - Jul 25, 2026"
                    //   "Dec 18, 2025 - Jan 8, 2026"
                    year = value
                        .substringBefore('-')
                        .trim()
                        .takeLast(4)
                        .toIntOrNull()
                }
                "durasi" -> durationMinutes = parseDurationMinutes(value)
                "rating" -> rating = value.toDoubleOrNull()
            }
        }

        // Some themes hide the rating in a dedicated block.
        if (rating == null) {
            val ratingText = document.selectFirst("div.rating strong")?.text()
            if (ratingText != null) {
                rating = ratingText
                    .replace("Rating", "", ignoreCase = true)
                    .trim()
                    .toDoubleOrNull()
            }
        }

        return SeriesInfo(status, year, plot, rating, durationMinutes)
    }

    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val hours = Regex("(\\d+)\\s*hr").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*min").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + minutes
        return if (total > 0) total else null
    }

    /**
     * Light-weight relative-date parser for strings like "Juli 18, 2026" or
     * already-ISO strings. Falls back to null on any failure so that addDate
     * simply does not populate the field.
     */
    private fun parseRelativeDate(text: String): Long? {
        // First try ISO 8601 (used in some JSON-LD blocks).
        runCatching {
            return kotlinx.datetime.Instant.parse(text).toEpochMilliseconds()
        }
        // Otherwise just return null – addDate() tolerates null and the UI
        // simply shows no date.
        return null
    }

    // ------------------------------------------------------------
    // Element -> SearchResponse / Recommendation
    // ------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = anchor.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()?.trim()
        }?.takeIf { it.isNotBlank() } ?: return null

        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        // Heuristic: listing cards link either to a series index page
        // (`/series-name/`) or to a single episode page (`/...-episode-N/`).
        // When in doubt, default to TvSeries because the user can navigate
        // from the series page even for movies.
        val looksLikeEpisode = Regex("[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)
        val isMovie = href.contains("/genre/movie", true) ||
            href.contains("type=Movie", true)

        return when {
            isMovie && !looksLikeEpisode -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
            looksLikeEpisode -> {
                // Pull the human-readable title (without "- Episode N") when possible.
                val cleanTitle = title.replace(Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()
                newTvSeriesSearchResponse(cleanTitle.ifBlank { title }, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    private fun Element.toRecommendation(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val title = anchor.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()?.trim()
        }?.takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        val looksLikeEpisode = Regex("[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)
        return if (looksLikeEpisode) {
            newTvSeriesSearchResponse(
                title.replace(Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim(),
                href,
                TvType.TvSeries,
            ) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ------------------------------------------------------------
    // Image attr picker – handles lazy loaders, srcset, wpx embeds
    // ------------------------------------------------------------
    private fun Element.getImageAttr(): String? {
        // The wpx-hosted images (i1.wp.com, i2.wp.com) ship an extra query
        // string we don't need; strip the resize suffix to get the original.
        fun cleanup(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return url
                .replace(Regex("[?&]resize=\\d+,\\d+"), "")
                .replace(Regex("[?&]quality=\\d+"), "")
        }

        return when {
            hasAttr("data-src") -> cleanup(attr("abs:data-src"))
            hasAttr("data-lazy-src") -> cleanup(attr("abs:data-lazy-src"))
            hasAttr("srcset") -> cleanup(attr("abs:srcset").substringBefore(" "))
            hasAttr("src") -> cleanup(attr("abs:src"))
            else -> null
        }
    }
}
