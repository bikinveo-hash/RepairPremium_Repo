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

    // Cookies anti-blokir
    private val phCookies = mapOf(
        "bs" to "1",
        "accessAgeDisclaimerPH" to "2",
        "age_verified" to "1",
        "platform" to "pc",
        "cookieConsent" to "3"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/video?o=mr" to "Recently Added",
        "$mainUrl/video?o=ht" to "Hot",
        "$mainUrl/video?o=mv" to "Most Viewed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        
        // PERBAIKAN: Selalu bawa User-Agent agar tidak dicurigai bot
        val doc = app.get(url, cookies = phCookies, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        val home = doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
        
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
        // Format query persis seperti browser (spasi jadi +)
        val formattedQuery = query.replace(" ", "+")
        val url = "$mainUrl/video/search?search=$formattedQuery&page=$page"
        
        // PERBAIKAN FATAL: Bawa User-Agent agar tidak ditendang (redirect) ke Beranda!
        val doc = app.get(url, cookies = phCookies, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        val results = doc.select("li.videoblock, li.pcVideoListItem").mapNotNull {
            it.toSearchResult()
        }
        
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".title a, span.title a") ?: return null
        val title = titleElement.text()
        val href = fixUrl(titleElement.attr("href"))
        
        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-image")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-thumb_url")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-mediumthumb")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")
        
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // PERBAIKAN: Bawa User-Agent
        val doc = app.get(url, cookies = phCookies, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = doc.selectFirst("h1.title")?.text() ?: ""
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val durationText = doc.selectFirst(".duration")?.text()
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = doc.selectFirst(".video-description")?.text()
            this.tags = doc.select(".categoriesWrapper a").map { it.text() }
            
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
            this.recommendations = doc.select("li.videoblock, li.pcVideoListItem").mapNotNull { it.toSearchResult() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, cookies = phCookies, headers = mapOf("User-Agent" to USER_AGENT)).text
        
        var jsonString = ""
        val startIndex = html.indexOf("\"mediaDefinitions\":[")
        if (startIndex != -1) {
            var bracketCount = 0
            val arrayStart = html.indexOf("[", startIndex)
            for (i in arrayStart until html.length) {
                val char = html[i]
                if (char == '[') bracketCount++
                else if (char == ']') bracketCount--
                
                if (bracketCount == 0) {
                    jsonString = html.substring(arrayStart, i + 1)
                    break
                }
            }
        }
        
        if (jsonString.isNotBlank()) {
            try {
                val mediaList = parseJson<List<Map<String, Any>>>(jsonString)
                
                mediaList.forEach { media ->
                    val videoUrl = media["videoUrl"] as? String ?: return@forEach
                    if (videoUrl.isBlank()) return@forEach
                    
                    val format = media["format"] as? String ?: ""
                    
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
