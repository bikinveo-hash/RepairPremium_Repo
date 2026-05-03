package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    
    // Senjata rahasia untuk menembus proteksi Cloudflare "Just a moment..."
    override val usesWebView = true 

    // ==========================================
    // 1. HALAMAN DEPAN (Home Page)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Kita akses langsung rute bahasa Indonesia (/id)
        val document = app.get("$mainUrl/id").document

        val videos = document.select("div.thumbnail").mapNotNull { element ->
            val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
            
            val title = titleElement.text()
            val url = titleElement.attr("href")
            
            val posterUrl = element.selectFirst("img")?.let { img ->
                val dataSrc = img.attr("data-src")
                dataSrc.ifEmpty { img.attr("src") }
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

        // Mengambil data super akurat dari OpenGraph Meta Tags
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        // Mengambil Tag, Genre, dan Pemeran
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
        
        // Cari script yang di-pack (biasanya diawali eval(function(p,a,c,k,e,d))
        val scriptElements = document.select("script")
        var m3u8Url: String? = null

        for (script in scriptElements) {
            val scriptText = script.data()
            if (scriptText.contains("eval(function(p,a,c,k,e,d)")) {
                // Bongkar enkripsi scriptnya menggunakan fungsi bawaan CloudStream
                val unpacked = getAndUnpack(scriptText)
                
                // Cari URL yang berakhiran .m3u8 pakai Regex
                val regex = Regex("""https?://[^"']+\.m3u8[^"']*""")
                val match = regex.find(unpacked)
                
                if (match != null) {
                    m3u8Url = match.value
                    break
                }
            }
        }
        
        // Jika videonya tidak di-pack, kita cari langsung di seluruh HTML sebagai cadangan
        if (m3u8Url == null) {
            val html = document.html()
            val regex = Regex("""https?://[^"']+\.m3u8[^"']*""")
            m3u8Url = regex.find(html)?.value
        }

        // Kalau ketemu, kita kirim linknya ke player CloudStream
        if (m3u8Url != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = data, // Sangat penting agar tidak diblokir surrit.com
                    quality = Qualities.Unknown.value, // Exoplayer akan mengurus resolusinya
                    type = ExtractorLinkType.M3U8 
                )
            )
            return true
        }

        return false
    }
}
