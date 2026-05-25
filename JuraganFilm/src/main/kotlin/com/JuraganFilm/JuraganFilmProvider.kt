package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JuraganFilmProvider : MainAPI() {
    override var name = "JuraganFilm"
    override var mainUrl = "https://tv41.juragan.film"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override val usesWebView = false

    // Kategori Home sudah dihapus, langsung fokus ke kategori grid
    override val mainPage = mainPageOf(
        "$mainUrl/kategori-film/box-office/" to "Box Office",
        "$mainUrl/kategori-film/ongoing/" to "Ongoing",
        "$mainUrl/kategori-film/drama-serial-mandarin/" to "Drama Serial Mandarin",
        "$mainUrl/kategori-film/drama-serial-korea/" to "Drama Serial Korea"
    )

    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val baseHeaders get() = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer"    to mainUrl
    )

    // =====================================================================
    // HOMEPAGE / KATEGORI
    // =====================================================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page > 1) {
            val base = request.data.trimEnd('/')
            "$base/page/$page/"
        } else {
            request.data
        }

        val doc = app.get(url, headers = baseHeaders).document
        val items = parseLatestMovieItems(doc)
        
        return if (items.isNotEmpty()) {
            newHomePageResponse(request.name, items, hasNext = hasNextPage(doc))
        } else null
    }

    // =====================================================================
    // SEARCH
    // =====================================================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (page > 1)
            "$mainUrl/page/$page/?s=$query&post_type[]=post&post_type[]=tv"
        else
            "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"

        val doc = app.get(url, headers = baseHeaders).document
        val results = (
            doc.select(".gmr-item-modulepost").mapNotNull { parseWidgetItem(it) } +
            parseLatestMovieItems(doc)
        ).distinctBy { it.url }

        return newSearchResponseList(results, hasNextPage(doc))
    }

    // =====================================================================
    // LOAD (DETAIL PAGE)
    // =====================================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = baseHeaders).document

        // Menggunakan helper cleanTitle untuk membersihkan judul SEO
        val title = doc.selectFirst("h3.entry-title, .entry-title")
            ?.text()?.trim()?.let { cleanTitle(it) } ?: return null

        val posterUrl = fixPosterUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val year = doc.selectFirst("time[itemprop=dateCreated]")
            ?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
            ?: doc.selectFirst("a[href*='/tahun/']")?.text()?.trim()?.toIntOrNull()

        val plot = doc.selectFirst(
            ".gmr-moviedata:contains(Sinopsis:) .entry-content em, " +
            ".gmr-moviedata em p, .entry-content p"
        )?.text()?.trim()

        val actors = doc.select("span[itemprop=actors] span[itemprop=name] a")
            .map { ActorData(Actor(it.text().trim())) }
        val tags = doc.select("a[rel=category tag]").map { it.text().trim() }
        val trailerUrl = doc.selectFirst("a.gmr-trailer-popup")?.attr("href")

        val iframeEl = doc.selectFirst("iframe[id^=jf-frame-]")
        val playerUrl = iframeEl?.attr("src")
            ?.replace("&#038;", "&")
            ?.replace("&amp;", "&")
            ?.let { src ->
                when {
                    src.startsWith("//")  -> "https:$src"
                    src.startsWith("/")   -> "$mainUrl$src"
                    else                  -> src
                }
            } ?: ""

        val type = if (url.contains("/film-seri/")) TvType.TvSeries else TvType.Movie

        fun buildTrailer(builder: LoadResponse) {
            if (!trailerUrl.isNullOrBlank()) {
                builder.trailers.add(
                    TrailerData(
                        extractorUrl = trailerUrl,
                        referer      = url,
                        raw          = false,
                        headers      = mapOf("User-Agent" to USER_AGENT)
                    )
                )
            }
        }

        return when (type) {
            TvType.TvSeries -> {
                val epElements = doc.select(
                    ".entry-content .post-page-numbers, " +
                    "article .post-page-numbers, " +
                    ".jf-eps-wrap .post-page-numbers, " +
                    ".post-page-numbers"
                )

                val episodes = epElements.map { el ->
                    val epUrl = if (el.tagName() == "span") url else el.attr("href")
                    val epNum = el.text().trim().toIntOrNull()
                    newEpisode(epUrl) {
                        this.name    = "Episode ${el.text().trim()}"
                        this.episode = epNum
                    }
                }.distinctBy { it.episode }

                val finalEpisodes = if (episodes.none { it.episode == 1 }) {
                    listOf(newEpisode(url) {
                        this.name    = "Episode 1"
                        this.episode = 1
                    }) + episodes
                } else episodes

                newTvSeriesLoadResponse(title, url, type, finalEpisodes).apply {
                    this.posterUrl = posterUrl
                    this.year      = year
                    this.plot      = plot
                    this.tags      = tags
                    this.actors    = actors
                    buildTrailer(this)
                }
            }

            else -> {
                val dataUrl = if (playerUrl.isNotBlank())
                    "$playerUrl${SEPARATOR}$url"
                else
                    url

                newMovieLoadResponse(title, url, type, dataUrl).apply {
                    this.posterUrl = posterUrl
                    this.year      = year
                    this.plot      = plot
                    this.tags      = tags
                    this.actors    = actors
                    buildTrailer(this)
                }
            }
        }
    }

    // =====================================================================
    // LOAD LINKS
    // =====================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val (playerUrl, pageUrl) = when {
            data.contains(SEPARATOR) -> {
                val parts = data.split(SEPARATOR)
                Pair(parts[0], parts[1])
            }
            data.contains("/file/") -> {
                Pair(data, mainUrl)
            }
            else -> {
                val doc = app.get(data, headers = baseHeaders).document
                val extracted = doc.selectFirst("iframe[id^=jf-frame-]")
                    ?.attr("src")
                    ?.replace("&#038;", "&")
                    ?.replace("&amp;", "&")
                    ?.let { src ->
                        when {
                            src.startsWith("//") -> "https:$src"
                            src.startsWith("/")  -> "$mainUrl$src"
                            else                 -> src
                        }
                    } ?: ""

                if (extracted.isBlank()) {
                    return loadExtractor(data, referer = mainUrl, subtitleCallback, callback)
                }
                Pair(extracted, data)
            }
        }

        val playerHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer"    to pageUrl,
            "Origin"     to mainUrl
        )

        val html = app.get(playerUrl, headers = playerHeaders).text

        val m3u8Regex = Regex("""https://cloud\.wth\.my\.id/\?id=[^"'\s]+\.m3u8""")
        val match = m3u8Regex.find(html)

        if (match != null) {
            callback(
                newExtractorLink(
                    source = name,
                    name   = "JuraganFilm - HLS",
                    url    = match.value,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = playerUrl
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "Origin"     to mainUrl,
                        "Referer"    to playerUrl,
                        "User-Agent" to mobileUserAgent
                    )
                }
            )
            return true
        }

        return loadExtractor(playerUrl, referer = pageUrl, subtitleCallback, callback)
    }

    // =====================================================================
    // VIDEO INTERCEPTOR
    // =====================================================================
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val req = chain.request()
            if (req.url.toString().contains("cloud.wth.my.id")) {
                chain.proceed(
                    req.newBuilder()
                        .header("Origin",     mainUrl)
                        .header("Referer",    extractorLink.referer ?: mainUrl)
                        .header("User-Agent", mobileUserAgent)
                        .build()
                )
            } else {
                chain.proceed(req)
            }
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================
    
    /**
     * Memotong dan membersihkan sampah keyword SEO dari situs agar hanya menyisakan judul asli.
     */
    private fun cleanTitle(title: String): String {
        return title
            // Hapus "Nonton " atau "Nonton Film " di bagian awal
            .replace(Regex("(?i)^Nonton\\s*(Film\\s*)?"), "")
            // Hapus embel-embel akhir seperti kategori web dan Sub Indo
            .replace(Regex("(?i)\\s*(Subtitle Indonesia|Sub Indo|Film Seri.*|Drama Serial.*)$"), "")
            // Jika ada tahun rilis di ujung akhir judul yang tertinggal, hapus juga
            .replace(Regex("\\s*\\b(19|20)\\d{2}\\b$"), "")
            .trim()
    }

    private fun hasNextPage(doc: Document): Boolean =
        doc.selectFirst("a.next.page-numbers") != null

    private fun fixPosterUrl(url: String?): String? =
        url?.replace(Regex("-\\d+x\\d+(?=\\.(jpg|jpeg|png|webp)$)"), "")

    private fun parseSliderItems(doc: Document): List<SearchResponse> =
        doc.select(".gmr-slider-content").mapNotNull { el ->
            val linkEl = el.selectFirst("a.gmr-slide-titlelink") ?: return@mapNotNull null
            // Membersihkan judul di slider (jika nantinya dipanggil lagi)
            val title  = linkEl.text().trim().takeIf { it.isNotEmpty() }?.let { cleanTitle(it) } ?: return@mapNotNull null
            val url    = linkEl.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val poster = fixPosterUrl(
                el.selectFirst("img.tns-lazy-img")
                    ?.attr("data-src")?.ifBlank { null }
                    ?: el.selectFirst("img.tns-lazy-img")?.attr("src")
            )
            val eps = el.selectFirst(".strokeepisode")?.text()
            val type = if (url.contains("/film-seri/") ||
                eps?.contains("EPS", ignoreCase = true) == true)
                TvType.TvSeries else TvType.Movie

            when (type) {
                TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) { posterUrl = poster }
                else            -> newMovieSearchResponse(title, url, type) { posterUrl = poster }
            }
        }

    private fun parseWidgetItem(el: Element): SearchResponse? {
        val linkEl = el.selectFirst(".entry-title a") ?: return null
        // Membersihkan judul pada widget
        val title  = linkEl.text().trim().takeIf { it.isNotEmpty() }?.let { cleanTitle(it) } ?: return null
        val url    = linkEl.attr("href").trim().takeIf { it.isNotEmpty() } ?: return null
        val poster = fixPosterUrl(
            el.selectFirst("img.wp-post-image")
                ?.attr("data-src")?.ifBlank { null }
                ?: el.selectFirst("img.wp-post-image")?.attr("src")
        )
        val quality = el.selectFirst(".gmr-quality-item a")?.text()?.trim()
        val year    = el.selectFirst("time[itemprop=dateCreated]")
            ?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
        val eps     = el.selectFirst(".strokeepisode")?.text()
        val type    = if (url.contains("/film-seri/") ||
            eps?.contains("EPS", ignoreCase = true) == true)
            TvType.TvSeries else TvType.Movie

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) {
                posterUrl = poster
                addQuality(quality ?: "")
            }
            else -> newMovieSearchResponse(title, url, type) {
                posterUrl  = poster
                this.year  = year
                addQuality(quality ?: "")
            }
        }
    }

    private fun parseLatestMovieItems(doc: Document): List<SearchResponse> =
        doc.select("#gmr-main-load article.item, #primary article.item")
            .mapNotNull { el ->
                val linkEl = el.selectFirst(".entry-title a") ?: return@mapNotNull null
                // Membersihkan judul pada item pencarian dan kategori
                val title  = linkEl.text().trim().takeIf { it.isNotEmpty() }?.let { cleanTitle(it) } ?: return@mapNotNull null
                val url    = linkEl.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val poster = fixPosterUrl(
                    el.selectFirst("img.wp-post-image")
                        ?.attr("data-src")?.ifBlank { null }
                        ?: el.selectFirst("img.wp-post-image")?.attr("src")
                )
                val quality = el.selectFirst(".gmr-quality-item a")?.text()?.trim()
                val year    = el.selectFirst("time[itemprop=dateCreated]")
                    ?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
                val type    = if (url.contains("/film-seri/")) TvType.TvSeries else TvType.Movie

                when (type) {
                    TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) {
                        posterUrl = poster
                        addQuality(quality ?: "")
                    }
                    else -> newMovieSearchResponse(title, url, type) {
                        posterUrl  = poster
                        this.year  = year
                        addQuality(quality ?: "")
                    }
                }
            }

    companion object {
        const val SEPARATOR = "|||"
    }
}
