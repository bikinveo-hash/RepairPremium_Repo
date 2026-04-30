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

    // Cookies rahasia untuk menembus peringatan 18+
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
        return newHomePageResponse(request.name, home)
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
        
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-mediumthumb")?.takeIf { it.isNotBlank() } 
            ?: imgElement?.attr("src")
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            // PERBAIKAN 1: Tambahkan header Referer agar gambar tidak error 403
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, cookies = phCookies).document
        val title = doc.selectFirst("h1.title")?.text() ?: ""
        val poster = doc.selectFirst("link[property=og:image]")?.attr("content")
        val durationText = doc.selectFirst(".duration")?.text()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            // PERBAIKAN 1: Header untuk gambar poster detail
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = doc.selectFirst(".video-description")?.text()
            this.tags = doc.select(".categoriesWrapper a").map { it.text() }
            
            // PERBAIKAN 3: Ambil nama DAN gambar aktor agar tampil bulat seperti Adicinemax21
            val actorsList = doc.select(".pornstarsWrapper a").mapNotNull { aTag ->
                val actorName = aTag.text().trim()
                if (actorName.isNotEmpty()) {
                    // Cari gambar di dalam tag <a> jika ada, atau gunakan null
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

    // PERBAIKAN 2: Ubah tipe data `quality` menjadi `Any?` untuk menghindari jebakan Array JSON
    data class MediaDefinition(
        val format: String?,
        val quality: Any?, 
        val videoUrl: String?
    )

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
                val mediaList = parseJson<List<MediaDefinition>>(jsonString)
                
                mediaList.forEach { media ->
                    val videoUrl = media.videoUrl ?: return@forEach
                    val format = media.format ?: ""
                    
                    // PERBAIKAN 2 (Lanjutan): Ekstrak nilai quality dengan aman
                    val rawQuality = media.quality
                    val qualityStr = when (rawQuality) {
                        is List<*> -> rawQuality.firstOrNull()?.toString() ?: "Unknown"
                        else -> rawQuality?.toString() ?: "Unknown"
                    }
                    
                    val cleanUrl = videoUrl.replace("\\/", "/")
                    if (cleanUrl.isBlank()) return@forEach

                    val qualInt = getQualityFromName(qualityStr)
                    val linkType = if (format.contains("hls", true) || cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback(
                        newExtractorLink(
                            source = this@PornhubProvider.name,
                            name = "PH Player $qualityStr",
                            url = cleanUrl,
                            type = linkType
                        ) {
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
