package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    
    // SEMENTARA kita gunakan TvType.Movie agar pasti muncul di beranda dan tidak difilter
    override val supportedTypes = setOf(TvType.Movie)
    
    override val usesWebView = true 

    // ==========================================
    // 1. HALAMAN DEPAN (Home Page)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/id").document

        // ALARM CLOUDFLARE: Biar kita tahu kalau diam-diam diblokir
        if (document.title().contains("Just a moment") || document.title().contains("Cloudflare")) {
            throw Error("Terhalang Cloudflare! Tolong buka webview/pengaturan untuk verifikasi.")
        }

        val videos = document.select("div.thumbnail").mapNotNull { element ->
            // Cari link (tag <a>) yang memuat judul
            val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
            
            val title = titleElement.text().trim()
            val url = titleElement.attr("href")
            
            // Lewati template kosong bawaan web
            if (title.isEmpty() || url.contains("javascript:") || url == "#") {
                return@mapNotNull null
            }
            
            val posterUrl = element.selectFirst("img")?.let { img ->
                val dataSrc = img.attr("data-src")
                if (dataSrc.isNullOrEmpty()) img.attr("src") else dataSrc
            }

            // Gunakan TvType.Movie sementara untuk bypass filter
            newMovieSearchResponse(title, url, TvType.Movie) {
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
