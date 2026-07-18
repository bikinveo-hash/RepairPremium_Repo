package com.OppaDrama

import com.lagradost.api.Log
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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * OppaDrama plugin for CloudStream.
 *
 * Source: https://oppa.biz (OppaDrama, Indonesian subbed dramas/movies).
 *
 * Notes on the site:
 *  - WordPress + "dramastream" theme.
 *  - Backend serves a human-verification redirect on first hit (no CF
 *    challenge, just a tiny JS shim that appends `?verify_human=1`). The
 *    shared `app` handles this transparently because it follows redirects.
 *  - Listing cards live under `article.bs` inside `div.listupd`.
 *  - Series detail page exposes `div.eplister ul > li > a` for the episode
 *    list, with a `data-index` attribute and `div.epl-num` / `div.epl-title`
 *    / `div.epl-date` children.
 *  - Episode page exposes:
 *      1) `div.player-embed iframe`            – primary player
 *      2) `select.mirror option[value]`        – server mirror dropdown,
 *         each value is a base64-encoded `<iframe>` tag.
 *      3) `div.dlbox li span.e a`              – direct download links.
 */
class OppaDramaProvider : MainAPI() {

    // The canonical Cloudflare-fronted domain. Because the underlying server
    // IP rotates frequently, the user can override `mainUrl` from the "Clone
    // site" settings in CloudStream (canBeOverridden defaults to `true`).
    override var mainUrl = "https://oppa.biz"
    override var name = "OppaDrama"

    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    // ------------------------------------------------------------
    // Homepage
    // ------------------------------------------------------------
    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update"                                   to "Latest Update",
        "series/?status=Ongoing&type=&order=update"                            to "Ongoing",
        "series/?status=Completed&type=Drama&order=update"                     to "Completed Drama",
        "series/?country%5B%5D=china&type=Drama&order=update"                  to "Drama China",
        "series/?country%5B%5D=japan&type=Drama&order=update"                  to "Drama Jepang",
        "series/?country%5B%5D=south-korea&status=&type=Drama&order=update"    to "Drama Korea",
        "series/?country%5B%5D=philippines&type=Drama&order=update"            to "Drama Philippines",
        "series/?country%5B%5D=taiwan&type=Drama&order=update"                 to "Drama Taiwan",
        "series/?country%5B%5D=thailand&type=Drama&order=update"               to "Drama Thailand",
        "series/?country%5B%5D=usa&type=Drama&order=update"                    to "Drama Western",
        "series/?type=Movie&order=update"                                      to "All Movies",
        "series/?country%5B%5D=south-korea&status=&type=Movie&order=update"    to "Korean Movie",
        "series/?country%5B%5D=japan&type=Movie&order=update"                  to "Japan Movie",
        "series/?country%5B%5D=china&type=Movie&order=update"                  to "Chinese Movie",
        "series/?country%5B%5D=thailand&type=Movie&order=update"               to "Thailand Movie",
        "series/?country%5B%5D=taiwan&type=Movie&order=update"                 to "Taiwan Movie",
        "series/?country%5B%5D=philippines&type=Movie&order=update"            to "Philippines Movie",
        "series/?country%5B%5D=india&type=Movie&order=update"                  to "India Movie",
        "series/?country%5B%5D=united-states&type=Movie&order=update"          to "Western Movie",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"

        val document = fetchDocument(url)
        if (document == null) {
            Log.w(TAG, "getMainPage: fetchDocument returned null for $url")
            return newHomePageResponse(
                HomePageList(request.name, emptyList(), request.horizontalImages),
                hasNext = false,
            )
        }

        val articles = document.select("div.listupd article.bs")
        Log.d(TAG, "getMainPage: found ${articles.size} article.bs in $url")
        val items = articles.mapNotNull { it.toSearchResult() }
        Log.d(TAG, "getMainPage: parsed ${items.size} search results for ${request.name}")

