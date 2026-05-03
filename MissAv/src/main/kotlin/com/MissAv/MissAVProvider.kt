package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

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
    
    // Mencegah pemblokiran karena memuat banyak kategori sekaligus
    override var sequentialMainPage = true
    
    // KUNCI SAKTI: Memaksa Cloudstream memakai Browser Asli untuk menembus Cloudflare
    override val usesWebView = true

    // Headers penyamaran
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    // ==========================================
    // FUNGSI PINTAR UNTUK MENGATASI HALAMAN TRANSIT / REDIRECT
    // ==========================================
    private suspend fun getDocument(url: String): Document {
        var response = app.get(url, headers = headers)
        var doc = response.document
        
        // Mengecek apakah Cloudflare melempar kita ke halaman "Redirecting to..."
        val isRedirect = doc.selectFirst("meta[http-equiv=refresh]") != null
        if (isRedirect) {
            val newUrl = doc.selectFirst("a")?.attr("href")
            if (newUrl != null) {
                // Kejar URL tujuan barunya!
                response = app.get(newUrl, headers = headers)
                doc = response.document
            }
        }
        return doc
    }

    // ==========================================
    // 2. KATEGORI HALAMAN UTAMA (MENGGUNAKAN DIRECT URL)
    // ==========================================
    override val mainPage = mainPageOf(
        "https://missav.ws/dm628/id/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "https://missav.ws/dm590/id/release" to "Keluaran Terbaru",
        "https://missav.ws/dm515/id/new" to "Recent Update",
        "https://missav.ws/dm68/id/genres/Wanita%20Menikah/Ibu%20Rumah%20Tangga" to "Ibu Rumah Tangga"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page == 1) {
                request.data
            } else {
                "${request.data}?page=$page"
            }
            
            val document = getDocument(url)
            
            val home = document.select("div.thumbnail").mapNotNull { 
                toSearchResult(it) 
            }.toMutableList()

            // Menampilkan pesan error di layar jika halaman kosong
            if (home.isEmpty()) {
                home.add(newMovieSearchResponse(
                    name = "KOSONG: Web merespons dengan judul '${document.title()}'", 
                    url = url, 
                    type = TvType.NSFW
                ) {
                    this.posterUrl = "https://via.placeholder.com/300x400.png?text=Kosong"
                })
            }

            newHomePageResponse(request.name, home)
            
        } catch (e: Exception) {
            val errorList = listOf(
                newMovieSearchResponse(
                    name = "ERROR: ${e.message}", 
                    url = mainUrl, 
                    type = TvType.NSFW
                ) {
                    this.posterUrl = "https://via.placeholder.com/300x400.png?text=Error"
                }
            )
            newHomePageResponse(request.name, errorList)
        }
    }

    // ==========================================
    // 3. FITUR PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/id/search/$query"
            val document = getDocument(url)

            document.select("div.thumbnail").mapNotNull { 
                toSearchResult(it) 
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.selectFirst("a")?.attr("href") ?: return null
        
        // Pengambilan judul yang lebih kuat
        val title = element.selectFirst("a.text-secondary")?.text() 
            ?: element.selectFirst("img")?.attr("alt") 
            ?: "Tanpa Judul"
            
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
        val document = getDocument(url)

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
        // Karena HTML yang mengandung UUID ada di body, kita pakai outerHtml()
        val html = getDocument(data).outerHtml()

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
