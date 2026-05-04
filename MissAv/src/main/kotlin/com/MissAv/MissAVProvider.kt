package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    
    // Pastikan "Show NSFW content" di Pengaturan CloudStream aktif
    override val supportedTypes = setOf(TvType.NSFW)
    override val usesWebView = true 

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "sec-ch-ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/id/release" to "Keluaran Terbaru",
        "$mainUrl/id/new" to "Baru Ditambahkan",
        "$mainUrl/id/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "$mainUrl/id/monthly-hot" to "Paling Populer Bulan Ini",
        "$mainUrl/id/siro" to "Koleksi Amatir SIRO"
    )

    private fun parseVideos(document: Element): List<SearchResponse> {
        return document.select("div.thumbnail").mapNotNull { element ->
            val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val videoUrl = titleElement.attr("href")

            if (title.isEmpty() || videoUrl.contains("javascript:") || videoUrl == "#") {
                return@mapNotNull null
            }

            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

            newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page == 1) request.data else "${request.data}?page=$page"
        try {
            val document = app.get(pageUrl, headers = headers).document
            val videos = parseVideos(document)
            return newHomePageResponse(
                list = listOf(HomePageList(request.name, videos, isHorizontalImages = true)),
                hasNext = videos.size >= 10 
             )
        } catch (e: Exception) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val formattedQuery = query.replace(" ", "+")
        val searchUrl = if (page == 1) {
            "$mainUrl/id/search/$formattedQuery"
        } else {
            "$mainUrl/id/search/$formattedQuery?page=$page"
        }
        val document = app.get(searchUrl, headers = headers).document
        val videos = parseVideos(document)
        return newSearchResponseList(list = videos, hasNext = videos.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = document.select("a[href*=/genres/], a[href*=/actresses/]").map { it.text().trim() }

        val recUrls = document.select("a[href*=/genres/], a[href*=/actresses/]")
            .mapNotNull { it.attr("href") }
            .distinct()
            .take(3) 

        val recommendations = ArrayList<SearchResponse>()
        for (recUrl in recUrls) {
            if (recommendations.size >= 16) break 
            try {
                val recDoc = app.get(recUrl, headers = headers).document
                val videos = parseVideos(recDoc).filter { it.url != url }
                recommendations.addAll(videos)
            } catch (e: Exception) {}
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations.distinctBy { it.url }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // ==========================================
        // FITUR MENGAMBIL VIDEO & SUBTITLE DARI SUKAWIBU
        // ==========================================
        try {
            val videoCodeMatch = Regex("""/([a-zA-Z0-9-]+)$""").find(data)
            
            if (videoCodeMatch != null) {
                val videoCode = videoCodeMatch.groupValues[1].uppercase() 
                println("MISSAV_SUB: Mencari SukaWibu -> $videoCode")

                val searchUrl = "https://sukawibu.com/?s=$videoCode"
                val searchDoc = app.get(searchUrl, headers = headers).document
                val postUrl = searchDoc.selectFirst("article.video-preview-item a")?.attr("href")
                
                if (postUrl != null) {
                    val postDoc = app.get(postUrl, headers = headers).document
                    var iframeUrl = postDoc.selectFirst("iframe#videoPlayer")?.attr("src")
                    
                    if (iframeUrl != null) {
                        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                        
                        // Menembus Iframe Lapis 1
                        val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to postUrl)).text
                        val nestedIframeMatch = Regex("""<iframe[^>]+src=["']((?:https?:)?//[^"']+/e/[^"']+)["']""").find(iframeHtml)
                        
                        var finalUrl = iframeUrl
                        var finalHtml = iframeHtml
                        
                        // Kalau ada Iframe Lapis 2, kita masuk ke dalamnya
                        if (nestedIframeMatch != null) {
                            finalUrl = nestedIframeMatch.groupValues[1]
                            if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
                            finalHtml = app.get(finalUrl, headers = mapOf("Referer" to iframeUrl)).text
                        }

                        // KITA BONGKAR SEMUA SCRIPT YANG DI-PACK
                        var unpackedHtml = finalHtml
                        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,.*?\)\)""")
                        packedRegex.findAll(finalHtml).forEach { match ->
                            try {
                                val unpacked = getAndUnpack(match.value)
                                unpackedHtml += "\n$unpacked"
                            } catch (e: Exception) {}
                        }

                        // Setelah dibongkar, VTT dan M3U8 pasti kelihatan telanjang
                        val m3u8Match = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpackedHtml)
                        val vttMatch = Regex("""(?:file|src)\s*:\s*["'](https?://[^"']+\.vtt[^"']*)["']""").find(unpackedHtml)

                        if (m3u8Match != null) {
                            val m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
                            println("MISSAV_SUB: Sukses nemu M3U8 -> $m3u8Url")
                            
                            callback.invoke(
                                newExtractorLink(
                                    source = "SukaWibu (Sub Indo)",
                                    name = "SukaWibu (Sub Indo)",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = finalUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            
                            if (vttMatch != null) {
                                val vttUrl = vttMatch.groupValues[1].replace("\\/", "/")
                                println("MISSAV_SUB: Sukses nemu VTT -> $vttUrl")
                                
                                subtitleCallback.invoke(
                                    SubtitleFile(
                                        lang = "Indonesia",
                                        url = vttUrl
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("MISSAV_SUB: Error -> ${e.message}")
        }
        // ==========================================

        // KODE ASLI UNTUK SERVER MISSAV
        val document = app.get(data, headers = headers).document
        var m3u8Url: String? = null

        val scriptElements = document.select("script")
        for (script in scriptElements) {
            val scriptText = script.data()
            if (scriptText.contains("eval(function(p,a,c,k,e,d)")) {
                val unpacked = getAndUnpack(scriptText)
                val match = Regex("""https?://[^"']+\.m3u8[^"']*""").find(unpacked)
                if (match != null) {
                    m3u8Url = match.value
                    break
                }
            }
        }
        
        if (m3u8Url == null) {
            val html = document.html()
            val match = Regex("""https?://[^"']+\.m3u8[^"']*""").find(html)
            if (match != null) {
                m3u8Url = match.value
            }
        }

        if (m3u8Url != null) {
            callback.invoke(
                newExtractorLink(
                    source = "MissAv Asli",
                    name = "MissAv Asli",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }
}
