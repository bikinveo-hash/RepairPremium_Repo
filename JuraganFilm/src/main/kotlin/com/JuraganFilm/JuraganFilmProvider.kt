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

    override val mainPage = mainPageOf(
        "$mainUrl/kategori-film/box-office/" to "Box Office",
        "$mainUrl/kategori-film/ongoing/" to "Ongoing",
        "$mainUrl/kategori-film/drama-serial-mandarin/" to "Drama Serial Mandarin",
        "$mainUrl/kategori-film/drama-serial-korea/" to "Drama Serial Korea"
    )

    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private val baseHeaders get() = mapOf(
        "User-Agent" to mobileUserAgent,
        "Referer"    to mainUrl
    )

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

    override suspend fun load(url: String): LoadResponse? {
        val realUrl = url.substringBefore(TRAILER_SEPARATOR)
        val trailerFromUrl = url.substringAfter(TRAILER_SEPARATOR, "").takeIf { it.isNotBlank() }

        val doc = app.get(realUrl, headers = baseHeaders).document

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
            ".gmr-moviedata em p, " +
            ".entry-content p"
        )?.text()?.trim()

        val actors = doc.select("span[itemprop=actors] span[itemprop=name] a")
            .map { ActorData(Actor(it.text().trim())) }
        val tags = doc.select("a[rel=category tag]").map { it.text().trim() }
        
        val trailerUrl = doc.selectFirst("a.gmr-trailer-popup")?.attr("href") ?: trailerFromUrl

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

        val type = if (realUrl.contains("/film-seri/")) TvType.TvSeries else TvType.Movie

        fun buildTrailer(builder: LoadResponse) {
            if (!trailerUrl.isNullOrBlank()) {
                builder.trailers.add(
                    TrailerData(
                        extractorUrl = trailerUrl,
                        referer      = realUrl,
                        raw          = false,
                        headers      = mapOf("User-Agent" to mobileUserAgent)
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
                    val epUrl = if (el.tagName() == "span") realUrl else el.attr("href")
                    val epNum = el.text().trim().toIntOrNull()
        
                    newEpisode(epUrl) {
                        this.name = "Episode ${el.text().trim()}"
                        this.episode = epNum
                    }
                }.distinctBy { it.episode }

                val finalEpisodes = if (episodes.none { it.episode == 1 }) {
                    listOf(newEpisode(realUrl) {
                        this.name    = "Episode 1"
                        this.episode = 1
                    }) + episodes
                } else episodes

                newTvSeriesLoadResponse(title, realUrl, type, finalEpisodes).apply {
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
                    "$playerUrl${SEPARATOR}$realUrl"
                else
                    realUrl

                newMovieLoadResponse(title, realUrl, type, dataUrl).apply {
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
                Pair(extracted, data)
            }
        }

        if (playerUrl.isBlank()) return false

        val playerHeaders = mapOf(
            "User-Agent" to mobileUserAgent,
            "Referer"    to pageUrl,
            "Origin"     to mainUrl
        )

        val html = app.get(playerUrl, headers = playerHeaders).text
        val sourceRegex = Regex("""\{\"type\":\"(?<type>[^\"]+)\",\"label\":\"(?<label>[^\"]+)\",\"link\":\"(?<link>[^\"]+)\"\}""")
        val matches = sourceRegex.findAll(html)

        if (matches.none()) return false

        var linksFound = false

        matches.forEach { match ->
            val type = match.groups["type"]?.value ?: ""
            val label = match.groups["label"]?.value ?: ""
            val rawLink = match.groups["link"]?.value ?: ""
            val cleanLink = rawLink.replace("\\/", "/")

            if (cleanLink.isNotBlank()) {
                if (cleanLink.contains("scroll.web.id/?id=")) return@forEach
                if (cleanLink.contains("640x268")) return@forEach

                val quality = when {
                    label.contains("1080") || label.contains("Original") -> Qualities.P1080.value
                    label.contains("804") || label.contains("720") -> Qualities.P720.value
                    label.contains("536") || label.contains("480") -> Qualities.P480.value
                    else -> Qualities.P360.value
                }

                val requestHeaders = mapOf(
                    "Origin"             to "https://tv44.juragan.film",
                    "Referer"            to "https://tv44.juragan.film/",
                    "User-Agent"         to mobileUserAgent,
                    "sec-ch-ua"          to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                    "sec-ch-ua-mobile"   to "?1",
                    "sec-ch-ua-platform" to "\"Android\"",
                    "sec-fetch-dest"     to "empty",
                    "sec-fetch-mode"     to "cors",
                    "sec-fetch-site"     to "cross-site",
                    "Accept-Language"    to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
                )

                // ✅ FIX: Kembalikan deteksi tipe media secara akurat. 
                // Jangan paksa /original/ menjadi M3U8 agar player memutarnya sebagai progressive video stream langsung.
                val isHls = type == "hls" || cleanLink.contains(".m3u8")

                callback(
                    newExtractorLink(
                        source = name,
                        name   = "JuraganFilm - $label",
                        url    = cleanLink,
                        type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://tv44.juragan.film/"
                        this.quality = quality
                        this.headers = requestHeaders
                    }
                )
                linksFound = true
            }
        }

        return linksFound
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val req = chain.request()
            val urlString = req.url.toString()
            if (urlString.contains("scroll.web.id")) {
                chain.proceed(
                    req.newBuilder()
                        .header("Origin",             "https://tv44.juragan.film")
                        .header("Referer",            "https://tv44.juragan.film/")
                        .header("User-Agent",         mobileUserAgent)
                        .header("sec-ch-ua",          "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"")
                        .header("sec-ch-ua-mobile",   "?1")
                        .header("sec-ch-ua-platform", "\"Android\"")
                        .header("sec-fetch-dest",     "empty")
                        .header("sec-fetch-mode",     "cors")
                        .header("sec-fetch-site",     "cross-site")
                        .build()
                )
            } else {
                chain.proceed(req)
            }
        }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("(?i)^Nonton\\s*(Film\\s*)?"), "")
            .replace(Regex("(?i)\\s*(Subtitle Indonesia|Sub Indo|Film Seri.*|Drama Serial.*)$"), "")
            .replace(Regex("\\s*\\b(19|20)\\d{2}\\b$"), "")
            .trim()
    }

    private fun hasNextPage(doc: Document): Boolean =
        doc.selectFirst("a.next.page-numbers") != null

    private fun fixPosterUrl(url: String?): String? =
        url?.replace(Regex("-\\d+x\\d+(?=\\.(jpg|jpeg|png|webp)$)"), "")

    private fun parseWidgetItem(el: Element): SearchResponse? {
        val linkEl = el.selectFirst(".entry-title a") ?: return null
        val title  = linkEl.text().trim().takeIf { it.isNotEmpty() }?.let { cleanTitle(it) } ?: return null
        val url    = linkEl.attr("href").trim().takeIf { it.isNotEmpty() } ?: return null
        
        val trailer = el.selectFirst("a.gmr-trailer-popup")?.attr("href")
        val finalUrl = if (!trailer.isNullOrBlank()) "$url$TRAILER_SEPARATOR$trailer" else url

        val poster = fixPosterUrl(
            el.selectFirst("img.wp-post-image")
                ?.attr("data-src")?.ifBlank { null }
                ?: el.selectFirst("img.wp-post-image")?.attr("src")
        )
        val quality = el.selectFirst(".gmr-quality-item a")?.text()?.trim()
        val year    = el.selectFirst("time[itemprop=dateCreated]")
            ?.attr("datetime")?.substringBefore("-")?.toIntOrNull()
        val eps     = el.selectFirst(".strokeepisode")?.text()
        val type = if (url.contains("/film-seri/") || eps?.contains("EPS", ignoreCase = true) == true)
            TvType.TvSeries else TvType.Movie

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, finalUrl, type) {
                posterUrl = poster
                addQuality(quality ?: "")
            }
            else -> newMovieSearchResponse(title, finalUrl, type) {
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
                val title  = linkEl.text().trim().takeIf { it.isNotEmpty() }?.let { cleanTitle(it) } ?: return@mapNotNull null
                val url    = linkEl.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                
                val trailer = el.selectFirst("a.gmr-trailer-popup")?.attr("href")
                val finalUrl = if (!trailer.isNullOrBlank()) "$url$TRAILER_SEPARATOR$trailer" else url

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
                    TvType.TvSeries -> newTvSeriesSearchResponse(title, finalUrl, type) {
                        posterUrl = poster
                        addQuality(quality ?: "")
                    }
                    else -> newMovieSearchResponse(title, finalUrl, type) {
                        posterUrl  = poster
                        this.year  = year
                        addQuality(quality ?: "")
                    }
                }
            }

    companion object {
        const val SEPARATOR = "|||"
        const val TRAILER_SEPARATOR = "||trailer="
    }
}
