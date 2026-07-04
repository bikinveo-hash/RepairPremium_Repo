package com.javtiful

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class JavtifulProvider : MainAPI() {
    override var name = "Javtiful"
    override var mainUrl = "https://javtiful.com"
    override var lang = "id"
    
    // Mengubah tipe utama provider menjadi NSFW agar terfilter dengan benar di aplikasi
    override val supportedTypes = setOf(TvType.NSFW)[span_2](start_span)[span_2](end_span)

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

        // Mengubah parameter tipe data response menjadi TvType.NSFW
        return newMovieSearchResponse(title, href, TvType.NSFW) {[span_3](start_span)[span_3](end_span)
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

        // Mengubah parameter tipe data load menjadi TvType.NSFW
        return newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {[span_4](start_span)[span_4](end_span)
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
                            name = "Javtiful (R2 Storage)",
                            source = name,
                            url = streamUrl,
                            referer = "$mainUrl/",
                            quality = resQuality,
                            type = ExtractorLinkType.VIDEO
                        )
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
                        name = "Javtiful Native Source",
                        source = name,
                        url = fixUrl(videoUrl),
                        referer = "$mainUrl/",
                        type = ExtractorLinkType.VIDEO
                    )
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
