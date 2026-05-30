package com.Rebahin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.autos"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/series/page/" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.ml-mask") ?: return null
        val title = a.attr("title")
        val href = fixUrlNull(a.attr("href")) ?: return null

        val img = this.selectFirst("img.mli-thumb")
        val posterUrl = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        )

        val qualityStr = this.selectFirst("span.mli-quality")?.text()?.lowercase()
        val qualityResult = when {
            qualityStr == null -> null
            qualityStr.contains("fhd") || qualityStr.contains("hd") -> SearchQuality.HD
            qualityStr.contains("blu") -> SearchQuality.BlueRay
            qualityStr.contains("cam") -> SearchQuality.Cam
            qualityStr.contains("sd") -> SearchQuality.SD
            else -> null
        }

        val isTvSeries = href.contains("/series/") || href.contains("season")
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = qualityResult
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = qualityResult
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h3[itemprop=name]")?.text()
            ?: return null

        val mviCover = document.selectFirst("a.mvi-cover")
        val background = fixUrlNull(
            mviCover?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")
                ?.removeSurrounding("'")?.removeSurrounding("\"")
        )

        val poster = fixUrlNull(document.selectFirst("meta[itemprop=image]")?.attr("content"))
            ?: fixUrlNull(
                document.selectFirst("div.mvic-thumb")?.attr("style")
                    ?.substringAfter("url(")?.substringBefore(")")
                    ?.removeSurrounding("'")?.removeSurrounding("\"")
            )

        val plot = document.selectFirst("div.sinopsis-indo, div.desc, div.rt-Text")
            ?.text()
            ?.replace("Nonton Film.*Sub Indo \\| REBAHIN".toRegex(RegexOption.IGNORE_CASE), "")
            ?.trim()

        val ratingText = document
            .selectFirst("span.irank-voters, span.rating, div.btn-danger.averagerate")
            ?.text()?.replace(",", ".")?.trim()
        val parsedScore = ratingText?.toFloatOrNull()?.let { Score.from10(it) }

        var year: Int? = null
        var duration: Int? = null

        document.select("div.mvic-info p").forEach { p ->
            val text = p.text()
            when {
                text.contains("Release Date:") -> {
                    year = p.selectFirst("meta[itemprop=datePublished]")?.attr("content")
                        ?.substringBefore("-")?.toIntOrNull()
                        ?: text.substringAfter("Release Date:").trim()
                            .substringBefore("-").toIntOrNull()
                }
                text.contains("Duration:") -> {
                    duration = text.replace(Regex("[^0-9]"), "").toIntOrNull()
                }
            }
        }

        val isTvSeries = url.contains("/series/")

        return if (isTvSeries) {
            // ----------------------------------------------------------------
            // TV SERIES: fetch halaman /play untuk ambil daftar episode
            // URL play bisa dari mvi-cover href, fallback ke url/play
            // ----------------------------------------------------------------
            val playUrl = fixUrlNull(mviCover?.attr("href")) ?: "$url/play"
            val watchDocument = app.get(playUrl).document
            val episodes = mutableListOf<Episode>()

            watchDocument.select("div#list-eps a.btn-eps").forEach { epElem ->
                val epName = epElem.text().trim()
                val base64Iframe = epElem.attr("data-iframe")
                val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()

                if (base64Iframe.isNotBlank()) {
                    episodes.add(newEpisode(base64Iframe) {
                        this.name = epName
                        this.episode = epNum
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.score = parsedScore
                this.year = year
                this.duration = duration
            }
        } else {
            // ----------------------------------------------------------------
            // MOVIE: kirim URL halaman /play sebagai data ke loadLinks
            // FIX: sebelumnya kirim url utama, sekarang kirim url/play
            // ----------------------------------------------------------------
            val playUrl = fixUrlNull(mviCover?.attr("href")) ?: "$url/play"
            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.score = parsedScore
                this.year = year
                this.duration = duration
            }
        }
    }

    // ------------------------------------------------------------------------
    // Decode base64 data-iframe → URL player asli
    // Mendukung dua lapis encoding:
    //   Layer 1: data-iframe (base64) → /iembed/?source=BASE64 atau URL langsung
    //   Layer 2: ?source= param (base64) → URL player asli (misal AbyssPlayer)
    // ------------------------------------------------------------------------
    private fun decodeIframeSrc(raw: String): String? {
        if (raw.isBlank()) return null

        // Sudah berupa URL langsung (TV Series kadang langsung dapat URL)
        if (raw.startsWith("http")) return raw

        return try {
            val decoded = String(Base64.decode(raw.trim(), Base64.DEFAULT))
            when {
                // Hasil decode adalah /iembed/?source=BASE64 → decode lagi
                decoded.contains("/iembed/?source=") || decoded.contains("iembed") -> {
                    val innerBase64 = decoded.substringAfter("source=").substringBefore("&").trim()
                    try {
                        String(Base64.decode(innerBase64, Base64.DEFAULT))
                    } catch (e: Exception) {
                        decoded // Gagal decode layer 2, pakai hasil layer 1
                    }
                }
                // Hasil decode langsung berupa URL
                decoded.startsWith("http") -> decoded
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urlsToExtract = mutableListOf<String>()

        when {
            // ----------------------------------------------------------------
            // CASE 1: data adalah base64 (dari episode TV Series)
            // ----------------------------------------------------------------
            !data.startsWith("http") -> {
                decodeIframeSrc(data)?.let { urlsToExtract.add(it) }
            }

            // ----------------------------------------------------------------
            // CASE 2: data adalah URL halaman /play (dari Movie)
            // Fetch halaman, cari semua div.server dengan data-iframe
            // ----------------------------------------------------------------
            else -> {
                val playDoc = app.get(data).document

                // Selector sesuai hasil inspect: div.server dan div.server-active
                playDoc.select("div.server, div.server-active").forEach { server ->
                    val raw = server.attr("data-iframe")
                    if (raw.isNotBlank()) {
                        decodeIframeSrc(raw)?.let { urlsToExtract.add(it) }
                    }
                }

                // Fallback: cari iframe langsung (selain YouTube/sosmed)
                if (urlsToExtract.isEmpty()) {
                    playDoc.select("iframe").forEach { iframe ->
                        val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
                        val isSkip = src.contains("youtube.com") ||
                                src.contains("facebook.com") ||
                                src.contains("twitter.com") ||
                                src.contains("googleusercontent.com")
                        if (!isSkip && src.isNotBlank()) {
                            // Jika iframe src adalah /iembed/?source=, decode dulu
                            if (src.contains("iembed") && src.contains("source=")) {
                                val innerBase64 = src.substringAfter("source=")
                                    .substringBefore("&").trim()
                                try {
                                    val decodedUrl = String(Base64.decode(innerBase64, Base64.DEFAULT))
                                    if (decodedUrl.startsWith("http")) urlsToExtract.add(decodedUrl)
                                } catch (e: Exception) {
                                    urlsToExtract.add(src)
                                }
                            } else {
                                urlsToExtract.add(src)
                            }
                        }
                    }
                }
            }
        }

        // --------------------------------------------------------------------
        // Extract semua URL yang sudah terkumpul
        // --------------------------------------------------------------------
        urlsToExtract.distinct().forEach { targetUrl ->
            if (!targetUrl.startsWith("http")) return@forEach

            // 1. Coba extractor bawaan CloudStream (AbyssPlayer, Filemoon, dll)
            val loaded = loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)

            // 2. Fallback manual jika extractor gagal
            if (!loaded) {
                try {
                    val playerHtml = app.get(targetUrl, referer = mainUrl).text
                    val unpackedHtml = getAndUnpack(playerHtml)

                    var found = false

                    // Cari URL m3u8 / mp4 / fd
                    Regex("""(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+(?:\.m3u8|\.mp4|\.fd)[^"']*)["']""")
                        .findAll(unpackedHtml)
                        .forEach { match ->
                            found = true
                            val link = match.groupValues[1]
                            val isM3u8 = link.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                                    url = link,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = targetUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }

                    // Fallback paling akhir: cari URL apapun yang mengandung ekstensi video
                    if (!found) {
                        Regex("""["'](https?://[^"']+(?:\.m3u8|\.mp4|\.fd)[^"']*)["']""")
                            .findAll(unpackedHtml)
                            .forEach { match ->
                                val link = match.groupValues[1]
                                val isM3u8 = link.contains(".m3u8")
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = this.name + " (Backup)",
                                        url = link,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = targetUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                    }
                } catch (e: Exception) {
                    // Silent fail, lanjut ke URL berikutnya
                }
            }
        }

        return true
    }
}
