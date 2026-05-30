package com.Rebahin

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
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
                this.posterUrl = posterUrl; this.quality = qualityResult
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl; this.quality = qualityResult
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
            mviCover?.attr("style")?.substringAfter("url(")?.substringBefore(")")
                ?.removeSurrounding("'")?.removeSurrounding("\"")
        )
        val playUrl = fixUrlNull(mviCover?.attr("href"))
            ?: if (url.contains("/series/")) "$url/watch" else "$url/play"
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
                        ?: text.substringAfter("Release Date:").trim().substringBefore("-").toIntOrNull()
                }
                text.contains("Duration:") -> {
                    duration = text.replace(Regex("[^0-9]"), "").toIntOrNull()
                }
            }
        }

        val isTvSeries = url.contains("/series/")
        return if (isTvSeries) {
            val watchDocument = app.get(playUrl).document
            val episodes = mutableListOf<Episode>()
            watchDocument.select("div#list-eps a.btn-eps").forEach { epElem ->
                val epName = epElem.text().trim()
                val base64Iframe = epElem.attr("data-iframe")
                val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                if (base64Iframe.isNotBlank()) {
                    episodes.add(newEpisode(base64Iframe) {
                        this.name = epName; this.episode = epNum
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.backgroundPosterUrl = background
                this.plot = plot; this.score = parsedScore
                this.year = year; this.duration = duration
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster; this.backgroundPosterUrl = background
                this.plot = plot; this.score = parsedScore
                this.year = year; this.duration = duration
            }
        }
    }

    // Decode base64 data-iframe, tangani double encoding
    private fun decodeIframeSrc(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("http")) return raw
        return try {
            val decoded = String(Base64.decode(raw.trim(), Base64.DEFAULT))
            when {
                decoded.contains("iembed") && decoded.contains("source=") -> {
                    val inner = decoded.substringAfter("source=").substringBefore("&").trim()
                    try {
                        val url = String(Base64.decode(inner, Base64.DEFAULT))
                        if (url.startsWith("http")) url else decoded
                    } catch (e: Exception) { decoded }
                }
                decoded.startsWith("http") -> decoded
                else -> null
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val playerUrls = mutableListOf<String>()

        when {
            !data.startsWith("http") -> {
                decodeIframeSrc(data)?.let { playerUrls.add(it) }
            }
            else -> {
                val playDoc = app.get(data).document
                // Selector sesuai hasil inspect: div.server dan div.server-active
                playDoc.select("div.server, div.server-active").forEach { server ->
                    val raw = server.attr("data-iframe")
                    if (raw.isNotBlank()) {
                        decodeIframeSrc(raw)?.let { playerUrls.add(it) }
                    }
                }
                // Fallback iframe
                if (playerUrls.isEmpty()) {
                    playDoc.select("iframe").forEach { iframe ->
                        val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
                        val skip = listOf("youtube.com", "facebook.com", "twitter.com", "googleusercontent.com")
                        if (skip.none { src.contains(it) } && src.isNotBlank()) {
                            if (src.contains("iembed") && src.contains("source=")) {
                                val inner = src.substringAfter("source=").substringBefore("&")
                                try {
                                    val decoded = String(Base64.decode(inner, Base64.DEFAULT))
                                    if (decoded.startsWith("http")) playerUrls.add(decoded)
                                } catch (e: Exception) { playerUrls.add(src) }
                            } else playerUrls.add(src)
                        }
                    }
                }
            }
        }

        playerUrls.distinct().forEach { playerUrl ->
            if (!playerUrl.startsWith("http")) return@forEach

            val domain = playerUrl.substringBefore("/", "").let {
                Regex("""https?://[^/]+""").find(playerUrl)?.value ?: playerUrl
            }

            // ── Coba loadExtractor standar dulu ──────────────────────
            val extractorLoaded = loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
            if (extractorLoaded) return@forEach

            // ── WebViewResolver: buka player di browser, intercept video request ──
            try {
                val cookieManager = CookieManager.getInstance()

                // Intercept semua request yang merupakan video
                val videoRegex = Regex(
                    """https?://[^\s"'<>]+(?:\.m3u8|\.mp4|\.ts)(?:[^\s"'<>]*)?"""
                )

                val (interceptedRequest, collectedRequests) = WebViewResolver(
                    interceptUrl  = videoRegex,
                    additionalUrls = listOf(videoRegex),
                    timeout        = 30_000L
                ).resolveUsingWebView(
                    url     = playerUrl,
                    referer = mainUrl,
                    requestCallBack = { req ->
                        val u = req.url.toString()
                        // Stop segera setelah dapat URL video
                        u.contains(".m3u8") || u.contains(".mp4")
                    }
                )

                // Gabung: request utama + semua collected requests
                val allRequests = buildList {
                    interceptedRequest?.let { add(it) }
                    addAll(collectedRequests)
                }.distinctBy { it.url.toString() }

                allRequests.forEach { req ->
                    val url   = req.url.toString()
                    val isM3u8 = url.contains(".m3u8")
                    val isMp4  = url.contains(".mp4")
                    if (!isM3u8 && !isMp4) return@forEach

                    // ── Kunci anti-403: ambil cookie dari WebView ──────
                    val finalHeaders = mutableMapOf<String, String>()

                    // Salin semua header dari request WebView
                    req.headers.forEach { (k, v) -> finalHeaders[k] = v }

                    // Ambil cookie dari WebView CookieManager untuk domain player
                    val cookiePlayer  = cookieManager.getCookie(playerUrl) ?: ""
                    val cookieVideo   = cookieManager.getCookie(url)       ?: ""
                    val cookieDomain  = cookieManager.getCookie(domain)    ?: ""
                    val allCookies = listOf(cookiePlayer, cookieVideo, cookieDomain)
                        .filter { it.isNotBlank() }
                        .joinToString("; ")
                    if (allCookies.isNotBlank()) {
                        finalHeaders["Cookie"] = allCookies
                    }

                    // Pastikan Origin & Referer sesuai domain player
                    finalHeaders["Origin"]  = domain
                    finalHeaders["Referer"] = "$domain/"
                    finalHeaders["Accept"]  = "*/*"

                    // Gunakan UA dari WebView (PENTING: server cek UA)
                    if (!finalHeaders.containsKey("User-Agent") &&
                        !finalHeaders.containsKey("user-agent")) {
                        finalHeaders["User-Agent"] =
                            WebViewResolver.webViewUserAgent
                                ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name   = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                            url    = url,
                            type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer  = "$domain/"
                            this.headers  = finalHeaders
                            this.quality  = Qualities.Unknown.value
                        }
                    )
                }

            } catch (e: Exception) {
                // Silent fail — lanjut ke URL berikutnya
            }
        }

        return true
    }
}
