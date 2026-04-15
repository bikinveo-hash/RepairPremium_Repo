package com.PODJAV

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PodjavProvider : MainAPI() {
    override var name = "PODJAV"
    override var mainUrl = "https://podjav.tv"
    override var lang = "id"
    override val hasMainPage = true
    
    // LABEL NSFW: Mengubah tipe konten menjadi khusus dewasa
    override val supportedTypes = setOf(TvType.NSFW)

    // MENAMBAHKAN DAFTAR KATEGORI (GENRE)
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Baru Upload",
        "$mainUrl/genre/affair/" to "Perselingkuhan",
        "$mainUrl/genre/abuse/" to "Pelecehan",
        "$mainUrl/genre/cuckold/" to "Istri Tidak Setia",
        "$mainUrl/genre/married-woman/" to "Wanita Menikah",
        "$mainUrl/genre/rape/" to "Kekerasan",
        "$mainUrl/genre/young-wife/" to "Istri Muda",
        "$mainUrl/genre/sweat/" to "Sweat",
        "$mainUrl/genre/kiss/" to "Kiss"
    )

    /** * HELPER: Mengubah elemen HTML daftar film menjadi SearchResponse 
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".data h3 a")?.text() 
            ?: this.selectFirst(".poster img")?.attr("alt") 
            ?: return null
        
        val url = this.selectFirst(".data h3 a")?.attr("href") 
            ?: this.selectFirst(".poster a")?.attr("href") 
            ?: return null
            
        val posterUrl = this.selectFirst(".poster img")?.attr("src")

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    /** * 1. HALAMAN UTAMA & KATEGORI
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val items = mutableListOf<HomePageList>()
        
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data == "$mainUrl/") "$mainUrl/page/$page/" else "${request.data}page/$page/"
        }
        
        val document = app.get(url).document

        if (request.name == "Baru Upload") {
            if (page == 1) {
                val featuredElements = document.select("#featured-titles article.item")
                if (featuredElements.isNotEmpty()) {
                    val featuredList = featuredElements.mapNotNull { it.toSearchResult() }
                    items.add(HomePageList("Terpopuler", featuredList))
                }
            }
            val collectionElements = document.select(".items.full article.item")
            if (collectionElements.isNotEmpty()) {
                val collectionList = collectionElements.mapNotNull { it.toSearchResult() }
                items.add(HomePageList(request.name, collectionList))
            }
        } else {
            val elements = document.select("article.item")
            if (elements.isNotEmpty()) {
                val list = elements.mapNotNull { it.toSearchResult() }
                items.add(HomePageList(request.name, list))
            }
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(items, hasNext = true)
    }

    /** * 2. PENCARIAN 
     */
    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select(".result-item article").mapNotNull { element ->
            val href = element.selectFirst(".details .title a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".details .title a")?.text() ?: return@mapNotNull null
            val posterUrl = element.selectFirst(".image .thumbnail img")?.attr("src")
            val year = element.selectFirst(".details .meta .year")?.text()?.toIntOrNull()

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    /** * 3. DETAIL FILM (Dilengkapi Aktor & Rekomendasi)
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".sheader .data h1")?.text() ?: document.selectFirst("h1")?.text() ?: return null
        val posterUrl = document.selectFirst(".sheader .poster img")?.attr("src")
        val plot = document.selectFirst(".wp-content p")?.text()
        val tags = document.select(".sgeneros a").map { it.text() }
        val year = document.selectFirst(".date")?.text()?.takeLast(4)?.toIntOrNull()

        // Mengambil daftar pemain (Cast) secara lebih tangguh (robust)
        val actors = document.select("#cast .persons .person").mapNotNull {
            val name = it.selectFirst(".data .name")?.text() ?: return@mapNotNull null
            val image = it.selectFirst(".img img")?.attr("src")
            ActorData(Actor(name, image))
        }

        // Mengambil film rekomendasi (Similar titles)
        val recommendations = document.select("#single_relacionados article").mapNotNull {
            val linkElem = it.selectFirst("a") ?: return@mapNotNull null
            val imgElem = it.selectFirst("img") ?: return@mapNotNull null
            
            val recTitle = imgElem.attr("alt") ?: return@mapNotNull null
            val recUrl = linkElem.attr("href") ?: return@mapNotNull null
            val recPoster = imgElem.attr("src")

            newMovieSearchResponse(recTitle, recUrl, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.year = year
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    /** * 4. EKSTRAKSI LINK VIDEO & SUBTITLE
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Rencana A: Mencari Iframe (Fallback)
        val iframeSrc = document.select("iframe").mapNotNull { it.attr("src") }
            .firstOrNull { it.contains("source=") }

        if (iframeSrc != null) {
            val sourceRegex = Regex("""source=([^&]+)""")
            val match = sourceRegex.find(iframeSrc)

            if (match != null) {
                val finalLink = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                val isMp4 = finalLink.contains(".mp4")
                
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "HD Stream " + if(isMp4) "(MP4)" else "(M3U8)",
                        url = finalLink,
                        type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P720.value
                    }
                )
            }
        } else {
            // Rencana B: Dooplay AJAX (Mendukung Multi-Server)
            val postId = document.selectFirst(".dooplay_player_option")?.attr("data-post") 
                ?: document.selectFirst("#player-option-1")?.attr("data-post")

            if (postId != null) {
                document.select(".dooplay_player_option").forEach { playerOption ->
                    val nume = playerOption.attr("data-nume") ?: return@forEach
                    
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
                    
                    if (embedUrl != null && embedUrl.contains("source=")) {
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

                            // Ekstraksi subtitle eksternal khusus untuk format M3U8
                            if (!isMp4 && finalLink.contains("/master.m3u8")) {
                                val baseUrl = finalLink.substringBeforeLast("/")
                                val slug = baseUrl.substringAfterLast("/")
                                val currentTime = System.currentTimeMillis() / 1000
                                val srtUrl = "$baseUrl/$slug.srt?t=$currentTime"

                                subtitleCallback.invoke(
                                    newSubtitleFile(lang = "id", url = srtUrl) {
                                        this.headers = mapOf("Referer" to mainUrl)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}

/** * DATA CLASS: Untuk parsing JSON response dari AJAX Dooplay 
 */
data class DooplayAjaxResponse(
    @JsonProperty("embed_url") val embedUrl: String?,
    @JsonProperty("type") val type: String?
)
