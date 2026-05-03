package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    
    // Pastikan "Show NSFW content" di Pengaturan CloudStream kamu selalu aktif
    override val supportedTypes = setOf(TvType.NSFW)
    
    // Memicu WebView ketika terkena validasi captcha Cloudflare
    override val usesWebView = true 

    // Header penyamaran agar dikira browser HP Android sungguhan
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
    // 1. HALAMAN DEPAN (Home Page)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/id", headers = headers).document
        val items = ArrayList<HomePageList>()

        document.select("div.sm\\:container:has(h2)").forEach { section ->
            val sectionTitle = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            
            // Abaikan bagian rekomendasi bawaan yg dikunci API
            if (sectionTitle.contains("Memuat", true) || sectionTitle.contains("Direkomendasikan", true)) {
                return@forEach
            }

            val videos = section.select("div.thumbnail").mapNotNull { element ->
                val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
                val title = titleElement.text().trim()
                val videoUrl = titleElement.attr("href")

                if (title.isEmpty() || videoUrl.contains("javascript:") || videoUrl == "#") {
                    return@mapNotNull null
                }

                val img = element.selectFirst("img")
                var posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
                
                // TRIK GAMBAR TAJAM: Ubah cover-t (Thumbnail/Blur) menjadi cover-n (Normal/Tajam)
                posterUrl = posterUrl?.replace("cover-t.jpg", "cover-n.jpg")

                newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                    this.posterUrl = posterUrl
                }
            }
            
            if (videos.isNotEmpty()) {
                // TRIK POSTER HORIZONTAL: Tambahkan parameter isHorizontalImages = true
                items.add(HomePageList(sectionTitle, videos, isHorizontalImages = true))
            }
        }

        if (items.isEmpty()) {
            throw Error("Gagal memuat data. Coba klik 'Buka di Peramban' untuk melewati verifikasi Cloudflare.")
        }

        return newHomePageResponse(items, hasNext = false)
    }

    // ==========================================
    // 2. FITUR PENCARIAN (Search)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse>? {
        val formattedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/id/search/$formattedQuery"
        
        val document = app.get(searchUrl, headers = headers).document

        return document.select("div.thumbnail").mapNotNull { element ->
            val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val videoUrl = titleElement.attr("href")
            
            if (title.isEmpty() || videoUrl.contains("javascript:") || videoUrl == "#") {
                return@mapNotNull null
            }
            
            val img = element.selectFirst("img")
            var posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            posterUrl = posterUrl?.replace("cover-t.jpg", "cover-n.jpg") // Gambar tajam

            newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
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

        // --- TRIK MENDAPATKAN BANYAK SARAN FILM ---
        // Kita kunjungi link profil aktris (atau genre) secara langsung!
        val targetRecUrl = document.selectFirst("a[href*=/actresses/]")?.attr("href") 
            ?: document.selectFirst("a[href*=/genres/]")?.attr("href")

        var recommendations: List<SearchResponse>? = null

        if (targetRecUrl != null) {
            try {
                val recDoc = app.get(targetRecUrl, headers = headers).document
                recommendations = recDoc.select("div.thumbnail").mapNotNull { element ->
                    val recTitleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
                    val recTitle = recTitleElement.text().trim()
                    val recVideoUrl = recTitleElement.attr("href")

                    if (recTitle.isEmpty() || recVideoUrl.contains("javascript:") || recVideoUrl == "#" || recVideoUrl == url) {
                        return@mapNotNull null
                    }

                    val img = element.selectFirst("img")
                    var recPosterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
                    recPosterUrl = recPosterUrl?.replace("cover-t.jpg", "cover-n.jpg") // Gambar tajam

                    newMovieSearchResponse(recTitle, recVideoUrl, TvType.NSFW) {
                        this.posterUrl = recPosterUrl
                    }
                }
            } catch (e: Exception) {
                // Abaikan jika pencarian saran gagal
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
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
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = data, 
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8 
                )
            )
            return true
        }

        return false
    }
}
