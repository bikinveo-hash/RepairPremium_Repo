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
        // FITUR MENGAMBIL VIDEO & SUBTITLE LANGSUNG DARI API
        // ==========================================
        try {
            val videoCodeMatch = Regex("""/([a-zA-Z0-9-]+)$""").find(data)
            
            if (videoCodeMatch != null) {
                val videoCode = videoCodeMatch.groupValues[1].uppercase() 
                println("MISSAV_SUB: Sedang mencari -> $videoCode di SukaWibu")

                val searchUrl = "https://sukawibu.com/?s=$videoCode"
                val searchDoc = app.get(searchUrl, headers = headers).document
                val postUrl = searchDoc.selectFirst("article.video-preview-item a")?.attr("href")
                
                if (postUrl != null) {
                    val postDoc = app.get(postUrl, headers = headers).document
                    var iframeUrl = postDoc.selectFirst("iframe#videoPlayer")?.attr("src")
                    
                    if (iframeUrl != null) {
                        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                        
                        // Menembus Iframe Lapis 1 (hgcloud)
                        val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to postUrl)).text
                        val nestedIframeMatch = Regex("""<iframe[^>]+src=["']((?:https?:)?//[^"']+/e/[^"']+)["']""").find(iframeHtml)
                        
                        var targetExtractorUrl = iframeUrl
                        if (nestedIframeMatch != null) {
                            targetExtractorUrl = nestedIframeMatch.groupValues[1]
                            if (targetExtractorUrl.startsWith("//")) targetExtractorUrl = "https:$targetExtractorUrl"
                        }

                        println("MISSAV_SUB: Final Iframe URL -> $targetExtractorUrl")
                        
                        // MENGAKALI API VIBUXER / MASUKESTIN
                        // Kita ekstrak ID Videonya (Contoh: iqus31epfbjj)
                        val fileCodeMatch = Regex("""/e/([a-zA-Z0-9]+)""").find(targetExtractorUrl)
                        if (fileCodeMatch != null) {
                            val fileCode = fileCodeMatch.groupValues[1]
                            val host = targetExtractorUrl.substringBefore("/e/") // Contoh: https://vibuxer.com
                            
                            // Kita pancing API mereka untuk memberikan JSON berisi link m3u8 dan VTT
                            // Menggunakan endpoint /api/source/
                            val apiUrl = "$host/api/source/$fileCode"
                            val apiHeaders = mapOf(
                                "Referer" to targetExtractorUrl,
                                "User-Agent" to "Mozilla/5.0",
                                "Accept" to "application/json"
                            )
                            
                            // Nembak API! Minta data mentahnya (Post Request kosong biasanya berhasil untuk JWPlayer)
                            val apiResponse = app.post(apiUrl, headers = apiHeaders, data = mapOf("r" to "", "d" to host.replace("https://", ""))).text
                            
                            println("MISSAV_SUB: Respon API -> $apiResponse")
                            
                            // 1. Tangkap link M3U8
                            val m3u8Match = Regex("""\"file\"\s*:\s*\"(https?://[^\"]+\.m3u8[^\"]*)\"""").find(apiResponse)
                            // 2. Tangkap link VTT
                            val vttMatch = Regex("""\"file\"\s*:\s*\"(https?://[^\"]+\.vtt[^\"]*)\"""").find(apiResponse)
                            
                            if (m3u8Match != null) {
                                println("MISSAV_SUB: SUKSES EKSTRAK M3U8 -> ${m3u8Match.groupValues[1]}")
                                callback.invoke(
                                    newExtractorLink(
                                        source = "SukaWibu (Sub Indo)",
                                        name = "SukaWibu (Sub Indo)",
                                        url = m3u8Match.groupValues[1].replace("\\/", "/"),
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = targetExtractorUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                
                                if (vttMatch != null) {
                                    println("MISSAV_SUB: SUKSES EKSTRAK VTT -> ${vttMatch.groupValues[1]}")
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            lang = "Indonesia",
                                            url = vttMatch.groupValues[1].replace("\\/", "/")
                                        )
                                    )
                                } else {
                                    // Backup plan jika VTT tidak ada di JSON, cari langsung di HTML iframe-nya
                                    val iframeFinalHtml = app.get(targetExtractorUrl, headers = mapOf("Referer" to iframeUrl)).text
                                    val fallbackVttMatch = Regex("""https?://[^\s\"']+\.vtt""").find(iframeFinalHtml)
                                    if (fallbackVttMatch != null) {
                                         println("MISSAV_SUB: SUKSES EKSTRAK VTT (Fallback) -> ${fallbackVttMatch.value}")
                                         subtitleCallback.invoke(
                                            SubtitleFile(
                                                lang = "Indonesia",
                                                url = fallbackVttMatch.value
                                            )
                                        )
                                    }
                                }
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
