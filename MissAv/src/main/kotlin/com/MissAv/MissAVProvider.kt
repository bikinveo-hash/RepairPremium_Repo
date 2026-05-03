package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    
    // Dikembalikan ke habitat aslinya: NSFW
    override val supportedTypes = setOf(TvType.NSFW)
    
    override val usesWebView = true 

    // ==========================================
    // 1. HALAMAN DEPAN (Home Page)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        // LANGKAH 1: Kita singgah sebentar di halaman utama untuk 'mencuri' link kategori dengan kode DM terbaru (contoh: dm628)
        val mainDoc = app.get("$mainUrl/id").document
        
        val urlRelease = mainDoc.select("a:contains(Keluaran terbaru)").attr("href").takeIf { it.isNotBlank() } ?: "$mainUrl/id/release"
        val urlNew = mainDoc.select("a:contains(Recent update)").attr("href").takeIf { it.isNotBlank() } ?: "$mainUrl/id/new"
        val urlUncensored = mainDoc.select("a:contains(Kebocoran tanpa sensor)").attr("href").takeIf { it.isNotBlank() } ?: "$mainUrl/id/uncensored-leak"

        // LANGKAH 2: Masukkan link yang sudah akurat ke dalam daftar scraping kita
        val urls = listOf(
            Pair("Keluaran Terbaru", urlRelease),
            Pair("Baru Ditambahkan", urlNew),
            Pair("Tanpa Sensor", urlUncensored)
        )

        for ((name, url) in urls) {
            try {
                // Tembak halamannya
                val document = app.get(url).document
                
                val videos = document.select("div.thumbnail").mapNotNull { element ->
                    val titleElement = element.selectFirst("div.my-2 a") ?: return@mapNotNull null
                    
                    val title = titleElement.text().trim()
                    val videoUrl = titleElement.attr("href")
                    
                    // Lewati template Alpine.js yang kosong
                    if (title.isEmpty() || videoUrl.contains("javascript:") || videoUrl == "#") {
                        return@mapNotNull null
                    }
                    
                    val img = element.selectFirst("img")
                    val posterUrl = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src")

                    // Pastikan menggunakan TvType.NSFW
                    newMovieSearchResponse(title, videoUrl, TvType.NSFW) {
                        this.posterUrl = posterUrl
                    }
                }
                
                if (videos.isNotEmpty()) {
                    items.add(HomePageList(name, videos))
                }
            } catch (e: Exception) {
                // Biarkan saja kalau ada 1 kategori yang gagal, lanjut ke kategori berikutnya
            }
        }

        return newHomePageResponse(
            list = items,
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
