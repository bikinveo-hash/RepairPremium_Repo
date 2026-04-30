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

    // Menu kategori halaman utama
    override val mainPage = mainPageOf(
        "$mainUrl/video?o=mr" to "Recently Added",
        "$mainUrl/video?o=ht" to "Hot",
        "$mainUrl/video?o=mv" to "Most Viewed",
    )

    // Fungsi memuat halaman utama beserta paginasinya
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val doc = app.get(url, cookies = phCookies).document
        
        val home = doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Fungsi pencarian (Search) dengan paginasi
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/video/search?search=$query&page=$page"
        val doc = app.get(url, cookies = phCookies).document
        
        val results = doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
        
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    // Helper: Mengubah elemen HTML kotak video menjadi kartu pencarian
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title a")?.text() ?: return null
        
        // Memastikan link tidak error (menghindari duplikasi https://)
        val rawHref = this.selectFirst(".title a")?.attr("href") ?: return null
        val href = fixUrl(rawHref) 
        
        // Memilih gambar resolusi bagus, atau fallback ke resolusi standar
        val posterUrl = this.selectFirst("img")?.attr("data-mediumthumb") 
            ?: this.selectFirst("img")?.attr("src")
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // Fungsi memuat detail spesifik sebuah video
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cookies = phCookies).document
        val title = doc.selectFirst("h1.title")?.text() ?: ""
        val poster = doc.selectFirst("link[property=og:image]")?.attr("content")
        val durationText = doc.selectFirst(".duration")?.text()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = doc.selectFirst(".video-description")?.text()
            this.tags = doc.select(".categoriesWrapper a").map { it.text() }
            
            // Ekstrak nama aktor/pornstar jika ada
            val actors = doc.select(".pornstarsWrapper a").map { it.text() }
            if (actors.isNotEmpty()) {
                addActors(actors)
            }
            
            // Video rekomendasi di bagian bawah
            this.recommendations = doc.select("li.videoblock").mapNotNull { it.toSearchResult() }
            
            // Tambahkan durasi video
            addDuration(durationText)
        }
    }

    // Class pembantu untuk mengkonversi JSON video nantinya
    data class MediaDefinition(
        val format: String?,
        val quality: String?,
        val videoUrl: String?
    )

    // Otak pemburu link streaming asli
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ambil HTML halaman video. Cookies sangat wajib agar tidak diblokir.
        val html = app.get(data, cookies = phCookies).text
        
        // Temukan data "mediaDefinitions" di dalam script tag
        val mediaDefsRegex = Regex(""""mediaDefinitions"\s*:\s*(\[.*?\])""")
        val match = mediaDefsRegex.find(html)
        
        if (match != null) {
            val jsonString = match.groupValues[1]
            
            try {
                // Konversi string JSON ke list MediaDefinition
                val mediaList = parseJson<List<MediaDefinition>>(jsonString)
                
                mediaList.forEach { media ->
                    val videoUrl = media.videoUrl ?: return@forEach
                    val format = media.format ?: ""
                    val quality = media.quality ?: "Unknown"
                    
                    // Bersihkan karakter escape yang mengganggu link
                    val cleanUrl = videoUrl.replace("\\/", "/")
                    if (cleanUrl.isBlank()) return@forEach

                    val qualInt = getQualityFromName(quality)
                    val linkType = if (format.contains("hls", true) || cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    // Bangun link player menggunakan cara terbaru (newExtractorLink)
                    callback(
                        newExtractorLink(
                            source = this@PornhubProvider.name,
                            name = "PH Player $quality",
                            url = cleanUrl,
                            type = linkType
                        ) {
                            // Referer SANGAT PENTING untuk membypass proteksi CORS
                            this.referer = mainUrl
                            this.quality = qualInt
                        }
                    )
                }
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }
}
