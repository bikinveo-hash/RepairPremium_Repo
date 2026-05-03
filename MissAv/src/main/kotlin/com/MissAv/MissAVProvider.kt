package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    
    // PENTING: Jika di CloudStream kamu belum mengaktifkan "Show NSFW content" di Pengaturan,
    // halaman akan tetap kosong. Jika mau test tanpa ubah pengaturan, ganti TvType.NSFW jadi TvType.Movie
    override val supportedTypes = setOf(TvType.NSFW)
    
    // Senjata rahasia untuk menyuruh CloudStream memanggil WebView jika terkena Cloudflare
    override val usesWebView = true 

    // ==========================================
    // 1. HALAMAN DEPAN (Home Page)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Tembak URL dengan /id agar judul otomatis bahasa Indonesia
        val document = app.get("$mainUrl/id").document

        val videos = document.select("div.thumbnail").mapNotNull { element ->
            val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
            
            val title = titleElement.text()
            val url = titleElement.attr("href")
            
            val posterUrl = element.selectFirst("img")?.let { img ->
                val dataSrc = img.attr("data-src")
                if (dataSrc.isNullOrEmpty()) img.attr("src") else dataSrc
            }

            newMovieSearchResponse(title, url, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }

        if (videos.isEmpty()) return null

        return newHomePageResponse(
            list = HomePageList("Update Terbaru", videos),
            hasNext = false
        )
    }

    // ==========================================
    // 2. HALAMAN DETAIL (Load Info)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Mengambil data menggunakan OpenGraph meta tags agar sangat akurat
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        // Cari Tag/Genre dan Pemeran
        val tags = document.select("a[href*=/genres/], a[href*=/actresses/]").map { it.text() }

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
        val document = app.get(data).document
        var m3u8Url: String? = null

        // 1. Cari script yang disembunyikan (Packed JS)
        val scriptElements = document.select("script")
        for (script in scriptElements) {
            val scriptText = script.data()
            if (scriptText.contains("eval(function(p,a,c,k,e,d)")) {
                // Bongkar enkripsi scriptnya menggunakan ExtractorApi bawaan CloudStream
                val unpacked = getAndUnpack(scriptText)
                
                // Cari URL playlist.m3u8 pakai Regex
                val match = Regex("""https?://[^"']+\.m3u8[^"']*""").find(unpacked)
                if (match != null) {
                    m3u8Url = match.value
                    break
                }
            }
        }
        
        // 2. Jika tidak di-pack, kita cari langsung di seluruh body HTML
        if (m3u8Url == null) {
            val html = document.html()
            val match = Regex("""https?://[^"']+\.m3u8[^"']*""").find(html)
            if (match != null) {
                m3u8Url = match.value
            }
        }

        // 3. Kirim ke video player CloudStream jika link m3u8 ditemukan
        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = data, // Wajib ada agar tidak Access Denied oleh server video
                    quality = Qualities.Unknown.value, // Resolusi 360p/480p/720p akan diatur otomatis oleh ExoPlayer
                    type = ExtractorLinkType.M3U8 
                )
            )
            return true
        }

        return false
    }
}
