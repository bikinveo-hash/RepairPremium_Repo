// Bu plugin CloudStream OppaDrama — sumber https://oppa.biz

package com.OppaDrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OppaDramaProvider : MainAPI() {

    // PENTING: pakai IP langsung, bukan oppa.biz. Server ngasih cookie
    // `user_is_human=true` di domain 45.11.57.192. Kalo kita request
    // via oppa.biz → redirect ke IP, cookie jadi cross-domain dan Cloudstream's
    // HTTP client gak kirim ke domain hasil redirect.
    //
    // IP bisa berubah sewaktu-waktu (cek canonical link di HTML). User bisa
    // "Clone site" di settings kalo IP pindah.
    override var mainUrl        = "http://45.11.57.192"
    override var name           = "OppaDrama"

    override val hasMainPage    = true
    override var lang           = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Slow Cloudflare fronted: jalankan homepage satu-satu dengan jeda
    // supaya gak kena rate limit.
    override var sequentialMainPage            = true

    init {
        Log.i(TAG, "OppaDramaProvider instantiated, mainUrl=$mainUrl")
    }

    // ------------------------------------------------------------
    //  Cloudflare verify_human workaround
    // ------------------------------------------------------------
    // CF ngasih shim 434-byte yang redirect via JS ke URL + "?verify_human=1".
    // Server response untuk `?verify_human=1` adalah:
    //   - 302 Found
    //   - Set-Cookie: user_is_human=true; Max-Age=86400
    //   - Location: /
    //
    // Setelah dapat cookie, request berikut ke `/` (atau URL lain) dengan
    // cookie itu bakal dapet real content langsung.
    //
    // MASALAH UTAMA: Cloudstream's HTTP client TIDAK persist cookie
    // across requests. Cuma dapet Set-Cookie dari 302, tapi gak pake di
    // request berikutnya.
    //
    // SOLUSI: kita SET COOKIE MANUAL di setiap request. Cookie value
    // di-hardcode di sini. Kalo user buka situs di browser, browser
    // dapet cookie yang sama persis ("user_is_human=true"). Kita pake
    // value itu.
    //
    // Kalo cookie expired (Max-Age 24 jam), user perlu:
    //   1. Buka situs di browser, solve challenge, dapet cookie baru
    //   2. Atau update CF_COOKIE constant di sini
    //   3. Atau "Clone site" di Cloudstream settings (mungkin)
    //
    // Untuk sekarang, hardcode value yang umum ("user_is_human=true").
    // CF hanya butuh presence, value exact match "true" yang penting.

    private suspend fun ensureCfVerified() {
        // Sekarang gak perlu preflight — cookie di-set manual di headers.
        // Method ini kept untuk backward compatibility (dipanggil di fetchPage)
        // tapi jadi no-op.
        if (cfVerified) return
        Log.i(TAG, "ensureCfVerified: using manual cookie, skipping preflight")
        cfVerified = true
    }

    /**
     * Wrapper untuk GET request dengan CF cookie. Kalo response masih
     * shim, log warning. Real fix kalo ini kejadian: user perlu refresh
     * cookie (lihat comment CF_COOKIE di atas).
     */
    private suspend fun fetchPage(url: String): String? {
        ensureCfVerified()
        val res  = app.get(url, headers = browserHeaders())
        val body = res.text
        val isShim = body.contains("verify_human") || body.contains("window.location")
        Log.i(TAG, "fetchPage: $url → ${body.length} bytes, shim=$isShim")
        if (isShim) {
            Log.w(TAG, "fetchPage: got CF shim. CF_COOKIE may have expired.")
        }
        return body.ifBlank { null }
    }

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
        val html = fetchPage(request.data)
        if (html == null) {
            Log.w(TAG, "getMainPage: fetchPage returned null for ${request.name}")
            return newHomePageResponse(request.name, emptyList())
        }
        val document = Jsoup.parse(html)
        val articles = document.select("div.listupd article.bs")
        Log.i(TAG, "getMainPage: ${request.name} → found ${articles.size} article.bs, html=${html.length}b")
        val home = articles.mapNotNull { it.toSearchResult() }
        Log.i(TAG, "getMainPage: ${request.name} → parsed ${home.size} search results")
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor   = selectFirst("a") ?: return null
        val href     = fixUrlNull(anchor.attr("href")) ?: return null
        val titleRaw = anchor.attr("title").ifBlank { this.selectFirst("div.tt")?.text()?.trim() }
        val title    = titleRaw?.takeIf { it.isNotBlank() } ?: return null
        val poster   = fixUrlNull(this.selectFirst("img")?.getImageAttr())

        // URL mengandung "episode-N" untuk halaman episode tunggal.
        val looksLikeEpisode = Regex(
            "[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE
        ).containsMatchIn(href)

        val cleanTitle = if (looksLikeEpisode) {
            title.replace(Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()
        } else title

        return newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------
    //  Search
    // ------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val html = fetchPage("${mainUrl}/?s=${query.encodeUrl()}") ?: return emptyList()
        val document = Jsoup.parse(html)
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ------------------------------------------------------------
    //  Load
    // ------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val html = fetchPage(url) ?: return null
        val document = Jsoup.parse(html)

        // Halaman series -> div.eplister
        if (document.selectFirst("div.eplister ul > li > a") != null) {
            return loadSeries(url, document)
        }
        // Halaman episode tunggal
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

        // Episode list – newest first di DOM; reverse untuk natural numbering
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
        val html = fetchPage(data) ?: return false
        val document = Jsoup.parse(html)

        var dispatched = false

        // (1) Primary player iframe
        document.selectFirst("div.player-embed iframe")?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && loadExtractor(httpsify(src), data, subtitleCallback, callback)) {
                dispatched = true
            }
        }

        // (2) Mirror dropdown – value-nya adalah base64-encoded <iframe>
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

        // (3) Direct download links
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

    private fun Element.getImageAttr(): String? {
        // Hapus query Jetpack "resize=" agar dapat gambar original.
        fun cleanup(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return url
                .replace(Regex("[?&]resize=\\d+,\\d+"), "")
                .replace(Regex("[?&]quality=\\d+"), "")
        }
        return when {
            hasAttr("data-src")      -> cleanup(attr("abs:data-src"))
            hasAttr("data-lazy-src") -> cleanup(attr("abs:data-lazy-src"))
            hasAttr("srcset")        -> cleanup(attr("abs:srcset").substringBefore(" "))
            hasAttr("src")           -> cleanup(attr("abs:src"))
            else                     -> null
        }
    }

    /**
     * Browser-like headers + CF cookie. Cookie di-set manual karena
     * Cloudstream's HTTP client gak persist cookie dari 302 ke request
     * berikutnya.
     */
    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding" to "gzip, deflate",
        "Cookie" to CF_COOKIE,
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    /**
     * URL-encoding helper yang aman – ganti spasi jadi "+" dan
     * escape karakter khusus. Mengikuti konvensi query WordPress.
     */
    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "OppaDrama"

        // CF cookie — set manual karena Cloudstream's HTTP client gak
        // persist cookie dari 302. Value ini dari browser yang solve
        // challenge. Max-Age 86400 (24 jam). Kalo expired, user perlu
        // refresh: buka situs di browser, atau ganti value di sini.
        private const val CF_COOKIE = "user_is_human=true"

        @Volatile
        private var cfVerified: Boolean = false
    }
}
