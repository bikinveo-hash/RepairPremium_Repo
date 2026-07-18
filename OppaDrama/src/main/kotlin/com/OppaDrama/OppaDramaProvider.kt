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

    // PENTING: pakai IP langsung, bukan oppa.biz. Server cuma ngasih
    // cookie `user_is_human=true` di domain 45.11.57.192. Kalo kita
    // request via oppa.biz → redirect ke IP, Cloudstream's HTTP client
    // gak nge-send cookie itu ke domain hasil redirect (cross-domain
    // issue), jadi request berikut kena shim 434-byte lagi.
    //
    // IP bisa berubah sewaktu-waktu (cek canonical link di HTML).
    // `canBeOverridden` default-nya `true` — user bisa "Clone site" di
    // settings kalo IP pindah.
    override var mainUrl        = "http://45.11.57.192"
    override var name           = "OppaDrama"

    override val hasMainPage    = true
    override var lang           = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Slow Cloudflare fronted: jalankan homepage satu-satu dengan jeda
    // supaya gak kena rate limit.
    override var sequentialMainPage            = true
    // override var sequentialMainPageDelay       = 250L
    // override var sequentialMainPageScrollDelay = 250L

    // ------------------------------------------------------------
    //  Cloudflare verify_human workaround
    // ------------------------------------------------------------
    // CF di situs ini ngasih shim 434-byte yang cuma berisi:
    //   <script>window.location.href += "?verify_human=1"</script>
    // Trick-nya: request URL pake `?verify_human=1` — server langsung
    // ngasih real content + Set-Cookie `user_is_human=true`. Request
    // berikutnya yang ngirim cookie itu bakal lewat tanpa challenge.
    //
    // `?verify_human=1` adalah "bypass" yang didokumentasiin oleh shim
    // itu sendiri — kalo browser nge-eksekusi JS, dia redirect ke URL
    // + `?verify_human=1`. Kita "jump ahead" dengan langsung pake param
    // itu dari awal.
    //
    // PENTING: flag disimpan di companion object biar share across
    // instance. Cloudstream bisa bikin instance baru per request —
    // instance flag gak akan nyimpen state.

    private suspend fun ensureCfVerified() {
        if (cfVerified) return
        try {
            // URL paling simple yang bakal return real page + Set-Cookie.
            // Cookie di-set di domain 45.11.57.192 (sama dengan mainUrl),
            // jadi request berikutnya di session yang sama bakal pake itu.
            val response = app.get(
                "${mainUrl}/?verify_human=1",
                headers = browserHeaders()
            )
            // Hanya set flag kalo kita bener-bener dapet real page.
            // Shim = 434 byte. Real page biasanya 80-180KB.
            cfVerified = response.text.length > 5_000
            if (!cfVerified) {
                Log.w(TAG, "ensureCfVerified: preflight returned suspicious payload (${response.text.length} bytes), will retry")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureCfVerified: preflight failed: ${e.message}")
        }
    }

    /**
     * Wrapper untuk GET request yang handle CF challenge. Kalo response
     * masih shim (kecil banget), ulang preflight + retry sekali. Ini
     * safety net kalo cookie somehow ke-drop antar request.
     */
    private suspend fun fetchPage(url: String): String? {
        ensureCfVerified()
        return try {
            val res = app.get(url, headers = browserHeaders())
            if (res.text.length < 5_000) {
                // Kemungkinan kena shim — reset flag + retry
                Log.w(TAG, "fetchPage: got tiny response (${res.text.length} bytes) for $url, retrying after preflight")
                cfVerified = false
                ensureCfVerified()
                app.get(url, headers = browserHeaders()).text
            } else {
                res.text
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPage: failed to fetch $url: ${e.message}")
            null
        }
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
        // request.data adalah URL lengkap (lihat mainPage di atas).
        val document = fetchPage(request.data)?.let { Jsoup.parse(it) } ?: return newHomePageResponse(request.name, emptyList())
        val home = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
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
     * Browser-like headers supaya request dari plugin gak gampang di-flag
     * Cloudflare sebagai bot. Cloudflare fronted situs ini melakukan
     * challenge kalau header terlalu "default NiceHttp".
     */
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

    /**
     * URL-encoding helper yang aman – ganti spasi jadi "+" dan
     * escape karakter khusus. Mengikuti konvensi query WordPress.
     */
    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "OppaDrama"

        @Volatile
        private var cfVerified: Boolean = false
    }
}
