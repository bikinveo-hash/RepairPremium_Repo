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

    // ==========================================
    // DAFTAR KATEGORI (Otomatis dibuatkan Tab/Baris oleh CloudStream)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/id/release" to "Keluaran Terbaru",
        "$mainUrl/id/new" to "Baru Ditambahkan",
        "$mainUrl/id/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "$mainUrl/id/monthly-hot" to "Paling Populer Bulan Ini",
        "$mainUrl/id/siro" to "Koleksi Amatir SIRO"
    )

    // ==========================================
    // FUNGSI BANTUAN UNTUK EKSTRAK VIDEO
    // ==========================================
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

    // ==========================================
    // 1. HALAMAN DEPAN & INFINITE SCROLL
    // ==========================================
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

    // ==========================================
    // 2. FITUR PENCARIAN (Search)
    // ==========================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val formattedQuery = query.replace(" ", "+")
        
        // Cek halaman keberapa yang sedang dimuat, tambahkan ?page= jika lebih dari 1
        val searchUrl = if (page == 1) {
            "$mainUrl/id/search/$formattedQuery"
        } else {
            "$mainUrl/id/search/$formattedQuery?page=$page"
        }
        
        val document = app.get(searchUrl, headers = headers).document
        val videos = parseVideos(document)
        
        // Mengembalikan list dengan parameter hasNext supaya CloudStream tau kapan harus berhenti scroll
        return newSearchResponseList(
            list = videos,
            hasNext = videos.isNotEmpty()
         )
    }

    // ==========================================
    // 3. HALAMAN DETAIL & SARAN FILM (Load Info)
    // ==========================================
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
            } catch (e: Exception) {
                // Abaikan jika salah satu link gagal
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations.distinctBy { it.url }
        }
    }

    // ==========================================
    // 4. PEMUTAR VIDEO (Load Links)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // ==========================================
        // FITUR PENCURI SUBTITLE DARI SUKAWIBU
        // ==========================================
        try {
            val videoCodeMatch = Regex("""/([a-zA-Z0-9-]+)$""").find(data)
            
            if (videoCodeMatch != null) {
                val videoCode = videoCodeMatch.groupValues[1].uppercase() 
                println("MISSAV_SUB: Kode video -> $videoCode")

                val searchUrl = "https://sukawibu.com/?s=$videoCode"
                val searchDoc = app.get(searchUrl, headers = headers).document
                val postUrl = searchDoc.selectFirst("article.video-preview-item a")?.attr("href")
                println("MISSAV_SUB: URL Post SukaWibu -> $postUrl")
                
                if (postUrl != null) {
                    val postDoc = app.get(postUrl, headers = headers).document
                    var iframeUrl = postDoc.selectFirst("iframe#videoPlayer")?.attr("src")
                    println("MISSAV_SUB: URL Iframe Lapis 1 -> $iframeUrl")
                    
                    if (iframeUrl != null) {
                        val iframeHeaders = mapOf(
                            "Referer" to postUrl,
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                        )
                        var iframeHtml = app.get(iframeUrl, headers = iframeHeaders).text
                        
                        // 1. TEMBUS PERTAHANAN IFRAME BERLAPIS
                        val nestedIframeMatch = Regex("""<iframe[^>]+src=["'](https?://[^"']+/e/[^"']+)["']""").find(iframeHtml)
                        if (nestedIframeMatch != null) {
                            iframeUrl = nestedIframeMatch.groupValues[1]
                            println("MISSAV_SUB: URL Iframe Lapis 2 -> $iframeUrl")
                            iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to "https://hgcloud.to/")).text
                        }

                        // 2. TEMBUS PERTAHANAN KARAKTER DISAMARKAN (\/)
                        val cleanHtml = iframeHtml.replace("\\/", "/")
                        var vttUrl = Regex("""https?://[^"']+\.vtt""").find(cleanHtml)?.value
                        
                        // 3. TEMBUS PERTAHANAN JAVASCRIPT PACKED
                        if (vttUrl == null) {
                            val evalMatches = Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""").findAll(iframeHtml)
                            for (match in evalMatches) {
                                val unpacked = getAndUnpack(match.value).replace("\\/", "/")
                                val unpackedMatch = Regex("""https?://[^"']+\.vtt""").find(unpacked)
                                if (unpackedMatch != null) {
                                    vttUrl = unpackedMatch.value
                                    println("MISSAV_SUB: VTT ketemu dari dalam Script Unpacked!")
                                    break
                                }
                            }
                        }

                        println("MISSAV_SUB: FINAL VTT URL -> $vttUrl")
                        
                        // KIRIM KE CLOUDSTREAM
                        if (vttUrl != null) {
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    lang = "Indonesia",
                                    url = vttUrl
                                )
                            )
                            println("MISSAV_SUB: SUKSES MENGIRIM SUBTITLE!")
                        } else {
                            println("MISSAV_SUB: GAGAL MENEMUKAN LINK VTT DARI HTML")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("MISSAV_SUB: ERROR -> ${e.message}")
        }
        // ==========================================
        // AKHIR FITUR SUBTITLE
        // ==========================================

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
            // FIX: Menggunakan pola Lambda Builder dari API CloudStream terbaru
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
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
