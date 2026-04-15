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

    /** * 1. HALAMAN UTAMA 
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val items = mutableListOf<HomePageList>()
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        
        val document = app.get(url).document

        if (page == 1) {
            val featuredElements = document.select("#featured-titles article.item")
            if (featuredElements.isNotEmpty()) {
                val featuredList = featuredElements.mapNotNull { it.toSearchResult() }
                items.add(HomePageList("Baru Upload", featuredList))
            }
        }

        val collectionElements = document.select(".items.full article.item")
        if (collectionElements.isNotEmpty()) {
            val collectionList = collectionElements.mapNotNull { it.toSearchResult() }
            items.add(HomePageList("JAV Collection", collectionList))
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
            val href = element.selectFirst(".details .title a")?.attr("href") 
                ?: return@mapNotNull null
            
            val title = element.selectFirst(".details .title a")?.text() 
                ?: return@mapNotNull null
            
            val posterUrl = element.selectFirst(".image .thumbnail img")?.attr("src")
            val year = element.selectFirst(".details .meta .year")?.text()?.toIntOrNull()

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    /** * 3. DETAIL FILM 
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".sheader .data h1")?.text() 
            ?: document.selectFirst("h1")?.text() 
            ?: return null

        val posterUrl = document.selectFirst(".sheader .poster img")?.attr("src")
        val plot = document.selectFirst(".wp-content p")?.text()
        val tags = document.select(".sgeneros a").map { it.text() }
        val year = document.selectFirst(".date")?.text()?.takeLast(4)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.year = year
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

        // --- FUNGSI LOKAL: Agar video dan subtitle selalu dikirim bersamaan ---
        suspend fun processAndInvoke(m3u8Url: String, streamName: String) {
            // 1. Kirim Video
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = streamName,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P720.value
                }
            )

            // 2. Kirim Subtitle
            if (m3u8Url.contains("/master.m3u8")) {
                val baseUrl = m3u8Url.substringBeforeLast("/")
                val slug = baseUrl.substringAfterLast("/")
                val currentTime = System.currentTimeMillis() / 1000
                val srtUrl = "$baseUrl/$slug.srt?t=$currentTime"

                subtitleCallback.invoke(
                    newSubtitleFile(
                        lang = "id",
                        url = srtUrl
                    ) {
                        this.headers = mapOf("Referer" to mainUrl)
                    }
                )
            }
        }
        // -----------------------------------------------------------------------

        val iframeSrc = document.select("iframe").mapNotNull { it.attr("src") }
            .firstOrNull { it.contains("source=") }

        if (iframeSrc != null) {
            val sourceRegex = Regex("""source=([^&]+)""")
            val match = sourceRegex.find(iframeSrc)

            if (match != null) {
                val finalM3u8 = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                // Panggil fungsi lokal (Video + Subtitle)
                processAndInvoke(finalM3u8, "HD Stream")
            }
        } else {
            val postId = document.selectFirst("#player-option-1")?.attr("data-post")
            if (postId != null) {
                val ajaxResponse = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to "1",
                        "type" to "movie"
                    ),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<DooplayAjaxResponse>()
                
                val embedUrl = ajaxResponse?.embedUrl
                if (embedUrl != null && embedUrl.contains("source=")) {
                    val sourceRegex = Regex("""source=([^&]+)""")
                    val m = sourceRegex.find(embedUrl)
                    if (m != null) {
                        val finalM3u8 = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
                        // Panggil fungsi lokal di jalur Backup (Video + Subtitle)
                        processAndInvoke(finalM3u8, "HD (Backup)")
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
