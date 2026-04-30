package com.Pornhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class PornhubProvider : MainAPI() {
    override var name = "Pornhub"
    override var mainUrl = "https://www.pornhub.com"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // Cookies rahasia untuk menembus peringatan 18+ (Age-Gate Bypass)
    private val phCookies = mapOf(
        "bs" to "1",
        "accessAgeDisclaimerPH" to "2",
        "age_verified" to "1",
        "platform" to "pc"
    )

    // Menu Halaman Utama
    override val mainPage = mainPageOf(
        "$mainUrl/video?o=mr" to "Recently Added",
        "$mainUrl/video?o=ht" to "Hot",
        "$mainUrl/video?o=mv" to "Most Viewed",
    )

    // Mengambil daftar video di halaman utama (Mendukung scroll/paginasi)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val doc = app.get(url, cookies = phCookies).document
        
        val home = doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Mengambil daftar video dari kolom pencarian (Mendukung paginasi)
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/video/search?search=$query&page=$page"
        val doc = app.get(url, cookies = phCookies).document
        
        val results = doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
        
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    // Helper: Mengubah elemen HTML video menjadi objek CloudStream
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title a")?.text() ?: return null
        val href = mainUrl + this.selectFirst(".title a")?.attr("href")
        
        // Coba ambil gambar kualitas bagus dulu, kalau gagal ambil yang biasa
        val posterUrl = this.selectFirst("img")?.attr("data-mediumthumb") 
            ?: this.selectFirst("img")?.attr("src")
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addDuration(this@toSearchResult.selectFirst(".duration")?.text())
        }
    }

    // Mengambil detail info saat video diklik
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cookies = phCookies).document
        val title = doc.selectFirst("h1.title")?.text() ?: ""
        val poster = doc.selectFirst("link[property=og:image]")?.attr("content")
        
        // Parameter ke-4 adalah dataUrl yang akan dilempar ke fungsi loadLinks
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = doc.selectFirst(".video-description")?.text()
            this.tags = doc.select(".categoriesWrapper a").map { it.text() }
            this.recommendations = doc.select("li.videoblock").mapNotNull { it.toSearchResult() }
        }
    }

    // Struktur data untuk mempermudah membaca JSON video PH
    data class MediaDefinition(
        val format: String?,
        val quality: String?,
        val videoUrl: String?
    )

    // Mengekstrak link video asli (Bypass proteksi JSON & HLS)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Harus pakai cookies agar bisa masuk ke halaman player
        val html = app.get(data, cookies = phCookies).text
        
        // Mencari script JSON yang menyimpan link video
        val mediaDefsRegex = Regex(""""mediaDefinitions"\s*:\s*(\[.*?\])""")
        val match = mediaDefsRegex.find(html)
        
        if (match != null) {
            val jsonString = match.groupValues[1]
            
            try {
                // Ubah teks JSON menjadi bentuk List<MediaDefinition>
                val mediaList = parseJson<List<MediaDefinition>>(jsonString)
                
                mediaList.forEach { media ->
                    val videoUrl = media.videoUrl ?: return@forEach
                    val format = media.format ?: ""
                    val quality = media.quality ?: "Unknown"
                    
                    // Bersihkan karakter aneh pada URL
                    val cleanUrl = videoUrl.replace("\\/", "/")
                    if (cleanUrl.isBlank()) return@forEach

                    // Konversi kualitas string (misal "1080") ke format CloudStream
                    val qualInt = getQualityFromName(quality)

                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "PH Player $quality",
                            url = cleanUrl,
                            referer = mainUrl, // WAJIB: Surat pengantar untuk bypass proteksi Origin
                            quality = qualInt,
                            isM3u8 = format.contains("hls", true) || cleanUrl.contains(".m3u8")
                        )
                    )
                }
                return true
            } catch (e: Exception) {
                e.printStackTrace() // Abaikan jika terjadi error parsing
            }
        }
        return false
    }
}
