package com.michat88

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup

data class XhVideo(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("pageURL") val pageURL: String? = null,
    @JsonProperty("thumbURL") val thumbURL: String? = null
)

data class XhSuggest(
    @JsonProperty("plainText") val plainText: String? = null,
    @JsonProperty("link") val link: String? = null
)

class Xhamster : MainAPI() {
    override var mainUrl = "https://xhamster.com"
    override var name = "xHamster"
    override val supportedTypes = setOf(TvType.NSFW) 
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true 

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    )

    // MENAMBAHKAN KATEGORI DI SINI
    // Format: "URL Target" to "Nama Tab di Aplikasi"
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending",
        "$mainUrl/categories/indonesian" to "Indo",
        "$mainUrl/categories/uncensored" to "Uncensored",
        "$mainUrl/categories/russian" to "Russian",
        "$mainUrl/categories/chinese" to "Chinese",
        "$mainUrl/tags/japanese-mom-uncensored" to "Japanese Mom"
    )

    private fun extractVideos(html: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        val addedUrls = mutableSetOf<String>()

        val document = Jsoup.parse(html)
        val scriptData = document.selectFirst("script#initials-script")?.data() ?: html

        val regex = """"title":"(.*?)","thumbId"[^\{]*?"pageURL":"([^"]+)","thumbURL":"([^"]+)"""".toRegex()
        
        regex.findAll(scriptData).forEach { match ->
            val title = match.groupValues[1].replace("\\\"", "\"").replace("\\/", "/")
            val url = match.groupValues[2].replace("\\/", "/")
            val posterUrl = match.groupValues[3].replace("\\/", "/")

            if (url.isNotBlank() && url.contains("/videos/") && !addedUrls.contains(url)) {
                addedUrls.add(url)
                items.add(
                    newMovieSearchResponse(title, url, TvType.NSFW) {
                        this.posterUrl = posterUrl
                        this.posterHeaders = mapOf("referer" to mainUrl)
                    }
                )
            }
        }
        
        if (items.isEmpty()) {
            document.select("a.video-thumb, a.thumb-image-container, a.mobile-thumb-player-container").forEach { element ->
                val url = element.attr("href") ?: ""
                val img = element.selectFirst("img")
                val title = element.attr("aria-label").ifBlank { img?.attr("alt") ?: "" }
                
                var posterUrl = img?.attr("data-src") ?: img?.attr("srcset")?.substringBefore(" ") ?: img?.attr("src") ?: ""
                if (posterUrl.contains("data:image")) posterUrl = ""

                if (title.isNotBlank() && url.isNotBlank() && url.contains("/videos/") && !addedUrls.contains(url)) {
                    addedUrls.add(url)
                    items.add(
                        newMovieSearchResponse(title, url, TvType.NSFW) {
                            this.posterUrl = posterUrl
                            this.posterHeaders = mapOf("referer" to mainUrl)
                        }
                    )
                }
            }
        }
        return items
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Logika paginasi ini sudah mendukung struktur /kategori/nama-kategori/2 untuk xHamster
        val pageUrl = if (page == 1) request.data else "${request.data.removeSuffix("/")}/$page"
        val html = app.get(pageUrl, headers = headers).text
        val items = extractVideos(html)
        
        return newHomePageResponse(
            name = request.name,
            list = items,
            hasNext = items.isNotEmpty() 
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val html = app.get("$mainUrl/search/$query?page=$page", headers = headers).text
        val searchItems = extractVideos(html)
        
        return newSearchResponseList(
            list = searchItems,
            hasNext = searchItems.isNotEmpty() 
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val html = app.get("$mainUrl/search/$query?page=1", headers = headers).text
        return extractVideos(html)
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url, headers = headers).text
        val document = Jsoup.parse(html)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = document.select("a[href^=https://xhamster.com/categories/] span").map { it.text() }

        val recommendations = extractVideos(html).filter { it.url != url }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url 
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.posterHeaders = mapOf("referer" to mainUrl)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = headers).text
        val document = Jsoup.parse(html)

        val m3u8Url = document.selectFirst("link[rel=preload][as=fetch][href*=.m3u8]")?.attr("href")
        if (m3u8Url != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            val mp4Url = document.selectFirst("video.video_container__no-script-video")?.attr("src")
            if (mp4Url != null) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Fallback",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P480.value
                    }
                )
            }
        }

        val subtitleRegex = """"label":"([^"]+)","urls":\{"vtt":"([^"]+)"""".toRegex()
        subtitleRegex.findAll(html).forEach { matchResult ->
            val langLabel = matchResult.groupValues[1]
            val vttUrl = matchResult.groupValues[2].replace("\\/", "/")
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = langLabel,
                    url = vttUrl
                ) {}
            )
        }

        return true
    }
}
