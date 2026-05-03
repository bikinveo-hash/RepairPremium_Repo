package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAvProvider : MainAPI() {
    // ==========================================
    // 1. KONFIGURASI DASAR
    // ==========================================
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true

    // FIX 1: Mencegah pemblokiran dari Cloudflare dengan memuat kategori 
    // secara berurutan (tidak menyerang server web sekaligus).
    override var sequentialMainPage = true

    // ==========================================
    // 2. KATEGORI HALAMAN UTAMA (HOMEPAGE)
    // ==========================================
    override val mainPage = mainPageOf(
        "new" to "Recent Update",
        "release" to "Keluaran Terbaru",
        "uncensored-leak" to "Tanpa Sensor",
        "today-hot" to "Paling Banyak Dilihat Hari Ini",
        "weekly-hot" to "Paling Banyak Dilihat Minggu Ini"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // FIX 2: Jika halaman 1, jangan gunakan parameter ?page=1
        val url = if (page == 1) {
            "$mainUrl/id/${request.data}"
        } else {
            "$mainUrl/id/${request.data}?page=$page"
        }
        
        val document = app.get(url).document
        
        // FIX 3: Menyederhanakan selector ke "div.thumbnail" saja
        val home = document.select("div.thumbnail").mapNotNull { 
            toSearchResult(it) 
        }

        return newHomePageResponse(request.name, home)
    }

    // ==========================================
    // 3. FITUR PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/id/search/$query"
        val document = app.get(url).document

        return document.select("div.thumbnail").mapNotNull { 
            toSearchResult(it) 
        }
    }

    // Fungsi pembantu untuk mengubah elemen HTML "thumbnail" menjadi SearchResponse
    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.selectFirst("a")?.attr("href") ?: return null
        
        // FIX 4: Pengambilan Judul dan Gambar yang lebih kuat (fallback berjenjang)
        val title = element.selectFirst("a.text-secondary")?.text() 
            ?: element.selectFirst("img")?.attr("alt") 
            ?: return null
            
        val img = element.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==========================================
    // 4. HALAMAN DETAIL (LOAD)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

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

    // ==========================================
    // 5. MENGEKSTRAK LINK VIDEO PLAYER (LOAD LINKS)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text

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
