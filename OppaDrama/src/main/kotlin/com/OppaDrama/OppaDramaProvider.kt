// Bu plugin CloudStream OppaDrama — berdasarkan hasil uji riil Termux

package com.OppaDrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OppaDramaProvider : MainAPI() {

    override var mainUrl        = "https://oppa.biz"
    override var name           = "OppaDrama"

    override val hasMainPage    = true
    override var lang           = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage            = true

    // ------------------------------------------------------------
    //  Homepage
    // ------------------------------------------------------------
    override val mainPage = mainPageOf(
        "${mainUrl}/series/?status=&type=&order=update"                                to "Latest Update",
        "${mainUrl}/series/?status=Ongoing&type=&order=update"                         to "Ongoing",
        "${mainUrl}/series/?status=Completed&type=Drama&order=update"                  to "Completed Drama",
        "${mainUrl}/series/?country%5B%5D=china&type=Drama&order=update"               to "Drama China",
        "${mainUrl}/series/?country%5B%5D=japan&type=Drama&order=update"               to "Drama Jepang",
        "${mainUrl}/series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "${mainUrl}/series/?country%5B%5D=philippines&type=Drama&order=update"         to "Drama Philippines",
        "${mainUrl}/series/?country%5B%5D=taiwan&type=Drama&order=update"              to "Drama Taiwan",
        "${mainUrl}/series/?country%5B%5D=thailand&type=Drama&order=update"            to "Drama Thailand",
        "${mainUrl}/series/?country%5B%5D=usa&type=Drama&order=update"                 to "Drama Western",
        "${mainUrl}/series/?type=Movie&order=update"                                   to "All Movies",
        "${mainUrl}/series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Korean Movie",
        "${mainUrl}/series/?country%5B%5D=japan&type=Movie&order=update"               to "Japan Movie",
        "${mainUrl}/series/?country%5B%5D=china&type=Movie&order=update"               to "Chinese Movie",
        "${mainUrl}/series/?country%5B%5D=thailand&type=Movie&order=update"            to "Thailand Movie",
        "${mainUrl}/series/?country%5B%5D=taiwan&type=Movie&order=update"              to "Taiwan Movie",
        "${mainUrl}/series/?country%5B%5D=philippines&type=Movie&order=update"         to "Philippines Movie",
        "${mainUrl}/series/?country%5B%5D=india&type=Movie&order=update"               to "India Movie",
        "${mainUrl}/series/?country%5B%5D=united-states&type=Movie&order=update"       to "Western Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = browserHeaders()).document
        val home = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor   = selectFirst("a") ?: return null
        val href     = fixUrlNull(anchor.attr("href")) ?: return null
        val titleRaw = anchor.attr("title").ifBlank { this.selectFirst("div.tt")?.text()?.trim() }
        val title    = titleRaw?.takeIf { it.isNotBlank() } ?: return null
        val poster   = fixUrlNull(this.selectFirst("img")?.getImageAttr())

        val looksLikeEpisode = Regex(
            "[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE
        ).containsMatchIn(href)

        val cleanTitle = if (looksLikeEpisode) {
            title.replace(Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()
        } else title

        return newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.posterHeaders = imageHeaders()
        }
    }

    // ------------------------------------------------------------
    //  Search
    // ------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "${mainUrl}/?s=${query.encodeUrl()}",
            headers = browserHeaders()
        ).document
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ------------------------------------------------------------
    //  Load
    // ------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders()).document

        if (document.selectFirst("div.eplister ul > li > a") != null) {
            return loadSeries(url, document)
        }
        return loadEpisode(url, document)
    }

    private suspend fun loadSeries(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = pickPoster(document)

        val info = parseInfo(document)
        val tags  = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = document
            .select("div.spe span:has(b:matchesOwn(^Artis\$)) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val recommendations = document.select("div.listupd article.bs").mapNotNull { it.toRecommendation() }

        val episodeAnchors = document.select("div.eplister ul > li > a").toList()
        val episodes = episodeAnchors.reversed().mapIndexed { index, anchor ->
            val href     = anchor.attr("href")
            val epNumber = anchor.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull() ?: (index + 1)
            val epTitle  = anchor.selectFirst("div.epl-title")?.text()?.trim() ?: "Episode $epNumber"
            val epPoster = fixUrlNull(anchor.selectFirst("img")?.getImageAttr())

            newEpisode(href) {
                this.name      = epTitle
                this.episode   = epNumber
                this.posterUrl = epPoster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl       = poster
            this.posterHeaders   = imageHeaders()
            this.year            = info.year
            this.plot            = info.plot
            this.tags            = tags
            this.showStatus      = info.status
            this.duration        = info.duration
            this.recommendations = recommendations
            if (info.rating != null) {
                this.score = Score.from(info.rating.toString(), 10)
            }
            if (actors.isNotEmpty()) {
                this.actors = actors.map { ActorData(Actor(it)) }
            }
            if (!trailer.isNullOrBlank()) {
                this.trailers.add(TrailerData(trailer, null, false))
            }
        }
    }

    private suspend fun loadEpisode(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val title      = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val seriesName = document.selectFirst("h2[itemprop=partOfSeries], div.infolimit h2")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val poster = pickPoster(document)

        val info = parseInfo(document)
        val tags  = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = document
            .select("div.spe span:has(b:matchesOwn(^Artis\$)) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val recommendations = document.select("div.listupd article.bs").mapNotNull { it.toRecommendation() }

        val displayTitle = if (seriesName != null && !title.contains(seriesName, ignoreCase = true)) {
            "$seriesName - $title"
        } else title

        return newMovieLoadResponse(displayTitle, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.posterHeaders   = imageHeaders()
            this.year            = info.year
            this.plot            = info.plot
            this.tags            = tags
            this.duration        = info.duration
            this.recommendations = recommendations
            if (info.rating != null) {
                this.score = Score.from(info.rating.toString(), 10)
            }
            if (actors.isNotEmpty()) {
                this.actors = actors.map { ActorData(Actor(it)) }
            }
            if (!trailer.isNullOrBlank()) {
                this.trailers.add(TrailerData(trailer, null, false))
            }
        }
    }

    private fun Element.toRecommendation(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val href   = fixUrlNull(anchor.attr("href")) ?: return null
        val title  = anchor.attr("title").ifBlank { this.selectFirst("div.tt")?.text()?.trim() }
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val looksLikeEpisode = Regex(
            "[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE
        ).containsMatchIn(href)
        val type = if (looksLikeEpisode) TvType.TvSeries else TvType.Movie
        val cleanTitle = if (looksLikeEpisode) {
            title.replace(Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()
        } else title
        return newMovieSearchResponse(cleanTitle, href, type) {
            this.posterUrl = poster
            this.posterHeaders = imageHeaders()
        }
    }

    // ------------------------------------------------------------
    //  loadLinks
    // ------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data, headers = browserHeaders()).document
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: failed to fetch $data: ${e.message}")
            return false
        }

        var dispatched = false

        document.selectFirst("div.player-embed iframe")?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && loadExtractor(httpsify(src), data, subtitleCallback, callback)) {
                dispatched = true
            }
        }

        val mirrors = document.select("select.mirror option[value]:not([disabled])")
        for (option in mirrors) {
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
                // skip broken mirror
            }
        }

        for (a in document.select("div.dlbox li span.e a[href]")) {
            val href = a.attr("href").trim()
            if (href.isNotBlank() && loadExtractor(httpsify(href), data, subtitleCallback, callback)) {
                dispatched = true
            }
        }
        return dispatched
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private data class SeriesInfo(
        val status: ShowStatus,
        val year: Int?,
        val plot: String?,
        val rating: Double?,
        val duration: Int?
    )

    private fun pickPoster(document: org.jsoup.nodes.Document): String? {
        val raw = document.selectFirst("div.bigcontent img, div.thumb img")?.getImageAttr() ?: return null
        return fixUrlNull(raw)
    }

    private fun parseInfo(document: org.jsoup.nodes.Document): SeriesInfo {
        val plot = document.select("div.entry-content p, div.desc p")
            .joinToString("\n") { it.text() }
            .trim()
            .ifBlank { null }

        var status: ShowStatus = ShowStatus.Completed
        var year: Int?          = null
        var duration: Int?      = null
        var rating: Double?     = null

        for (span in document.select("div.spe > span")) {
            val label = span.selectFirst("b")?.text()?.trim()?.removeSuffix(":") ?: continue
            val value = span.ownText().trim()
            when (label.lowercase()) {
                "status" -> status = when (value.lowercase()) {
                    "ongoing" -> ShowStatus.Ongoing
                    else      -> ShowStatus.Completed
                }
                "dirilis" -> year = value.substringBefore('-').trim().takeLast(4).toIntOrNull()
                "durasi"  -> duration = parseDurationMinutes(value)
                "rating"  -> rating = value.toDoubleOrNull()
            }
        }

        if (rating == null) {
            val ratingText = document.selectFirst("div.rating strong")?.text()
            if (ratingText != null) {
                rating = ratingText.replace("Rating", "", ignoreCase = true).trim().toDoubleOrNull()
            }
        }

        return SeriesInfo(status, year, plot, rating, duration)
    }

    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val hours   = Regex("(\\d+)\\s*hr").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*min").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total   = hours * 60 + minutes
        return if (total > 0) total else null
    }

    // BERDASARKAN HASIL TERMUX: Kita ambil URL Jetpack CDN asli secara utuh beserta parameternya, 
    // namun kita paksa ganti menjadi jalur HTTPS agar lolos restriksi Cleartext internal Android OS.
    private fun Element.getImageAttr(): String? {
        val url = attr("src").ifBlank { attr("data-src") }.ifBlank { attr("data-lazy-src") }
        if (url.isBlank()) return null
        
        return if (url.startsWith("http://")) {
            "https://" + url.removePrefix("http://")
        } else {
            url
        }
    }

    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun imageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        "Referer" to mainUrl
    )

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "OppaDrama"
    }
}
