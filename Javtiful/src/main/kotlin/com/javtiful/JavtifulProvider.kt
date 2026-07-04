package com.javtiful

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class JavtifulProvider : MainAPI() {
    override var name = "Javtiful"
    override var mainUrl = "https://javtiful.com"
    override var lang = "id"
    override val supportedTypes: Set<TvType> = setOf(TvType.NSFW)

    override val hasMainPage = true

    // Base headers untuk bypass proteksi bot Cloudflare & penyamaran browser
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

    // Pemrosesan item card grid & Filter ganda (Partner & Kualitas Non-HD)
    private fun Element.toSearchResult(): SearchResponse? {
        // 1. FILTER PARTNER: Langsung skip jika kartu video berlabel partner / rekomendasi luar
        if (this.hasClass("front-partner-card") || this.selectFirst(".front-partner-badge") != null) {
            return null
        }

        // 2. FILTER LOGO HD: Cari elemen badge kualitas, wajib ada dan harus berlogo HD/4K
        val qualityElement = this.selectFirst(".front-quality-tag") ?: return null
        val quality = qualityElement.text()
        
        if (!quality.contains("HD", ignoreCase = true) && !quality.contains("4K", ignoreCase = true)) {
            return null
        }

        val titleElement = this.selectFirst("a.front-video-title") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        
        val thumbElement = this.selectFirst("a.front-video-thumb img")
        val posterUrl = thumbElement?.attr("data-front-lazy-src")?.takeIf { it.isNotBlank() }
            ?: thumbElement?.attr("src")

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

        // FITUR SARAN FILM: Mengambil item video terkait dari halaman detail (otomatis menyaring Partner & Non-HD)
        val recommendationsList = document.select("article.front-video-card").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = plot
            this.tags = tagsList
            this.actors = actorsList.map { ActorData(it) }
            this.recommendations = recommendationsList
        }
    }

    // ==================== EKSTRAKSI TAUTAN & SUBTITLE ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = baseHeaders)
        val document = res.document

        // ------------------------------------------------------------------
        // FITUR PENCARIAN SUBTITLE OTOMATIS VIA SUBTITLECAT
        // ------------------------------------------------------------------
        try {
            val rawCode = data.substringAfterLast("/").substringBefore("?")
            val codeRegex = Regex("""[a-zA-Z]{2,5}-\d{3,4}""")
            val videoCode = codeRegex.find(rawCode)?.value ?: rawCode
            
            val subSearchUrl = "https://www.subtitlecat.com/index.php?search=$videoCode"
            val subSearchDoc = app.get(subSearchUrl, headers = baseHeaders).document

            val resultLinks = subSearchDoc.select("table.sub-table tbody tr td a").take(5)

            resultLinks.forEachIndexed { index, element ->
                val resultPath = element.attr("href")
                val subLabel = element.parent()?.text()?.takeIf { it.isNotBlank() } ?: "Opsi ${index + 1}"
                
                if (resultPath.isNotBlank()) {
                    var detailUrl = if (resultPath.startsWith("http")) resultPath else "https://www.subtitlecat.com/${resultPath.removePrefix("/")}"
                    detailUrl = detailUrl.replace(" ", "%20")

                    try {
                        val subDetailDoc = app.get(detailUrl, headers = baseHeaders).document
                        val indoSubPath = subDetailDoc.selectFirst("#download_id")?.attr("href")
                        
                        if (!indoSubPath.isNullOrBlank()) {
                            var finalDownloadUrl = if (indoSubPath.startsWith("http")) indoSubPath else "https://www.subtitlecat.com/${indoSubPath.removePrefix("/")}"
                            finalDownloadUrl = finalDownloadUrl.replace(" ", "%20")
                            
                            subtitleCallback(
                                newSubtitleFile(
                                    lang = "ID - $subLabel",
                                    url = finalDownloadUrl
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Mencegah kerusakan loop jika satu berkas gagal dimuat
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // ------------------------------------------------------------------

        // Jalur Utama Player: Parsing objek JSON konfigurasi #frontWatchConfig
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
                            // PERBAIKAN BANDWIDTH: Memaksa ExoPlayer mengirimkan browser headers agar tidak di-throttle Cloudflare
                            this.headers = baseHeaders 
                        }
                    )
                }
            }
        }

        // Jalur Cadangan 1: Cek player iframe eksternal
        document.select("iframe").forEach { iframe ->
            val frameUrl = iframe.attr("src")
            if (frameUrl.isNotBlank()) {
                loadExtractor(frameUrl, subtitleCallback, callback)
            }
        }

        // Jalur Cadangan 2: Cek raw video source html5 native tag
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
                        this.headers = baseHeaders
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
