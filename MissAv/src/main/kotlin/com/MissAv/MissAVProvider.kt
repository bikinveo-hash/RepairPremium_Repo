package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val usesWebView = true 

    // Header penyamaran
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
        // Cukup 1 kali request ke halaman utama, CloudStream otomatis mengikuti redirect ke dmXYZ
        val document = app.get("$mainUrl/id", headers = headers).document
        
        val items = ArrayList<HomePageList>()

        // Cari semua container yang punya judul h2 (Keluaran Terbaru, dll)
        document.select("div.sm\\:container:has(h2)").forEach { section ->
            val sectionTitle = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            
            // Abaikan bagian rekomendasi (karena isinya kosong/di-load via JavaScript)
            if (sectionTitle.contains("Memuat", true) || sectionTitle.contains("Direkomendasikan", true)) {
                return@forEach
            }

            val videos = section.select("div.thumbnail").mapNotNull { element ->
                val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
                val title = titleElement.text().trim()
                val videoUrl = titleElement.attr("href")

                // Lewati template Alpine.js yang kosong
                if (title.isEmpty() || videoUrl.contains("javascript:") || videoUrl == "#") {
                    return@mapNotNull null
                }

                val img = element.selectFirst("img")
                val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

                newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                    this.posterUrl = posterUrl
                }
            }
            
            // Jika container ini punya video, tambahkan ke layar beranda
            if (videos.isNotEmpty()) {
                items.add(HomePageList(sectionTitle, videos))
            }
        }

        // Alarm jika murni diblokir Cloudflare
        if (items.isEmpty()) {
            throw Error("Gagal memuat data halaman depan. Coba buka di Peramban untuk memverifikasi Cloudflare.")
        }

        return newHomePageResponse(items, hasNext = false)
    }

    // ==========================================
    // 2. HALAMAN DETAIL (Load Info)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select("a[href*=/genres/], a[href*=/actresses/]").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
        }
    }

    // ==========================================
    // 3. PEMUTAR VIDEO (Load Links)
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
