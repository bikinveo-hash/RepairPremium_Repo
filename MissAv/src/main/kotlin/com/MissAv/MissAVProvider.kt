package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAvProvider : MainAPI() {
    // Konfigurasi Dasar
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id" 
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true

    // 1. Kategori Halaman Utama
    override val mainPage = mainPageOf(
        "new" to "Recent Update",
        "release" to "Keluaran Terbaru",
        "uncensored-leak" to "Tanpa Sensor",
        "today-hot" to "Paling Banyak Dilihat Hari Ini",
        "weekly-hot" to "Paling Banyak Dilihat Minggu Ini"
    )

    // 2. Mengambil Data Halaman Utama
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Asumsi struktur pagination webnya menggunakan ?page=
        val url = "$mainUrl/id/${request.data}?page=$page"
        val document = app.get(url).document
        
        val home = document.select("div.thumbnail.group").mapNotNull { 
            toSearchResult(it) 
        }

        return newHomePageResponse(request.name, home)
    }

    // 3. Fitur Pencarian
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/id/search/$query"
        val document = app.get(url).document

        return document.select("div.thumbnail.group").mapNotNull { 
            toSearchResult(it) 
        }
    }

    // Fungsi Pembantu: Mengubah HTML Element menjadi format Cloudstream
    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val title = element.selectFirst("div.truncate a")?.text() ?: return null
        val posterUrl = element.selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // 4. Halaman Detail Video
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Tanpa Judul"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        val year = document.selectFirst("div.text-secondary time")?.text()?.split("-")?.firstOrNull()?.toIntOrNull()
        
        val tags = document.select("div.text-secondary:contains(Genre:) a").map { it.text() }
        val actors = document.select("div.text-secondary:contains(Aktris:) a, div.text-secondary:contains(Aktor:) a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = actors
        }
    }

    // 5. Mengekstrak Link Video Player
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text

        // Mencari UUID menggunakan Regex
        val uuidRegex = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})\?/seek/""")
        val match = uuidRegex.find(html)

        if (match != null) {
            val uuid = match.groupValues[1]
            // Menyusun format m3u8 dari server surrit
            val m3u8Url = "https://surrit.com/$uuid/playlist.m3u8"

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = mainUrl, // Wajib agar server tidak memblokir (Error 403)
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }

        return true
    }
}
