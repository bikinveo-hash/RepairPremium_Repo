package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true
    override var sequentialMainPage = true
    
    // KUNCI SAKTI: Memaksa Cloudstream menggunakan Browser Asli (WebView)
    // untuk melewati blokir Cloudflare / Anti-Bot
    override val usesWebView = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "new" to "Recent Update",
        "release" to "Keluaran Terbaru",
        "uncensored-leak" to "Tanpa Sensor",
        "today-hot" to "Paling Banyak Dilihat Hari Ini",
        "weekly-hot" to "Paling Banyak Dilihat Minggu Ini"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/id/${request.data}"
        } else {
            "$mainUrl/id/${request.data}?page=$page"
        }
        
        val document = app.get(url, headers = headers).document
        
        val home = document.select("div.thumbnail").mapNotNull { 
            toSearchResult(it) 
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/id/search/$query"
        val document = app.get(url, headers = headers).document

        return document.select("div.thumbnail").mapNotNull { 
            toSearchResult(it) 
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val title = element.selectFirst("a.text-secondary")?.text() 
            ?: element.selectFirst("img")?.attr("alt") 
            ?: return null
            
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Tanpa Judul"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val year = document.selectFirst("div.text-secondary time")?.text()?.split("-")?.firstOrNull()?.toIntOrNull()
        val tags = document.select("div.text-secondary:contains(Genre:) a").map { it.text() }
        val actorsList = document.select("div.text-secondary:contains(Aktris:) a, div.text-secondary:contains(Aktor:) a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = actorsList.map { ActorData(Actor(it)) } 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, headers = headers).text

        val uuidRegex = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})[\\/]+seek""")
        val match = uuidRegex.find(html)

        if (match != null) {
            val uuid = match.groupValues[1]
            val m3u8Url = "https://surrit.com/$uuid/playlist.m3u8"

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8 
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
