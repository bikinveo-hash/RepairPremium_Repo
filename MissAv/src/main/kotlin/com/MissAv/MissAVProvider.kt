package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    
    // Gunakan TvType.Movie untuk memastikan tidak di-hide oleh filter aplikasi
    override val supportedTypes = setOf(TvType.Movie)
    
    override val usesWebView = true 

    // ==========================================
    // 1. HALAMAN DEPAN (Home Page) - REVISI
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        // Kita bypass beranda utama dan langsung ambil dari halaman kategori!
        val urls = listOf(
            Pair("Keluaran Terbaru", "$mainUrl/id/release"),
            Pair("Baru Ditambahkan", "$mainUrl/id/new"),
            Pair("Tanpa Sensor", "$mainUrl/id/uncensored-leak")
        )

        for ((name, url) in urls) {
            try {
                val document = app.get(url).document
                
                val videos = document.select("div.thumbnail").mapNotNull { element ->
                    val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
                    
                    val title = titleElement.text().trim()
                    val videoUrl = titleElement.attr("href")
                    
                    // Lewati template kosong bawaan Alpine.js
                    if (title.isEmpty() || videoUrl.contains("javascript:") || videoUrl == "#") {
                        return@mapNotNull null
                    }
                    
                    val img = element.selectFirst("img")
                    val posterUrl = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src")

                    newMovieSearchResponse(title, videoUrl, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                }
                
                // Jika berhasil mendapat video, masukkan ke dalam baris kategori
                if (videos.isNotEmpty()) {
                    items.add(HomePageList(name, videos))
                }
            } catch (e: Exception) {
                // Abaikan error pada satu kategori agar kategori lain tetap bisa dimuat
            }
        }

        // Alarm jika semua kategori gagal dimuat
        if (items.isEmpty()) throw Error("Gagal memuat data. Periksa koneksi atau verifikasi Cloudflare.")

        return HomePageResponse(items)
    }

    // ==========================================
    // 2. HALAMAN DETAIL (Load Info)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = document.select("a[href*=/genres/], a[href*=/actresses/]").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val document = app.get(data).document
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
