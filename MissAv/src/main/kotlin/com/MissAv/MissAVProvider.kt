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
            // KITA TETAP PAKAI cover-t.jpg KARENA INI GAMBAR LANDSCAPE (HORIZONTAL) ASLI YANG TAJAM
            val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

            newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ==========================================
    // 1. HALAMAN DEPAN (Home Page)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()

        // 1. Ambil kategori bawaan beranda
        try {
            val document = app.get("$mainUrl/id", headers = headers).document
            document.select("div.sm\\:container:has(h2)").forEach { section ->
                val sectionTitle = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
                
                if (sectionTitle.contains("Memuat", true) || sectionTitle.contains("Direkomendasikan", true)) {
                    return@forEach
                }

                val videos = parseVideos(section)
                if (videos.isNotEmpty()) {
                    // MEMAKSA TAMPILAN HORIZONTAL DI CLOUDSTREAM
                    items.add(HomePageList(sectionTitle, videos, isHorizontalImages = true))
                }
            }
        } catch (e: Exception) {
            // Abaikan jika error
        }

        // 2. TAMBAHAN KATEGORI KHUSUS: Kebocoran Tanpa Sensor
        try {
            val uncensoredDoc = app.get("$mainUrl/id/uncensored-leak", headers = headers).document
            val uncensoredVideos = parseVideos(uncensoredDoc)
            if (uncensoredVideos.isNotEmpty()) {
                // Tambahkan sebagai baris baru di halaman utama
                items.add(HomePageList("Kebocoran Tanpa Sensor", uncensoredVideos, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            // Abaikan jika error
        }

        if (items.isEmpty()) {
            throw Error("Gagal memuat data. Coba klik 'Buka di Peramban' untuk verifikasi Cloudflare.")
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
        
        return parseVideos(document)
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
        // Kita borong maksimal 3 link Aktris/Genre sekaligus agar sarannya banyak!
        val recUrls = document.select("a[href*=/genres/], a[href*=/actresses/]")
            .mapNotNull { it.attr("href") }
            .distinct()
            .take(3) 

        val recommendations = ArrayList<SearchResponse>()
        
        for (recUrl in recUrls) {
            if (recommendations.size >= 16) break // Batasi maksimal 16 saran film
            try {
                val recDoc = app.get(recUrl, headers = headers).document
                // Filter agar video yang sedang ditonton tidak muncul di saran
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
            // Hapus duplikat dan masukkan ke daftar saran
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
