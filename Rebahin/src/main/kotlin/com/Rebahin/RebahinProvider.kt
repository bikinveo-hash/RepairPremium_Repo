package com.Rebahin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLDecoder

class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.autos"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay: Long = 800L
    override var sequentialMainPageScrollDelay: Long = 200L

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/country/indonesia/page/" to "Indonesia",
        "$mainUrl/genre/series-indonesia/page/" to "Series Indonesia",
        "$mainUrl/country/south-korea/page/" to "South Korea",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/adventure/page/" to "Adventure",
        "$mainUrl/genre/war/page/" to "War",
        "$mainUrl/genre/adult/page/" to "Adult",
        "$mainUrl/country/philippines/page/" to "Philippines"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data.removeSuffix("page/")
        } else {
            request.data + page
        }

        val document = app.get(url, referer = mainUrl).document
        val home = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }

        if (home.isEmpty() && page == 1) {
            throw ErrorLoadingException("Server membatasi request, silakan refresh kategori ini.")
        }

        return newHomePageResponse(request, home)
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
        
        val img = this.selectFirst("img.mli-thumb")
        val posterUrl = fixUrlNull(img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
        
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
        val background = fixUrlNull(mviCover?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\""))
        val playUrl = fixUrlNull(mviCover?.attr("href")) ?: if (url.contains("/series/")) "$url/watch" else "$url/play"

        val poster = fixUrlNull(document.selectFirst("meta[itemprop=image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.mvic-thumb")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\""))

        val plot = document.selectFirst("div.sinopsis-indo, div.desc, div.rt-Text")?.text()?.replace("Nonton Film.*Sub Indo \\| REBAHIN".toRegex(RegexOption.IGNORE_CASE), "")?.trim()
        
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
                this.score = parsedScore 
                this.year = year
                this.duration = duration
            }
        } else {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val urlToExtract = mutableListOf<String>()

        if (!data.startsWith("http")) {
            try {
                val decodedUrl = String(Base64.decode(data, Base64.DEFAULT))
                if (decodedUrl.startsWith("http")) {
                     urlToExtract.add(decodedUrl)
                }
            } catch (e: Exception) {}
        } else {
            val document = app.get(data, referer = mainUrl).document
            
            document.select("[data-iframe]").forEach { element ->
                val encodedUrl = element.attr("data-iframe")
                if (encodedUrl.isNotBlank()) {
                    try {
                        val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                        if (decodedUrl.startsWith("http")) {
                            urlToExtract.add(decodedUrl)
                        }
                    } catch (e: Exception) {}
                }
            }
            
            document.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src")) ?: fixUrlNull(iframe.attr("data-src"))
                if (src != null && !src.contains("googleusercontent.com") && src.isNotBlank()) {
                    urlToExtract.add(src)
                }
            }
        }

        urlToExtract.distinct().forEach { rawUrl ->
            var targetUrl = rawUrl
            if (rawUrl.contains("/iembed/?source=")) {
                val base64 = rawUrl.substringAfter("source=")
                try {
                    targetUrl = String(Base64.decode(base64, Base64.DEFAULT))
                } catch(e: Exception) {}
            }

            val isExtractorLoaded = loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)

            if (!isExtractorLoaded) {
                val domain = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: targetUrl
                val fixedReferer = if (targetUrl.contains("abyssplayer")) "https://abysscdn.com/" else "$domain/"
                val reqHeaders = mapOf(
                    "Origin" to fixedReferer.removeSuffix("/"),
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )

                var linkFound = false

                // =========================================================
                // API HIJACKING (MEMBAJAK JWPLAYER DI LATAR BELAKANG)
                // =========================================================
                val jwPlayerHijackScript = """
                    var clickAttempted = false;
                    var cs_check = setInterval(function() {
                        // Paksa sentuh layar agar server mau mulai dekripsi
                        if (!clickAttempted) {
                            try { document.querySelector('.jw-icon-display').click(); } catch(e) {}
                            try { document.querySelector('.vjs-big-play-button').click(); } catch(e) {}
                            clickAttempted = true;
                        }
                        
                        // Sadap playlist m3u8 dari JWPlayer API
                        try {
                            if (window.jwplayer) {
                                var pl = window.jwplayer().getPlaylist();
                                if (pl && pl.length > 0 && pl[0].file) {
                                    var url = pl[0].file;
                                    if (url.indexOf('blob:') === -1) {
                                        clearInterval(cs_check);
                                        // Kirim link balik ke Cloudstream melalui Dummy URL
                                        window.location.href = "https://cloudstream.video.extracted/?url=" + encodeURIComponent(url);
                                    }
                                }
                            }
                        } catch(e) {}
                        
                        // Fallback: Sadap tag video mentah jika server memakai player HTML5 biasa
                        try {
                            var vids = document.querySelectorAll('video');
                            for (var i = 0; i < vids.length; i++) {
                                var vsrc = vids[i].src;
                                if (!vsrc) {
                                    var srcTag = vids[i].querySelector('source');
                                    if (srcTag) vsrc = srcTag.src;
                                }
                                if (vsrc && vsrc.indexOf('blob:') === -1) {
                                    clearInterval(cs_check);
                                    window.location.href = "https://cloudstream.video.extracted/?url=" + encodeURIComponent(vsrc);
                                }
                            }
                        } catch(e) {}
                    }, 500);
                """.trimIndent()

                try {
                    val webViewResolver = WebViewResolver(
                        // Cloudstream akan menangkap URL pancingan kita ini
                        interceptUrl = Regex("""cloudstream\.video\.extracted/\?url=(.*)"""), 
                        script = jwPlayerHijackScript 
                    )
                    
                    val (request, _) = webViewResolver.resolveUsingWebView(
                        url = targetUrl,
                        referer = mainUrl
                    )
                    
                    request?.url?.toString()?.let { dummyUrl ->
                        // Ekstrak URL asli yang berhasil disadap
                        val actualUrlMatch = Regex("""\?url=(.*)""").find(dummyUrl)
                        val encodedUrl = actualUrlMatch?.groupValues?.get(1)
                        if (encodedUrl != null) {
                            val actualUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                            val isM3u8 = actualUrl.contains(".m3u8") || actualUrl.contains("m3u8")
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name + " (Web)",
                                    name = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                                    url = actualUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = fixedReferer
                                    this.headers = reqHeaders 
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            linkFound = true
                        }
                    }
                } catch (e: Exception) {}

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
