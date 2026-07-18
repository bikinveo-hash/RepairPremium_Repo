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

    private val desktopBypassHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
        "Cookie" to "user_is_human=true",
        "Upgrade-Insecure-Requests" to "1",
        "Cache-Control" to "max-age=0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        Pair("latest", "Update Episode Terbaru"),
        Pair("movies", "Film Pilihan"),
        Pair("ongoing", "Sedang Tayang (Ongoing)")
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
        val targetUrl = when (request.data) {
            "latest" -> if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
            "movies" -> if (page > 1) "$mainUrl/series/page/$page/?type=Movie&order=update" else "$mainUrl/"
            "ongoing" -> if (page > 1) "$mainUrl/series/page/$page/?status=Ongoing&type=&order=update" else "$mainUrl/"
            else -> "$mainUrl/"
        }

        val html = app.get(targetUrl, headers = desktopBypassHeaders).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        when (request.data) {
            "latest" -> {
                for (element in document.select("article.bs")) {
                    val anchor = element.select("div.bsx a").first()
                    val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                    val link = anchor?.attr("href")
                    val poster = element.extractPoster()
                    
                    if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                        items.add(newTvSeriesSearchResponse(title, link, TvType.AsianDrama) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
            "movies" -> {
                for (element in document.select("article.stylefor")) {
                    val anchor = element.select("div.bsx a").first()
                    val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                    val link = anchor?.attr("href")
                    val poster = element.extractPoster()

                    if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                        items.add(newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
                if (items.isEmpty()) {
                    for (element in document.select("article.bs")) {
                        val anchor = element.select("div.bsx a").first()
                        val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                        val link = anchor?.attr("href")
                        val poster = element.extractPoster()

                        if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                            items.add(newMovieSearchResponse(title, link, TvType.Movie) {
                                this.posterUrl = poster
                            })
                        }
                    }
                }
            }
            "ongoing" -> {
                if (page == 1) {
                    for (element in document.select(".ongoingseries li")) {
                        val anchor = element.select("a").first()
                        val link = anchor?.attr("href")
                        val title = anchor?.select("span.l")?.first()?.text()?.trim()
                        
                        if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                            items.add(newTvSeriesSearchResponse(title, link, TvType.AsianDrama) {
                                this.posterUrl = null
                            })
                        }
                    }
                } else {
                    for (element in document.select("article.bs")) {
                        val anchor = element.select("div.bsx a").first()
                        val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                        val link = anchor?.attr("href")
                        val poster = element.extractPoster()

                        if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                            items.add(newTvSeriesSearchResponse(title, link, TvType.AsianDrama) {
                                this.posterUrl = poster
                            })
                        }
                    }
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
                val isMovie = typeStr?.contains("Movie", ignoreCase = true) == true || link.contains("/movie-")
                if (isMovie) {
                    items.add(newMovieSearchResponse(title, link, TvType.Movie) { this.posterUrl = poster })
                } else {
                    items.add(newTvSeriesSearchResponse(title, link, TvType.AsianDrama) { this.posterUrl = poster })
                }
            }
        }
        return items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = desktopBypassHeaders).text
        val document = Jsoup.parse(html)

        // Deteksi tipe konten secara akurat dari kontainer informasi .spe span web target
        var isMovie = url.contains("/movie-")
        for (span in document.select("div.spe span")) {
            val label = span.select("b").first()?.text()?.lowercase() ?: ""
            if (label.contains("tipe") || label.contains("type")) {
                if (span.text().lowercase().contains("movie")) {
                    isMovie = true
                }
            }
        }

        // Jika ini halaman episode tunggal, ambil tautan bapak serialnya lewat indeks Breadcrumb kedua
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
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.bigcontent img, div.thumb img")?.extractPoster())
        val info = parseInfo(document)

        val episodes = mutableListOf<Episode>()
        val episodeAnchors = document.select("div.eplister ul li a")
        val reversedAnchors = episodeAnchors.toList().reversed()
        
        for (i in reversedAnchors.indices) {
            val anchor = reversedAnchors[i]
            val href = anchor.attr("href")
            val epNumber = anchor.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull() ?: (i + 1)
            val epTitle = anchor.selectFirst("div.epl-title")?.text()?.trim() ?: "Episode $epNumber"
            val epPoster = fixUrlNull(anchor.selectFirst("img")?.extractPoster())

            episodes.add(newEpisode(href) {
                this.name = epTitle
                this.episode = epNumber
                this.posterUrl = epPoster
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = info.year
            this.plot = info.plot
            this.showStatus = info.status
            this.duration = info.duration
            info.rating?.let { this.score = Score.from(it, 10) }
        }
    }

    private suspend fun loadMovie(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.bigcontent img, div.thumb img")?.extractPoster())
        val info = parseInfo(document)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = info.year
            this.plot = info.plot
            this.duration = info.duration
            info.rating?.let { this.score = Score.from(it, 10) }
        }
    }

    private suspend fun parseEmbeds(doc: org.jsoup.nodes.Document, dataUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        doc.selectFirst("div.player-embed iframe")?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) loadExtractor(httpsify(src), dataUrl, subtitleCallback, callback)
        }

        val mirrors = doc.select("select.mirror option[value]:not([disabled])")
        for (option in mirrors) {
            val encoded = option.attr("value").trim()
            if (encoded.isBlank() || encoded.equals("Pilih Server Video", ignoreCase = true)) continue
            try {
                val decoded = base64Decode(encoded.replace("\\s".toRegex(), ""))
                val mirrorSrc = Jsoup.parse(decoded).selectFirst("iframe")?.let { el ->
                    el.attr("src").ifBlank { el.attr("data-src") }
                }
                if (!mirrorSrc.isNullOrBlank()) {
                    loadExtractor(httpsify(mirrorSrc), dataUrl, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        for (a in doc.select("div.dlbox li span.e a[href]")) {
            val href = a.attr("href").trim()
            if (href.isNotBlank()) loadExtractor(httpsify(href), dataUrl, subtitleCallback, callback)
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

        // Pengalihan Multi-Kualitas Film: Gabungkan seluruh link baris pseudo-episode menjadi opsi di dalam player
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