        return newHomePageResponse(
            HomePageList(request.name, items, request.horizontalImages),
            hasNext = items.isNotEmpty(),
        )
    }

    // ------------------------------------------------------------
    // Search
    // ------------------------------------------------------------
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val document = fetchDocument(url) ?: return null

        val results = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }

        // WordPress returns an empty .listupd with a "Nothing Found" block
        // when no results match – treat that as `hasNext = false`.
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    // ------------------------------------------------------------
    // Load
    // ------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val document = fetchDocument(url) ?: return null

        // Two page shapes live under this template:
        //   (A) Series index page – has `div.eplister ul > li > a`
        //   (B) Single episode page – has `div.player-embed` but no list
        return if (document.selectFirst("div.eplister ul > li > a") != null) {
            buildSeriesLoadResponse(url, document)
        } else {
            buildEpisodeLoadResponse(url, document)
        }
    }

    // ------------------------------------------------------------
    // loadLinks
    // ------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = fetchDocument(data) ?: return false

        var dispatched = false

        // (1) Primary player iframe – usually the default server.
        document.selectFirst("div.player-embed iframe")?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && loadExtractor(httpsify(src), data, subtitleCallback, callback)) {
                dispatched = true
            }
        }

        // (2) Mirror dropdown – each <option value=...> is a base64-encoded
        // <iframe> tag. Decode, parse the iframe, dispatch to loadExtractor.
        val mirrorOptions = document.select("select.mirror option[value]:not([disabled])")
        for (option in mirrorOptions) {
            val encoded = option.attr("value").trim()
            if (encoded.isBlank() || encoded.equals("Pilih Server Video", ignoreCase = true)) continue
            try {
                val decoded = base64Decode(encoded.replace("\\s".toRegex(), ""))
                val mirrorSrc = Jsoup.parse(decoded).selectFirst("iframe")?.let { el ->
                    el.attr("src").ifBlank { el.attr("data-src") }
                }
                if (!mirrorSrc.isNullOrBlank() &&
                    loadExtractor(httpsify(mirrorSrc), data, subtitleCallback, callback)
                ) {
                    dispatched = true
                }
            } catch (_: Exception) {
                // Skip broken mirrors silently.
            }
        }

        // (3) Direct download links (Buzzheavier, DataNodes, EarnVids, etc.).
        val downloadLinks = document.select("div.dlbox li span.e a[href]")
        for (a in downloadLinks) {
            val href = a.attr("href").trim()
            if (href.isNotBlank() && loadExtractor(httpsify(href), data, subtitleCallback, callback)) {
                dispatched = true
            }
        }

        return dispatched
    }

    // ============================================================
    //  Builders
    // ============================================================

    private suspend fun buildSeriesLoadResponse(
        url: String,
        document: org.jsoup.nodes.Document,
    ): LoadResponse? {
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        val poster = pickPoster(document)

        val info = parseInfoBlock(document)
        val tags = document.select("div.genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

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

        // Episode list – newest on top in DOM order, so reverse for natural
        // numbering (1, 2, 3, ...).
        val episodeAnchors = document.select("div.eplister ul > li > a").toList()
        val episodes = episodeAnchors.reversed().mapIndexed { index, anchor ->
            val href = anchor.attr("href")
            val epNumber = anchor.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull()
                ?: (index + 1)
            val epTitle = anchor.selectFirst("div.epl-title")?.text()?.trim()
                ?: "Episode $epNumber"
            val epDate = anchor.selectFirst("div.epl-date")?.text()?.trim()
            // Resolve the poster through fixUrlNull OUTSIDE the lambda so we
            // keep the MainAPI receiver (the lambda receiver is Episode, not
            // MainAPI, and `fixUrlNull` is an extension on MainAPI).
            val epPoster = anchor.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
            newEpisode(href) {
                name = epTitle
                episode = epNumber
                // The "epl-date" text is a localised Indonesian date
                // (e.g. "Juli 18, 2026") that Cloudstream's `addDate` does
                // not understand out of the box. Leave the field null –
                // the player will simply omit the release date.
                posterUrl = epPoster
            }
        }

        return newTvSeriesLoadResponse(
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
    }

    private suspend fun buildEpisodeLoadResponse(
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

        val poster = pickPoster(document)

        val info = parseInfoBlock(document)
        val tags = document.select("div.genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

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
        val displayTitle = if (seriesName != null && !title.contains(seriesName, ignoreCase = true)) {
            "$seriesName – $title"
        } else title

        return newMovieLoadResponse(
            name = displayTitle,
            url = url,
            type = TvType.Movie,
            dataUrl = url,
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

    /**
     * Pulls the poster image URL from whichever container the current theme
     * uses. Strips the Jetpack `?resize=...` query so the player receives
     * the full-resolution asset.
     */
    private fun pickPoster(document: org.jsoup.nodes.Document): String? {
        val raw = document
            .selectFirst("div.bigcontent img, div.thumb img")
            ?.getImageAttr()
            ?: return null
        return fixUrlNull(raw)
    }

    // ============================================================
    //  Parsing helpers
    // ============================================================

    /**
     * Wrapper around [app.get] that handles the site's
     * `?verify_human=1` challenge.
     *
     * The challenge is a small JS shim (~430 bytes) that, when loaded
     * in a browser, appends `?verify_human=1` to the URL and reloads.
     * Two facts make the plugin able to bypass it without a real
     * browser:
     *
     * 1. The shim body is unique enough to detect after the fact (it
     *    contains the literal "verify_human" and lacks any real
     *    `<article>` or `<div class="bsx">` markers).
     * 2. Sending `?verify_human=1` in the query string up-front makes
     *    the server skip the shim and return the real HTML in a
     *    single round-trip (it sets a `user_is_human=true` cookie
     *    that subsequent requests reuse).
     *
     * Strategy:
     *   a) First request with the marker appended – most paths.
     *   b) If that still returns the shim, retry with the plain URL.
     *   c) If both fail or the marker URL keeps returning the shim,
     *      warm the cookie by hitting the site root, then retry the
     *      original URL once more.
     *   d) Any thrown exception is caught and triggers a plain-URL
     *      fallback.
     */
    private suspend fun fetchDocument(url: String): Document? {
        val sep = if (url.contains("?")) "&" else "?"
        val markedUrl = "$url${sep}verify_human=1"

        // Browser-like headers. The site is fronted by Cloudflare
        // which gates suspicious clients behind a stricter challenge
        // than the simple JS shim, so we send full Chrome headers and
        // a realistic Accept / Accept-Language pair.
        val browserHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
        )

        fun looksLikeChallenge(doc: Document?): Boolean {
            if (doc == null) return false
            val html = doc.outerHtml()
            if (html.contains("verify_human", ignoreCase = true)) return true
            if (html.contains("Verifying your browser", ignoreCase = true)) return true
            // Cloudflare's "cf-mitigated" interstitials ship a
            // challenge-form script. The original site does not.
            if (html.contains("cf-mitigated", ignoreCase = true)) return true
            if (html.contains("__cf_chl", ignoreCase = true)) return true
            val body = doc.body()?.text().orEmpty()
            return body.contains("Checking your browser", ignoreCase = true) ||
                body.contains("Just a moment", ignoreCase = true)
        }

        // (a) marker URL with browser headers – bypasses the shim in
        // one hop and avoids the stricter CF challenge.
        return try {
            val first = app.get(markedUrl, headers = browserHeaders).document
            if (!looksLikeChallenge(first)) {
                first
            } else {
                Log.w(TAG, "marker URL returned challenge, retrying with cookies and referer")
                val cookieHeaders = browserHeaders + mapOf(
                    "Cookie" to "user_is_human=true",
                    "Referer" to "$mainUrl/",
                )
                val second = try {
                    app.get(url, headers = cookieHeaders).document
                } catch (e: Exception) {
                    Log.w(TAG, "second attempt failed: ${e.message}")
                    return@try app.get(markedUrl, headers = cookieHeaders).document
                }
                if (!looksLikeChallenge(second)) {
                    second
                } else {
                    Log.w(TAG, "second attempt also challenge, returning best-effort")
                    second
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchDocument failed for $url: ${e.message}")
            try {
                app.get(url, headers = browserHeaders).document
            } catch (e2: Exception) {
                Log.e(TAG, "top-level fallback failed: ${e2.message}")
                null
            }
        }
    }


    private data class SeriesInfo(
        val status: ShowStatus,
        val year: Int?,
        val plot: String?,
        val rating: Double?,
        val duration: Int?,
    )

    private fun parseInfoBlock(document: org.jsoup.nodes.Document): SeriesInfo {
        val plot = document
            .select("div.entry-content p, div.desc p")
            .joinToString("\n") { it.text() }
            .trim()
            .ifBlank { null }

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
                    year = value.substringBefore('-').trim().takeLast(4).toIntOrNull()
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

    // ------------------------------------------------------------
    // Element -> SearchResponse / Recommendation
    //
    // These are declared as MEMBER functions (not extensions) on the
    // provider class so we can call `fixUrl` / `fixUrlNull` directly inside
    // `?.let` blocks. As an extension on Element, `this` inside `?.let`
    // would change to the chained receiver, hiding the MainAPI receiver.
    // ------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val hrefRaw = anchor.attr("href")
        if (hrefRaw.isBlank()) return null
        val href = fixUrl(hrefRaw)

        val title = anchor.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()?.trim()
        }?.takeIf { it.isNotBlank() } ?: return null

        val posterAttr = selectFirst("img")?.getImageAttr()
        val poster = posterAttr?.let { fixUrlNull(it) }

        val looksLikeEpisode =
            Regex("[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)

        return when {
            looksLikeEpisode -> {
                val cleanTitle = title.replace(
                    Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE),
                    "",
                ).trim()
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
        val hrefRaw = anchor.attr("href")
        if (hrefRaw.isBlank()) return null
        val href = fixUrl(hrefRaw)

        val title = anchor.attr("title").ifBlank {
            this.selectFirst("div.tt")?.text()?.trim()
        }?.takeIf { it.isNotBlank() } ?: return null

        val posterAttr = selectFirst("img")?.getImageAttr()
        val poster = posterAttr?.let { fixUrlNull(it) }

        val looksLikeEpisode =
            Regex("[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)
        val type = if (looksLikeEpisode) TvType.TvSeries else TvType.Movie

        val cleanTitle = if (looksLikeEpisode) {
            title.replace(Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()
        } else title

        return newMovieSearchResponse(cleanTitle, href, type) {
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------
    // Image attribute picker
    // ------------------------------------------------------------
    private fun Element.getImageAttr(): String? {        // The wpx-hosted images (i1.wp.com, i2.wp.com) ship an extra query
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

    companion object {
        private const val TAG = "OppaDrama"
    }
}
