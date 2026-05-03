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
        // Asumsi format halaman web adalah ?page=1, ?page=2 dst
        val url = "$mainUrl/id/${request.data}?page=$page"
        val document = app.get(url).document
        
        // Ekstrak semua video yang ada di halaman utama
        val home = document.select("div.thumbnail.group").mapNotNull { 
            toSearchResult(it) 
        }

        return newHomePageResponse(request.name, home)
    }

    // ==========================================
    // 3. FITUR PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        // Format URL pencarian sesuai analisa HTML sebelumnya
        val url = "$mainUrl/id/search/$query"
        val document = app.get(url).document

        // Struktur pencarian sama dengan halaman utama
        return document.select("div.thumbnail.group").mapNotNull { 
            toSearchResult(it) 
        }
    }

    // Fungsi pembantu untuk mengubah elemen HTML "thumbnail" menjadi SearchResponse
    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val title = element.selectFirst("div.truncate a")?.text() ?: return null
        val posterUrl = element.selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==========================================
    // 4. HALAMAN DETAIL (LOAD)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Mengambil metadata dasar menggunakan Meta Tags OpenGraph
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: "Tanpa Judul"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        // Mengambil tahun rilis dari div text-secondary
        val year = document.selectFirst("div.text-secondary time")?.text()?.split("-")?.firstOrNull()?.toIntOrNull()
        
        // Mengambil genre dan aktor berdasarkan isi teksnya
        val tags = document.select("div.text-secondary:contains(Genre:) a").map { it.text() }
        val actors = document.select("div.text-secondary:contains(Aktris:) a, div.text-secondary:contains(Aktor:) a").map { it.text() }

        // Parameter 'data' kita isi url untuk dipakai lagi di loadLinks
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.actors = actors
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
        // Ambil source HTML mentah dari halaman detail
        val html = app.get(data).text

        // Regex untuk mendeteksi UUID video (contoh: a5b13b9d-fb3b-42e0-b9a4-c53333f4f827)
        // UUID ini tersembunyi di path gambar thumbnail seperti \/uuid\/seek\/
        val uuidRegex = Regex("""([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})[\\/]+seek""")
        val match = uuidRegex.find(html)

        if (match != null) {
            val uuid = match.groupValues[1]
            
            // Format URL m3u8 dari server video mereka (Surrit)
            val m3u8Url = "https://surrit.com/$uuid/playlist.m3u8"

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    referer = mainUrl, // Wajib ada agar server mengizinkan akses stream
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }

        return true
    }
}
