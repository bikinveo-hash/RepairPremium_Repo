package com.javtiful

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Memperbaiki Unresolved reference extractor utils
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class JavtifulProvider : MainAPI() {
    override var name = "Javtiful"
    override var mainUrl = "https://javtiful.com"
    override var lang = "id"
    
    // Menambahkan tipe data eksplit Set<TvType> untuk mencegah kesalahan inferensi compiler
    override val supportedTypes: Set<TvType> = setOf(TvType.NSFW)

    override val hasMainPage = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        Pair("id/foryou", "Untuk Anda"),
        Pair("id/censored", "Sensor"),
        Pair("id/uncensored", "Tanpa Sensor"),
        Pair("id/reducing-mosaic", "Reducing Mosaic")
    )

    // ==================== LOGIKA HALAMAN UTAMA ====================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}?page=$page"
        }

        val res = app.get(url, headers = baseHeaders)
        val document = res.document
        
        val homeItems = document.select("article.front-video-card").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // ==================== LOGIKA PENCARIAN BERPAGINASI ====================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchUrl = if (page <= 1) {
            "$mainUrl/id/search?q=$query"
        } else {
            "$mainUrl/id/search?page=$page&q=$query"
        }

        val res = app.get(searchUrl, headers = baseHeaders)
        val document = res.document

        val searchResults = document.select("article.front-video-card").mapNotNull {
            it.toSearchResult()
        }

        val hasNext = document.select("nav.front-pagination a.front-pagination-link").any { 
            it.text().contains("Berikutnya", ignoreCase = true) 
        }

        return newSearchResponseList(searchResults, hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("a.front-video-title") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        
        val thumbElement = this.selectFirst("a.front-video-thumb img")
        val posterUrl = thumbElement?.attr("data-front-lazy-src")?.takeIf { it.isNotBlank() }
            ?: thumbElement?.attr("src")

        val quality = this.selectFirst(".front-quality-tag")?.text() ?: "HD"

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
            addQuality(quality)
        }
    }

    // ==================== LOGIKA HALAMAN DETAIL (LOAD) ====================
    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = baseHeaders)
        val document = res.document

        val title = document.selectFirst(".front-watch-title h1")?.text() 
            ?: document.selectFirst("h1")?.text() 
            ?: "Javtiful Video"
        
        val posterUrl = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")

        val tagsList = document.select(".front-watch-link-chip").map { it.text() }

        val actorsList = document.select(".front-watch-actor-card").map { actorCard ->
            val actorName = actorCard.selectFirst("span")?.text() ?: ""
            val actorThumb = actorCard.selectFirst("img")?.attr("src")
            Actor(actorName, fixUrlNull(actorThumb))
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = plot
            this.tags = tagsList
            this.actors = actorsList.map { ActorData(it) }
        }
    }

    // ==================== EKSTRAKSI TAUTAN STREAMING ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = baseHeaders)
        val document = res.document

        val configScript = document.selectFirst("script#frontWatchConfig")?.data()
        if (!configScript.isNullOrBlank()) {
            val parsedConfig = tryParseJson<FrontWatchConfig>(configScript)
            
            parsedConfig?.playerSources?.forEach { source ->
                val streamUrl = source.src
                if (!streamUrl.isNullOrBlank()) {
                    val resQuality = source.size ?: 720
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Javtiful (R2 Storage)",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = resQuality
                        }
                    )
                }
            }
        }

        document.select("iframe").forEach { iframe ->
            val frameUrl = iframe.attr("src")
            if (frameUrl.isNotBlank()) {
                loadExtractor(frameUrl, subtitleCallback, callback)
            }
        }

        document.select("video source").forEach { srcTag ->
            val videoUrl = srcTag.attr("src")
            if (videoUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Javtiful Native Source",
                        url = fixUrl(videoUrl),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                    }
                )
            }
        }

        return true
    }

    data class FrontWatchConfig(
        @JsonProperty("playerSources") val playerSources: List<PlayerSource>? = null,
        @JsonProperty("videoTitle") val videoTitle: String? = null
    )

    data class PlayerSource(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("size") val size: Int? = null
    )
}
