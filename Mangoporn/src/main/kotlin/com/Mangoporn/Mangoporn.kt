package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.*

class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

    // Menggunakan User-Agent Windows Desktop yang lebih aman dari deteksi bot
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    )

    // ==============================
    // 1. MAIN PAGE CONFIGURATION
    // ==============================
    override val mainPage = mainPageOf(
        "$mainUrl/trending/" to "Trending",
        "$mainUrl/ratings/" to "Top Rated",
        "$mainUrl/genres/porn-movies/" to "Porn Movies",
        "$mainUrl/xxxclips/" to "XXX Clips",
        
        "$mainUrl/genre/18-teens/" to "18+ Teens",
        "$mainUrl/genre/all-girl/" to "All Girl",
        "$mainUrl/genre/all-sex/" to "All Sex",
        "$mainUrl/genre/asian/" to "Asian",
        "$mainUrl/genre/bbc/" to "BBC",
        "$mainUrl/genre/bbw/" to "BBW",
        "$mainUrl/genre/big-boobs/" to "Big Boobs",
        "$mainUrl/genre/big-butt/" to "Big Butt",
        "$mainUrl/genre/big-cocks/" to "Big Cocks",
        "$mainUrl/genre/blondes/" to "Blondes",
        "$mainUrl/genre/blowjobs/" to "Blowjobs",
        "$mainUrl/genre/cuckolds/" to "Cuckolds",
        "$mainUrl/genre/cumshots/" to "Cumshots",
        "$mainUrl/genre/deep-throat/" to "Deep Throat",
        "$mainUrl/genre/facials/" to "Facials"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Logic pintar untuk menangani pagination agar URL tidak double-slash
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.endsWith("/")) {
                "${request.data}page/$page/"
            } else {
                "${request.data}/page/$page/"
            }
        }

        val document = app.get(url, headers = headers).document
        
        // Mempertajam selector pencarian items
        val items = document.select("div.items article.item").mapNotNull {
            toSearchResult(it)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        // Fallback logic jika ada perubahan struktur judul
        val titleElement = element.selectFirst("div.data h3 a")
            ?: element.selectFirst("h3 > a") 
            ?: element.selectFirst("div.title > a")
            ?: return null

        val title = titleElement.text().trim()
        val url = fixUrl(titleElement.attr("href"))
        
        val imgElement = element.selectFirst("div.poster img") ?: element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$fixedQuery"
        
        val document = app.get(url, headers = headers).document
        
        return document.select("div.result-item, article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==============================
    // 3. LOAD DETAIL
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        
        val imgElement = document.selectFirst("div.poster img")
        val poster = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        val tags = document.select(".sgeneros a, .persons a[href*='/genre/']").map { it.text() }
        val year = document.selectFirst(".textco a[href*='/year/']")?.text()?.toIntOrNull()
        
        val actors = document.select("#cast .persons a[href*='/pornstar/']").map { 
            ActorData(Actor(it.text(), null)) 
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.actors = actors
        }
    }

    // ==============================
    // 4. LOAD LINKS
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val potentialLinks = mutableListOf<String>()

        document.select("#playeroptionsul li a").forEach { link ->
            val href = fixUrl(link.attr("href"))
            if (href.startsWith("http")) potentialLinks.add(href)
        }

        document.select("#playcontainer iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.startsWith("http")) potentialLinks.add(src)
        }

        fun getServerPriority(url: String): Int {
            return when {
                url.contains("dood") -> 0
                url.contains("streamtape") -> 1
                url.contains("voe.sx") -> 2
                url.contains("vidhide") -> 5
                url.contains("filemoon") -> 6
                url.contains("mixdrop") -> 10
                url.contains("streamsb") -> 11
                else -> 20
            }
        }

        if (potentialLinks.isNotEmpty()) {
            val sortedLinks = potentialLinks.sortedBy { getServerPriority(it) }

            coroutineScope {
                sortedLinks.map { link ->
                    launch(Dispatchers.IO) {
                        try {
                            loadExtractor(link, data, subtitleCallback, callback)
                        } catch (e: Exception) {
                            // Abaikan server yang gagal agar server lain tetap lanjut
                        }
                    }
                }
            }
            return true
        }

        return false
    }
}
