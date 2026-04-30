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

    // Cookies wajib untuk menembus peringatan umur 18+
    private val phCookies = mapOf(
        "bs" to "1",
        "accessAgeDisclaimerPH" to "2",
        "age_verified" to "1",
        "platform" to "pc"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/video?o=mr" to "Recently Added",
        "$mainUrl/video?o=ht" to "Hot",
        "$mainUrl/video?o=mv" to "Most Viewed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val doc = app.get(url, cookies = phCookies).document
        
        val home = doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
        
        // Memaksa UI beranda menampilkan poster secara horizontal (landscape)
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true 
            ),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/video/search?search=$query&page=$page"
        val doc = app.get(url, cookies = phCookies).document
        
        val results = doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
        
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title a")?.text() ?: return null
        val rawHref = this.selectFirst(".title a")?.attr("href") ?: return null
        val href = fixUrl(rawHref) 
        
        // Prioritas tag gambar agar dapat resolusi High-Res dan tidak buram
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-image")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-thumb_url")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-mediumthumb")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")
        
        // Gunakan newAnimeSearchResponse agar layout kartu pencarian jadi Horizontal
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            // Bypass error 403 (Forbidden) dari server CDN gambar
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cookies = phCookies).document
        val title = doc.selectFirst("h1.title")?.text() ?: ""
        
        // Mengambil poster besar dari halaman detail (menggunakan meta property)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val durationText = doc.selectFirst(".duration")?.text()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = doc.selectFirst(".video-description")?.text()
            this.tags = doc.select(".categoriesWrapper a").map { it.text() }
            
            // Mengambil aktor dan fotonya agar tampil bulat rapi
            val actorsList = doc.select(".pornstarsWrapper a").mapNotNull { aTag ->
                val actorName = aTag.text().trim()
                if (actorName.isNotEmpty()) {
                    val actorImage = aTag.selectFirst("img")?.attr("src") ?: aTag.attr("data-image")
                    ActorData(Actor(actorName, actorImage.takeIf { it.isNotBlank() }))
                } else null
            }
            if (actorsList.isNotEmpty()) {
                this.actors = actorsList
            }
            
            this.duration = getDurationFromString(durationText)
            this.recommendations = doc.select("li.videoblock").mapNotNull { it.toSearchResult() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, cookies = phCookies).text
        
        val mediaDefsRegex = Regex(""""mediaDefinitions"\s*:\s*(\[.*?\])""")
        val match = mediaDefsRegex.find(html)
        
        if (match != null) {
            val jsonString = match.groupValues[1]
            
            try {
                // Trik kebal JSON: Parsing menggunakan Map untuk menghindari jebakan String/Array Mismatch
                val mediaList = parseJson<List<Map<String, Any>>>(jsonString)
                
                mediaList.forEach { media ->
                    val videoUrl = media["videoUrl"] as? String ?: return@forEach
                    if (videoUrl.isBlank()) return@forEach
                    
                    val format = media["format"] as? String ?: ""
                    
                    // Ekstraksi nilai resolusi dengan aman
                    val rawQuality = media["quality"]
                    val qualityStr = when (rawQuality) {
                        is List<*> -> rawQuality.firstOrNull()?.toString() ?: "Unknown"
                        else -> rawQuality?.toString() ?: "Unknown"
                    }
                    
                    val cleanUrl = videoUrl.replace("\\/", "/")

                    val qualInt = getQualityFromName(qualityStr)
                    val linkType = if (format.contains("hls", true) || cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback(
                        newExtractorLink(
                            source = this@PornhubProvider.name,
                            name = "PH Player $qualityStr",
                            url = cleanUrl,
                            type = linkType
                        ) {
                            // WAJIB ADA: Surat pengantar (Headers) untuk bypass proteksi CORS Player
                            this.headers = mapOf(
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/"
                            )
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
