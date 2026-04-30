package com.Pornhub

import com.fasterxml.jackson.databind.JsonNode
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

    // Cookies wajib untuk menembus peringatan 18+
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
        
        // Prioritaskan data-image atau data-thumb_url agar gambar tidak buram
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-image")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-thumb_url")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-mediumthumb")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")
        
        // Gunakan newAnimeSearchResponse agar posternya jadi horizontal (landscape)
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            // Tambahkan header Referer agar gambar tidak di-blokir (Error 403)
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
            this.posterHeaders = mapOf("Referer" to "$mainUrl/") // Header untuk poster detail
            this.plot = doc.selectFirst(".video-description")?.text()
            this.tags = doc.select(".categoriesWrapper a").map { it.text() }
            
            // Ekstraksi aktor dengan foto (Biar tampil bulat di UI)
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
                // Gunakan JsonNode agar kebal dari JSON array/string mismatch (Anti-Crash)
                val mediaList = parseJson<List<JsonNode>>(jsonString)
                
                mediaList.forEach { media ->
                    val videoUrl = media.get("videoUrl")?.asText() ?: return@forEach
                    if (videoUrl.isBlank()) return@forEach
                    
                    val format = media.get("format")?.asText() ?: ""
                    
                    // Logika kebal crash untuk mengekstrak 'quality'
                    val rawQuality = media.get("quality")
                    val qualityStr = if (rawQuality != null && rawQuality.isArray && rawQuality.size() > 0) {
                        rawQuality.get(0).asText() // Jika JSON berbentuk Array [240]
                    } else {
                        rawQuality?.asText() ?: "Unknown" // Jika JSON berbentuk String "1080"
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
