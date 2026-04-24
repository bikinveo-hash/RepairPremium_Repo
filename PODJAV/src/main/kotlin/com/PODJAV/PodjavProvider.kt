package com.PODJAV

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class PodjavProvider : MainAPI() {
    override var name = "PODJAV"
    override var mainUrl = "https://podjav.tv"
    override var lang = "id"
    override val hasMainPage = true
    
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Baru Upload",
        "$mainUrl/genre/affair/" to "Perselingkuhan",
        "$mainUrl/genre/abuse/" to "Pelecehan",
        "$mainUrl/genre/cuckold/" to "Istri Tidak Setia",
        "$mainUrl/genre/married-woman/" to "Wanita Menikah",
        "$mainUrl/genre/rape/" to "Kekerasan",
        "$mainUrl/genre/young-wife/" to "Istri Muda",
        "$mainUrl/genre/sweat/" to "Sweat",
        "$mainUrl/genre/kiss/" to "Kiss",
        "$mainUrl/genre/step-mother/" to "Ibu Tiri"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        if (this.hasClass("banner-card")) return null

        val url = this.attr("href")
        if (url.isBlank() || !url.startsWith("http")) return null

        val titleText = this.selectFirst(".card-title")?.text() ?: this.attr("data-title") ?: return null
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        val isUncensored = this.selectFirst(".badge-uncen") != null || this.attr("data-genre").contains("uncensored", ignoreCase = true)
        val finalTitle = if (isUncensored) "🔥 [UNCENSORED] $titleText" else titleText

        return newMovieSearchResponse(finalTitle, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = mutableListOf<HomePageList>()
        val url = if (page == 1) request.data else if (request.data == "$mainUrl/") "$mainUrl/page/$page/" else "${request.data}page/$page/"
        
        val document = app.get(url).document

        if (request.name == "Baru Upload" && page == 1) {
            document.select("section").forEach { section ->
                val sectionTitle = section.selectFirst(".section-title")?.text() ?: return@forEach
                if (sectionTitle.contains("Artis", true) || sectionTitle.contains("TENTANG", true) || sectionTitle.contains("FAQ", true)) return@forEach
                
                val list = section.select("a.video-card").mapNotNull { it.toSearchResult() }
                if (list.isNotEmpty()) items.add(HomePageList(sectionTitle, list))
            }
        } else {
            val elements = document.select("a.video-card")
            val list = elements.mapNotNull { it.toSearchResult() }
            if (list.isNotEmpty()) items.add(HomePageList(request.name, list))
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(items, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/?s=$query"
        return app.get(url).document.select("a.video-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleText = document.selectFirst("h1.video-info-title")?.text() ?: return null
        val posterUrl = document.selectFirst(".video-info-top img")?.attr("src")
        val plot = document.selectFirst("#tab-synopsis .text-sm p")?.text()
        
        val tags = mutableListOf<String>()
        var year: Int? = null
        val actors = mutableListOf<ActorData>()

        document.select(".info-row-item").forEach { row ->
            val label = row.selectFirst(".info-label")?.text()?.trim() ?: ""
            val values = row.select(".info-value a").map { it.text().trim() }
            when {
                label.contains("Genre", true) -> tags.addAll(values)
                label.contains("Cast", true) -> values.forEach { actors.add(ActorData(Actor(it))) }
                label.contains("Tahun", true) -> year = values.firstOrNull()?.toIntOrNull()
            }
        }

        val recommendations = document.select(".carousel-track a.reko-card").mapNotNull {
            val recUrl = it.attr("href") ?: return@mapNotNull null
            val recPoster = it.selectFirst("img")?.attr("src")
            val recTitle = it.selectFirst(".reko-card-title")?.text() ?: return@mapNotNull null
            newMovieSearchResponse(recTitle, recUrl, TvType.NSFW) { this.posterUrl = recPoster }
        }

        return newMovieLoadResponse(titleText, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.year = year
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    /**
     * 4. EKSTRAKSI LINK (MENGGUNAKAN LOGIKA DOOPLAY AJAX LAMA)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. CARI POST ID
        // Desain baru Podjav biasanya menyimpan Post ID di tag body class (misal: "postid-13485") atau di star-rating
        val bodyClass = document.selectFirst("body")?.attr("class") ?: ""
        val postIdRegex = Regex("""postid-(\d+)""")
        val postId = postIdRegex.find(bodyClass)?.groupValues?.get(1)
            ?: document.selectFirst(".star-rating")?.attr("data-post-id")
            ?: document.selectFirst(".dooplay_player_option")?.attr("data-post")

        if (postId != null) {
            // Kita lakukan brute force "nume" (Server 1 sampai 5) karena tombolnya mungkin tersembunyi
            val numes = listOf("1", "2", "3", "4", "5")
            
            numes.forEach { nume ->
                try {
                    // POST AJAX seperti di kodemu yang lama
                    val ajaxResponse = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to postId,
                            "nume" to nume,
                            "type" to "movie"
                        ),
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsedSafe<DooplayAjaxResponse>()

                    val embedUrl = ajaxResponse?.embedUrl

                    if (embedUrl != null && embedUrl.isNotBlank()) {
                        // CEK JIKA PAKAI FORMAT "source="
                        if (embedUrl.contains("source=")) {
                            val sourceRegex = Regex("""source=([^&]+)""")
                            val match = sourceRegex.find(embedUrl)

                            if (match != null) {
                                val finalLink = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                                val isMp4 = finalLink.contains(".mp4")

                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "Server $nume " + if (isMp4) "(MP4)" else "(M3U8)",
                                        url = finalLink,
                                        type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = mainUrl
                                        this.quality = Qualities.P720.value
                                    }
                                )

                                // Ekstrak subtitle dari VOD
                                if (!isMp4 && finalLink.contains("/master.m3u8")) {
                                    val baseUrl = finalLink.substringBeforeLast("/")
                                    val slug = baseUrl.substringAfterLast("/")
                                    val currentTime = System.currentTimeMillis() / 1000
                                    subtitleCallback.invoke(
                                        SubtitleFile(lang = "Indonesia", url = "$baseUrl/$slug.srt?t=$currentTime")
                                    )
                                }
                            }
                        } 
                        // DIRECT LINK ATAU IFRAME PIHAK KETIGA
                        else {
                            val iframeUrl = org.jsoup.Jsoup.parse(embedUrl).select("iframe").attr("src").takeIf { it.isNotBlank() } ?: embedUrl

                            if (iframeUrl.startsWith("http")) {
                                val isDirectMp4 = iframeUrl.contains(".mp4")
                                val isDirectM3u8 = iframeUrl.contains(".m3u8")

                                if (isDirectMp4 || isDirectM3u8) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "VOD Server $nume",
                                            name = "Direct Stream " + if (isDirectMp4) "(MP4)" else "(M3U8)",
                                            url = iframeUrl,
                                            type = if (isDirectMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = mainUrl
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                } else {
                                    // BONGKAR MANUAL SEPERTI JARING SAPU JAGAT LAMA
                                    val isExtracted = loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)

                                    if (!isExtracted) {
                                        try {
                                            val iframeResponse = app.get(iframeUrl, referer = mainUrl).text
                                            val unpacked = getAndUnpack(iframeResponse)

                                            val m3u8Regex = Regex("""["']((?:https?://|/)[^"']*\.m3u8[^"']*)["']""")
                                            var m3u8Link = m3u8Regex.find(unpacked)?.groupValues?.get(1) 
                                                ?: m3u8Regex.find(iframeResponse)?.groupValues?.get(1)

                                            if (m3u8Link != null) {
                                                if (m3u8Link.startsWith("/")) {
                                                    val uri = URI(iframeUrl)
                                                    m3u8Link = "${uri.scheme}://${uri.host}$m3u8Link"
                                                }

                                                callback.invoke(
                                                    newExtractorLink(
                                                        source = "Server $nume",
                                                        name = "External (M3U8)",
                                                        url = m3u8Link,
                                                        type = ExtractorLinkType.M3U8
                                                    ) {
                                                        this.referer = iframeUrl 
                                                        this.quality = Qualities.P720.value
                                                    }
                                                )
                                            }
                                        } catch (e: Exception) {
                                            // Abaikan jika gagal
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Lanjut ke nume berikutnya jika AJAX gagal
                }
            }
        }
        return true
    }
}

/**
 * DATA CLASS: Untuk parsing JSON response dari AJAX Dooplay
 */
data class DooplayAjaxResponse(
    @JsonProperty("embed_url") val embedUrl: String?,
    @JsonProperty("type") val type: String?
)
