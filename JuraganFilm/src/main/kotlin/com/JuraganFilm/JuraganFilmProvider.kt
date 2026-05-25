package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JuraganFilmProvider : MainAPI() {
    override var name = "JuraganFilm"
    override var mainUrl = "https://tv44.juragan.film"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override val usesWebView = false

    // Halaman utama + kategori
    override val mainPage = listOf(
        mainPage("$mainUrl/", "Home"),
        mainPage("$mainUrl/kategori-film/box-office/", "Box Office"),
        mainPage("$mainUrl/kategori-film/ongoing/", "Ongoing"),
        mainPage("$mainUrl/kategori-film/drama-serial-mandarin/", "Drama Serial Mandarin"),
        mainPage("$mainUrl/kategori-film/drama-serial-korea/", "Drama Serial Korea")
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
        val isHome = request.data == "$mainUrl/"
        val url = if (page > 1) {
            val base = request.data.trimEnd('/')
            "$base/page/$page/"
        } else {
            request.data
        }

        val doc = app.get(url, headers = baseHeaders).document

        return if (isHome) {
            // Tampilan homepage lengkap
            val homeLists = mutableListOf<HomePageList>()

            val sliderItems = parseSliderItems(doc)
            if (sliderItems.isNotEmpty())
                homeLists.add(HomePageList("Featured", sliderItems, isHorizontalImages = true))

            doc.select(".home-widget").forEach { section ->
                val title = section.selectFirst(".homemodule-title, .widget-title")
                    ?.text()?.trim() ?: return@forEach
                val items = section.select(".gmr-item-modulepost")
                    .mapNotNull { parseWidgetItem(it) }
                if (items.isNotEmpty())
                    homeLists.add(HomePageList(title, items))
            }

            val latestItems = parseLatestMovieItems(doc)
            if (latestItems.isNotEmpty())
                homeLists.add(HomePageList("Latest Movie", latestItems))

            if (homeLists.isEmpty()) null
            else newHomePageResponse(homeLists, hasNext = hasNextPage(doc))
        } else {
            // Halaman kategori: tampilkan daftar item saja
            val items = doc.select(".gmr-item-modulepost").mapNotNull { parseWidgetItem(it) }
            if (items.isNotEmpty())
                newHomePageResponse(request.name, items, hasNext = hasNextPage(doc))
            else null
        }
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

        val title = doc.selectFirst("h3.entry-title, .entry-title")
            ?.text()?.trim() ?: return null

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
    private fun hasNextPage(doc: Document): Boolean =
        doc.selectFirst("ul.page-numbers li a.next") != null

    private fun fixPosterUrl(url: String?): String? =
        url?.replace(Regex("-\\d+x\\d+(?=\\.(jpg|jpeg|png|webp)$)"), "")

    private fun parseSliderItems(doc: Document): List<SearchResponse> =
        doc.select(".gmr-slider-content").mapNotNull { el ->
            val linkEl = el.selectFirst("a.gmr-slide-titlelink") ?: return@mapNotNull null
            val title  = linkEl.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
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
        val title  = linkEl.text().trim().takeIf { it.isNotEmpty() } ?: return null
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
                val title  = linkEl.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
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
