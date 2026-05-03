package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    
    // PENTING: Pastikan opsi "Show NSFW content" di Pengaturan CloudStream aktif!
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
        // Cukup 1x request ke halaman utama
        val document = app.get("$mainUrl/id", headers = headers).document
        
        val items = ArrayList<HomePageList>()

        // Cari semua container yang punya judul h2 (Keluaran Terbaru, dll)
        document.select("div.sm\\:container:has(h2)").forEach { section ->
            val sectionTitle = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            
            // Abaikan bagian rekomendasi (karena isinya di-load via API JavaScript Recombee)
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
            throw Error("Gagal memuat data. Coba klik 'Buka di Peramban' untuk melewati verifikasi Cloudflare.")
        }

        return newHomePageResponse(items, hasNext = false)
    }

    // ==========================================
    // 2. FITUR PENCARIAN (Search)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse>? {
        // Ubah spasi menjadi format URL yang aman (+) agar request tidak ditolak
        val formattedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/id/search/$formattedQuery"
        
        val document = app.get(searchUrl, headers = headers).document

        // Ambil elemen div.thumbnail secara langsung
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
    // 3. HALAMAN DETAIL & SARAN FILM (Load Info)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        // Ambil data super akurat dari OpenGraph Meta Tags
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        // Ambil Tags/Genre dan Aktor
        val tags = document.select("a[href*=/genres/], a[href*=/actresses/]").map { it.text().trim() }

        // --- TRIK MENDAPATKAN SARAN FILM (RECOMMENDATIONS) ---
        // Karena API bawaannya ribet, kita "pinjam" video dari fitur Pencarian!
        // Ambil nama Aktris pertama, jika tidak ada, ambil Genre pertama.
        val actressOrGenre = document.selectFirst("a[href*=/actresses/]")?.text()?.trim() 
            ?: document.selectFirst("a[href*=/genres/]")?.text()?.trim()

        var recommendations: List<SearchResponse>? = null

        if (actressOrGenre != null) {
            try {
                val formattedQuery = actressOrGenre.replace(" ", "+")
                val searchUrl = "$mainUrl/id/search/$formattedQuery"
                val recDoc = app.get(searchUrl, headers = headers).document

                recommendations = recDoc.select("div.thumbnail").mapNotNull { element ->
                    val recTitleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
                    val recTitle = recTitleElement.text().trim()
                    val recVideoUrl = recTitleElement.attr("href")

                    // Lewati jika kosong, template js, atau URL sama dengan video yang sedang ditonton
                    if (recTitle.isEmpty() || recVideoUrl.contains("javascript:") || recVideoUrl == "#" || recVideoUrl == url) {
                        return@mapNotNull null
                    }

                    val img = element.selectFirst("img")
                    val recPosterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

                    newMovieSearchResponse(recTitle, recVideoUrl, TvType.NSFW) {
                        this.posterUrl = recPosterUrl
                    }
                }
            } catch (e: Exception) {
                // Abaikan jika pencarian saran gagal agar halaman utama tetap bisa terbuka
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            // Sematkan video rekomendasi ke sini agar tombol/tab saran film muncul!
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

        // Cari script yang di-pack (biasanya diawali eval(function(p,a,c,k,e,d))
        val scriptElements = document.select("script")
        for (script in scriptElements) {
            val scriptText = script.data()
            if (scriptText.contains("eval(function(p,a,c,k,e,d)")) {
                // Bongkar enkripsi scriptnya menggunakan Extractor Api CloudStream
                val unpacked = getAndUnpack(scriptText)
                
                // Cari URL playlist.m3u8 pakai Regex
                val match = Regex("""https?://[^"']+\.m3u8[^"']*""").find(unpacked)
                if (match != null) {
                    m3u8Url = match.value
                    break
                }
            }
        }
        
        // Jika videonya tidak di-pack, kita cari langsung di seluruh HTML sebagai cadangan
        if (m3u8Url == null) {
            val html = document.html()
            val match = Regex("""https?://[^"']+\.m3u8[^"']*""").find(html)
            if (match != null) {
                m3u8Url = match.value
            }
        }

        // Kalau ketemu, kita kirim linknya ke player CloudStream
        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = data, // Sangat penting agar tidak diblokir surrit.com
                    quality = Qualities.Unknown.value, // Exoplayer akan mengurus resolusinya otomatis
                    type = ExtractorLinkType.M3U8 
                )
            )
            return true
        }

        return false
    }
}
