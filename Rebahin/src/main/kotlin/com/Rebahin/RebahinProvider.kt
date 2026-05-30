package com.Rebahin

import android.util.Base64
import android.webkit.CookieManager
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

            // Normalisasi AbyssCDN
            if (targetUrl.contains("abyssplayer.com/")) {
                targetUrl = targetUrl.replace("abyssplayer.com/", "abysscdn.com/?v=")
            }

            val isExtractorLoaded = loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)

            if (!isExtractorLoaded) {
                val domain = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: targetUrl
                val fixedReferer = if (targetUrl.contains("abyss")) "https://abysscdn.com/" else "$domain/"

                var linkFound = false

                // Script Auto-Clicker untuk mencuri link dari memori player
                val safeHijackScript = """
                    (function() {
                        if (window.cs_hook_active) return;
                        window.cs_hook_active = true;
                        
                        window.fuckAdBlock = { onDetected: function(){}, onNotDetected: function(){}, setOption: function(){} };
                        window.FuckAdBlock = window.fuckAdBlock;

                        setInterval(function() {
                            try {
                                if (window.jwplayer && typeof window.jwplayer === 'function') {
                                    var pl = window.jwplayer().getPlaylist();
                                    if (pl && pl.length > 0 && pl[0].file) {
                                        var url = pl[0].file;
                                        if (url.indexOf('blob:') === -1 && !window.cs_sent) {
                                            window.cs_sent = true;
                                            window.location.href = 'https://cloudstream.video.extracted/?url=' + encodeURIComponent(url);
                                        }
                                    }
                                }
                            } catch(e){}
                            
                            try {
                                var vids = document.querySelectorAll('video');
                                for (var i = 0; i < vids.length; i++) {
                                    var vurl = vids[i].src;
                                    if (!vurl) {
                                        var srcTag = vids[i].querySelector('source');
                                        if (srcTag && srcTag.src) vurl = srcTag.src;
                                    }
                                    if (vurl && vurl.indexOf('blob:') === -1 && !window.cs_sent) {
                                        window.cs_sent = true;
                                        window.location.href = 'https://cloudstream.video.extracted/?url=' + encodeURIComponent(vurl);
                                    }
                                }
                            } catch(e){}

                            try {
                                var overlay = document.getElementById('overlay');
                                if (overlay) overlay.click();
                                
                                var btns = document.querySelectorAll('.jw-icon-display, .vjs-big-play-button, .plyr__control--overlaid');
                                for (var i=0; i<btns.length; i++) {
                                    btns[i].click();
                                }
                            } catch(e){}
                        }, 500);
                    })();
                """.trimIndent()

                try {
                    val webViewResolver = WebViewResolver(
                        interceptUrl = Regex("""(cloudstream\.video\.extracted/\?url=.*|\.(m3u8|mp4)(?:[?#]|$))"""),
                        script = safeHijackScript 
                    )
                    
                    val (request, _) = webViewResolver.resolveUsingWebView(
                        url = targetUrl,
                        referer = mainUrl
                    )
                    
                    request?.url?.toString()?.let { resolvedUrl ->
                        var finalUrl = resolvedUrl
                        
                        if (resolvedUrl.contains("cloudstream.video.extracted")) {
                            val encodedUrl = Regex("""\?url=(.*)""").find(resolvedUrl)?.groupValues?.get(1)
                            if (encodedUrl != null) {
                                finalUrl = URLDecoder.decode(encodedUrl, "UTF-8")
                            }
                        }

                        // =========================================================
                        // PERBAIKAN FATAL: AMBIL SEMUA COOKIES UNTUK EXOPLAYER
                        // =========================================================
                        val cookieManager = CookieManager.getInstance()
                        val embedCookies = cookieManager.getCookie(targetUrl) ?: ""
                        val videoCookies = cookieManager.getCookie(finalUrl) ?: ""
                        val finalCookies = listOf(embedCookies, videoCookies).filter { it.isNotBlank() }.joinToString("; ")

                        val reqHeaders = mapOf(
                            "Origin" to fixedReferer.removeSuffix("/"),
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                            "Cookie" to finalCookies, // <--- INI KUNCI UTAMANYA MENCEGAH 403!
                            "Accept" to "*/*",
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Dest" to "empty"
                        )

                        val isM3u8 = finalUrl.contains(".m3u8") || finalUrl.contains("m3u8")
                        
                        callback.invoke(
                            ExtractorLink(
                                source = this.name + " (Web)",
                                name = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                                url = finalUrl,
                                referer = fixedReferer,
                                quality = Qualities.Unknown.value,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                headers = reqHeaders
                            )
                        )
                        linkFound = true
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
                                ExtractorLink(
                                    source = this.name + " (Auto)",
                                    name = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                                    url = link,
                                    referer = fixedReferer,
                                    quality = Qualities.Unknown.value,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
                                )
                            )
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        return true
    }
}
