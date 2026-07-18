package com.OppaDrama

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class OppaDramaProvider : MainAPI() {
    override var name = "OPPADRAMA"
    override var mainUrl = "http://45.11.57.192"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 1500L

    // Injeksi Headers & Cookie mutlak hasil sniffing lalu lintas paket data browser desktop
    private val desktopBypassHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
        "Cookie" to "user_is_human=true",
        "Upgrade-Insecure-Requests" to "1",
        "Cache-Control" to "max-age=0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    // Adopsi penuh susunan daftar kategori terlengkap dari WordPress Dramastream Engine
    override val mainPage = mainPageOf(
        Pair("${mainUrl}/series/?status=&type=&order=update", "Latest Update"),
        Pair("${mainUrl}/series/?status=Completed&type=Drama&order=update", "Completed Drama"),
        Pair("${mainUrl}/series/?country%5B%5D=china&type=Drama&order=update", "Drama China"),
        Pair("${mainUrl}/series/?country%5B%5D=japan&type=Drama&order=update", "Drama Jepang"),
        Pair("${mainUrl}/series/?country%5B%5D=south-korea&status=&type=Drama&order=update", "Drama Korea"),
        Pair("${mainUrl}/series/?country%5B%5D=philippines&type=Drama&order=update", "Drama Philippines"),
        Pair("${mainUrl}/series/?country%5B%5D=taiwan&type=Drama&order=update", "Drama Taiwan"),
        Pair("${mainUrl}/series/?country%5B%5D=thailand&type=Drama&order=update", "Drama Thailand"),
        Pair("${mainUrl}/series/?country%5B%5D=usa&type=Drama&order=update", "Drama Western"),
        Pair("${mainUrl}/series/?type=Movie&order=update", "All Movies"),
        Pair("${mainUrl}/series/?country%5B%5D=south-korea&status=&type=Movie&order=update", "Korean Movie"),
        Pair("${mainUrl}/series/?country%5B%5D=japan&type=Movie&order=update", "Japan Movie"),
        Pair("${mainUrl}/series/?country%5B%5D=china&type=Movie&order=update", "Chinese Movie"),
        Pair("${mainUrl}/series/?country%5B%5D=thailand&type=Movie&order=update", "Thailand Movie"),
        Pair("${mainUrl}/series/?country%5B%5D=taiwan&type=Movie&order=update", "Taiwan Movie"),
        Pair("${mainUrl}/series/?country%5B%5D=philippines&type=Movie&order=update", "Philippines Movie"),
        Pair("${mainUrl}/series/?country%5B%5D=india&type=Movie&order=update", "India Movie"),
        Pair("${mainUrl}/series/?country%5B%5D=united-states&type=Movie&order=update", "Western Movie")
    )

    private fun Element.extractPoster(): String? {
        val img = this.select("img").first() ?: return null
        val rawUrl = when {
            img.hasAttr("data-src") -> img.attr("data-src")
            img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
            img.hasAttr("srcset") -> img.attr("srcset").substringBefore(" ")
            else -> img.attr("src")
        }
        if (rawUrl.isNullOrBlank()) return null
        return rawUrl.replace(Regex("[?&]resize=\\d+,\\d+"), "")
                     .replace(Regex("[?&]quality=\\d+"), "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Penanganan paginasi struktur tautan dinamis arsip kategori WordPress[span_2](start_span)[span_2](end_span)
        val targetUrl = if (page > 1) {
            request.data.replace("/series/?", "/series/page/$page/?")
        } else {
            request.data
        }

        val html = app.get(targetUrl, headers = desktopBypassHeaders).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        for (element in document.select("article.bs")) {
            val anchor = element.select("div.bsx a").first()
            val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
            val link = anchor?.attr("href")
            val poster = element.extractPoster()
            val typeStr = element.select(".typez").first()?.text()

            if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                // Pembersihan judul mutlak dari deretan nomor episode atau sub teks tambahan[span_3](start_span)[span_3](end_span)
                val cleanTitle = title.replace(Regex("\\s*(?:Episode|Ep|Eps)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()

                val isMovie = request.data.contains("type=Movie") || typeStr?.contains("Movie", ignoreCase = true) == true || link.contains("/movie-")
                if (isMovie) {
                    items.add(newMovieSearchResponse(cleanTitle, link, TvType.Movie) {
                        this.posterUrl = poster
                    })
                } else {
                    items.add(newTvSeriesSearchResponse(cleanTitle, link, TvType.AsianDrama) {
                        this.posterUrl = poster
                    })
                }
            }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val html = app.get(searchUrl, headers = desktopBypassHeaders).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        for (element in document.select("article.bs")) {
            val anchor = element.select("div.bsx a").first()
            val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
            val link = anchor?.attr("href")
            val poster = element.extractPoster()
            val typeStr = element.select(".typez").first()?.text()

            if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                val cleanTitle = title.replace(Regex("\\s*(?:Episode|Ep|Eps)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()

                val isMovie = typeStr?.contains("Movie", ignoreCase = true) == true || link.contains("/movie-")
                if (isMovie) {
                    items.add(newMovieSearchResponse(cleanTitle, link, TvType.Movie) { this.posterUrl = poster })
                } else {
                    items.add(newTvSeriesSearchResponse(cleanTitle, link, TvType.AsianDrama) { this.posterUrl = poster })
                }
            }
        }
        return items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = desktopBypassHeaders).text
        val document = Jsoup.parse(html)

        var isMovie = url.contains("/movie-")
        for (span in document.select("div.spe span")) {
            val label = span.select("b").first()?.text()?.lowercase() ?: ""
            if (label.contains("tipe") || label.contains("type")) {
                if (span.text().lowercase().contains("movie")) {
                    isMovie = true
                }
            }
        }

        // Jika ini halaman episode tunggal, ambil tautan bapak serialnya lewat indeks Breadcrumb kedua[span_4](start_span)[span_4](end_span)
        val breadcrumbs = document.select(".ts-breadcrumb ol li a")
        if (breadcrumbs.size >= 3 && !isMovie) {
            val parentUrl = breadcrumbs[1].attr("href")
            if (!parentUrl.isNullOrBlank() && parentUrl != url) {
                val parentHtml = app.get(parentUrl, headers = desktopBypassHeaders).text
                return loadSeries(parentUrl, Jsoup.parse(parentHtml))
            }
        }

        return if (isMovie) loadMovie(url, document) else loadSeries(url, document)
    }

    private suspend fun loadSeries(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val title = document.select("h1.entry-title").first()?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.select("div.bigcontent img, div.thumb img").first()?.extractPoster())
        val info = parseInfo(document)

        val episodes = mutableListOf<Episode>()
        val episodeAnchors = document.select("div.eplister ul li a")
        val reversedAnchors = episodeAnchors.toList().reversed()
        
        for (i in reversedAnchors.indices) {
            val anchor = reversedAnchors[i]
            val href = anchor.attr("href")
            val epNumber = anchor.select("div.epl-num").first()?.text()?.trim()?.toIntOrNull() ?: (i + 1)
            val epTitle = anchor.select("div.epl-title").first()?.text()?.trim() ?: "Episode $epNumber"
            val epPoster = fixUrlNull(anchor.select("img").first()?.extractPoster())

            episodes.add(newEpisode(href) {
                this.name = epTitle
                this.episode = epNumber
                this.posterUrl = epPoster
            })
        }

        val recommendations = mutableListOf<SearchResponse>()
        for (element in document.select("div.listupd article.bs")) {
            element.toRecommendation()?.let { recommendations.add(it) }
        }

        val tags = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = document.select("div.spe span:has(b:matchesOwn(^Artis\$)) a").map { it.text().trim() }.filter { it.isNotBlank() }
        val trailer = document.select("div.bixbox.trailer iframe").first()?.attr("src")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = info.year
            this.plot = info.plot
            this.showStatus = info.status
            this.duration = info.duration
            this.tags = tags
            this.recommendations = recommendations
            info.rating?.let { this.score = Score.from(it, 10) }
            if (actors.isNotEmpty()) this.actors = actors.map { ActorData(Actor(it)) }
            if (!trailer.isNullOrBlank()) this.trailers.add(TrailerData(trailer, null, false))
        }
    }

    private suspend fun loadMovie(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val title = document.select("h1.entry-title").first()?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.select("div.bigcontent img, div.thumb img").first()?.extractPoster())
        val info = parseInfo(document)

        val recommendations = mutableListOf<SearchResponse>()
        for (element in document.select("div.listupd article.bs")) {
            element.toRecommendation()?.let { recommendations.add(it) }
        }

        val tags = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = document.select("div.spe span:has(b:matchesOwn(^Artis\$)) a").map { it.text().trim() }.filter { it.isNotBlank() }
        val trailer = document.select("div.bixbox.trailer iframe").first()?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = info.year
            this.plot = info.plot
            this.duration = info.duration
            this.tags = tags
            this.recommendations = recommendations
            info.rating?.let { this.score = Score.from(it, 10) }
            if (actors.isNotEmpty()) this.actors = actors.map { ActorData(Actor(it)) }
            if (!trailer.isNullOrBlank()) this.trailers.add(TrailerData(trailer, null, false))
        }
    }

    private fun Element.toRecommendation(): SearchResponse? {
        val anchor = this.select("a").first() ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.attr("title").ifBlank { this.select("div.tt").first()?.text()?.trim() }?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(this.select("img").first()?.extractPoster())
        val looksLikeEpisode = Regex("[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)
        val type = if (looksLikeEpisode) TvType.TvSeries else TvType.Movie
        val cleanTitle = title.replace(Regex("\\s*(?:Episode|Ep|Eps)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()
        
        return newMovieSearchResponse(cleanTitle, href, type) {
            this.posterUrl = poster
        }
    }

    private suspend fun parseEmbeds(doc: org.jsoup.nodes.Document, dataUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        doc.select("div.player-embed iframe").first()?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                val httpsSrc = httpsify(src)
                // Interseptor Manual untuk Custom Extractor Minochinos & Abyss jika tidak terdaftar di core[span_5](start_span)[span_5](end_span)[span_6](start_span)[span_6](end_span)
                if (!loadExtractor(httpsSrc, dataUrl, subtitleCallback, callback)) {
                    if (httpsSrc.contains("minochinos.com")) {
                        MinochinosExtractor().getUrl(httpsSrc, dataUrl, subtitleCallback, callback)
                    } else if (httpsSrc.contains("abyss.to") || httpsSrc.contains("abyssplayer.com")) {
                        AbyssExtractor().getUrl(httpsSrc, dataUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        val mirrors = doc.select("select.mirror option[value]:not([disabled])")
        for (option in mirrors) {
            val encoded = option.attr("value").trim()
            if (encoded.isBlank() || encoded.equals("Pilih Server Video", ignoreCase = true)) continue
            try {
                val decoded = base64Decode(encoded.replace("\\s".toRegex(), ""))
                val mirrorSrc = Jsoup.parse(decoded).select("iframe").first()?.let { el ->
                    el.attr("src").ifBlank { el.attr("data-src") }
                }
                if (!mirrorSrc.isNullOrBlank()) {
                    val httpsMirror = httpsify(mirrorSrc)
                    if (!loadExtractor(httpsMirror, dataUrl, subtitleCallback, callback)) {
                        if (httpsMirror.contains("minochinos.com")) {
                            MinochinosExtractor().getUrl(httpsMirror, dataUrl, subtitleCallback, callback)
                        } else if (httpsMirror.contains("abyss.to") || httpsMirror.contains("abyssplayer.com")) {
                            AbyssExtractor().getUrl(httpsMirror, dataUrl, subtitleCallback, callback)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        for (a in doc.select("div.dlbox li span.e a[href]")) {
            val href = a.attr("href").trim()
            if (href.isNotBlank()) {
                val httpsDl = httpsify(href)
                if (!loadExtractor(httpsDl, dataUrl, subtitleCallback, callback)) {
                    if (httpsDl.contains("minochinos.com")) {
                        MinochinosExtractor().getUrl(httpsDl, dataUrl, subtitleCallback, callback)
                    }
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
        val html = app.get(data, headers = desktopBypassHeaders).text
        val document = Jsoup.parse(html)

        var isMovie = data.contains("/movie-")
        for (span in document.select("div.spe span")) {
            val label = span.select("b").first()?.text()?.lowercase() ?: ""
            if (label.contains("tipe") || label.contains("type")) {
                if (span.text().lowercase().contains("movie")) {
                    isMovie = true
                }
            }
        }

        if (isMovie) {
            val pseudoEpisodes = document.select("div.eplister ul li a")
            if (pseudoEpisodes.isNotEmpty()) {
                for (anchor in pseudoEpisodes) {
                    val href = anchor.attr("href")
                    if (!href.isNullOrBlank()) {
                        val subHtml = app.get(href, headers = desktopBypassHeaders).text
                        parseEmbeds(Jsoup.parse(subHtml), href, subtitleCallback, callback)
                    }
                }
                return true
            }
        }

        parseEmbeds(document, data, subtitleCallback, callback)
        return true
    }

    private data class SeriesInfo(val status: ShowStatus, val year: Int?, val plot: String?, val rating: Double?, val duration: Int?)

    private fun parseInfo(document: org.jsoup.nodes.Document): SeriesInfo {
        val plot = document.select("div.entry-content p, div.desc p").joinToString("\n") { it.text() }.trim().ifBlank { null }
        var status: ShowStatus = ShowStatus.Completed
        var year: Int? = null
        var duration: Int? = null
        var rating: Double? = null

        for (span in document.select("div.spe > span")) {
            val labelElement = span.select("b").first() ?: continue
            val label = labelElement.text().trim().removeSuffix(":")
            val value = span.text().replace(labelElement.text(), "").trim()
            
            when (label.lowercase()) {
                "status" -> status = if (value.lowercase().contains("ongoing")) ShowStatus.Ongoing else ShowStatus.Completed
                "dirilis" -> {
                    val yearMatch = Regex("(\\d{4})").find(value)?.groupValues?.getOrNull(1)
                    year = yearMatch?.toIntOrNull()
                }
                "durasi" -> duration = parseDurationMinutes(value)
                "rating" -> rating = value.toDoubleOrNull()
            }
        }

        if (rating == null) {
            val ratingText = document.select("div.rating strong").first()?.text()
            if (ratingText != null) {
                rating = ratingText.replace("Rating", "", ignoreCase = true).trim().toDoubleOrNull()
            }
        }
        return SeriesInfo(status, year, plot, rating, duration)
    }

    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val hours = Regex("(\\d+)\\s*hr").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*min").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + minutes
        return if (total > 0) total else null
    }
}
