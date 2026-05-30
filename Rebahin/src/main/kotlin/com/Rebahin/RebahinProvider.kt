package com.Rebahin

import android.util.Base64
import com.lagradost.cloudstream3.* // getQualityFromString sudah termasuk di sini
import com.lagradost.cloudstream3.utils.* // ExtractorLink, Qualities, dll
import com.lagradost.cloudstream3.network.WebViewResolver
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.ml-mask") ?: return null
        val title = a.attr("title")
        val href = fixUrlNull(a.attr("href")) ?: return null
        
        // Memprioritaskan data-original untuk mengatasi Lazy Loading gambar
        val img = this.selectFirst("img.mli-thumb")
        val posterUrl = fixUrlNull(img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
        
        val qualityStr = this.selectFirst("span.mli-quality")?.text()
        val qualityResult = getQualityFromString(qualityStr)
        
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

        // Prioritaskan mengambil judul dari tag meta agar tidak error jika susunan h3 berubah
        val title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h3[itemprop=name]")?.text()
            ?: return null
        
        // Menarik URL pemutar video sekaligus Latar Belakang (Banner)
        val mviCover = document.selectFirst("a.mvi-cover")
        val background = fixUrlNull(mviCover?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\""))
        val playUrl = fixUrlNull(mviCover?.attr("href")) ?: if (url.contains("/series/")) "$url/watch" else "$url/play"

        // Menarik Poster Detail
        val poster = fixUrlNull(document.selectFirst("meta[itemprop=image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.mvic-thumb")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\""))

        // Membersihkan sampah deskripsi Iklan SEO
        val plot = document.selectFirst("div.sinopsis-indo, div.desc, div.rt-Text")?.text()?.replace("Nonton Film.*Sub Indo \\| REBAHIN".toRegex(RegexOption.IGNORE_CASE), "")?.trim()
        
        // Memakai Score system CloudStream 3 Terbaru
        val ratingText = document.selectFirst("span.irank-voters, span.rating, div.btn-danger.averagerate")?.text()?.replace(",", ".")?.trim()
        val parsedScore = ratingText?.toFloatOrNull()?.let { Score.from10(it) }

        var year: Int? = null
        var duration: Int? = null

        document.select("div.mvic-info p").forEach { p ->
            val text = p.text()
            when {
                text.contains("Release Date:") -> {
                    year = p.selectFirst("meta[itemprop=datePublished]")?.attr("content")?.substringBefore("-")?.toIntOrNull()
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
            val episodeButtons = watchDocument.select("div#list-eps a.btn-eps")
            
            episodeButtons.forEach { epElem ->
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
                this.score = parsedScore // <--- PERBAIKAN: Menggunakan score, bukan rating
                this.year = year
                this.duration = duration
            }
        } else {
            // URL pemutar film layar lebar langsung diteruskan
            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.score = parsedScore // <--- PERBAIKAN: Menggunakan score, bukan rating
                this.year = year
                this.duration = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val urlToExtract = mutableListOf<String>()

        // Pisahkan penanganan data Base64 (TV Series) dan URL (Movie)
        if (!data.startsWith("http")) {
            try {
                val decodedUrl = String(Base64.decode(data, Base64.DEFAULT))
                if (decodedUrl.startsWith("http")) {
                     urlToExtract.add(decodedUrl)
                }
            } catch (e: Exception) {}
        } else {
            val document = app.get(data).document
            document.select("div.server").forEach { server ->
                val encodedUrl = server.attr("data-iframe")
                if (encodedUrl.isNotBlank()) {
                    val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                    if (decodedUrl.startsWith("http")) {
                        urlToExtract.add(decodedUrl)
                    }
                }
            }
            document.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
                if (!src.contains("googleusercontent.com") && src.isNotBlank()) {
                    urlToExtract.add(src)
                }
            }
        }

        // Jalankan semua URL yang berhasil diekstrak
        urlToExtract.forEach { rawUrl ->
            var targetUrl = rawUrl
            // Tangani variasi URL menggunakan iembed/?source=
            if (rawUrl.contains("/iembed/?source=")) {
                val base64 = rawUrl.substringAfter("source=")
                try {
                    targetUrl = String(Base64.decode(base64, Base64.DEFAULT))
                } catch(e: Exception) {}
            }

            // PERBAIKAN HEADER ANTI-403:
            val domain = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: targetUrl
            val fixedReferer = if (targetUrl.contains("abyssplayer")) "https://abysscdn.com/" else "$domain/"
            
            val reqHeaders = mapOf(
                "Origin" to fixedReferer.removeSuffix("/"),
                "Referer" to fixedReferer,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            )

            // 1. Ekstraksi Standar CloudStream
            val isExtractorLoaded = loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)

            // 2. Senjata Pamungkas: WebViewResolver untuk melewati Cloudflare dan JS
            if (!isExtractorLoaded) {
                var linkFound = false
                try {
                    val webViewResolver = WebViewResolver(
                        interceptUrl = Regex("""\.(m3u8|mp4)""")
                    )
                    
                    val (request, _) = webViewResolver.resolveUsingWebView(
                        url = targetUrl,
                        referer = mainUrl
                    )
                    
                    request?.url?.toString()?.let { resolvedUrl ->
                        val isM3u8 = resolvedUrl.contains(".m3u8")
                        
                        // Gabungkan header sadapan Webview dengan Header referer
                        val finalHeaders = request.headers.toMap().toMutableMap()
                        finalHeaders.putAll(reqHeaders)

                        callback.invoke(
                            newExtractorLink(
                                source = this.name + " (Web)",
                                name = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                                url = resolvedUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = fixedReferer
                                this.headers = finalHeaders 
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        linkFound = true
                    }
                } catch (e: Exception) {}

                // 3. Fallback: JS Unpacker Manual 
                if (!linkFound) {
                    try {
                        val playerHtml = app.get(targetUrl, referer = mainUrl).text
                        val unpackedHtml = getAndUnpack(playerHtml)
                        val videoLinks = Regex("""(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']""").findAll(unpackedHtml)
                        
                        videoLinks.forEach { match ->
                            val link = match.groupValues[1]
                            val isM3u8 = link.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name + " (Auto)",
                                    name = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                                    url = link,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = fixedReferer
                                    this.headers = reqHeaders 
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        return true
    }
}
