package com.FreeReels

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FreeReels : MainAPI() {
    // Kita gunakan Frontend Web-nya langsung! Jauh lebih aman dari blokir.
    override var mainUrl = "https://m.mydramawave.com"
    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    // Kategori Web (Disesuaikan dengan rute URL webnya nanti, sementara pakai ini)
    override val mainPage = mainPageOf(
        "/list?type=popular" to "Populer",
        "/list?type=new" to "Terbaru",
        "/list?type=coming_soon" to "Segera Hadir"
    )

    // ==========================================
    // 1. MENGAMBIL HALAMAN UTAMA (HTML SCRAPING)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}&page=$page"
        }
        
        val doc = app.get(url).document
        
        val items = doc.select(".list-item, .card, .drama-item").mapNotNull { element ->
            val title = element.select(".title, h3").text()
            val href = element.select("a").attr("href")
            val image = element.select("img").attr("src").let {
                if (it.startsWith("//")) "https:$it" else it
            }
            
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            
            val fixUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            
            newTvSeriesSearchResponse(title, fixUrl) {
                this.posterUrl = image
            }
        }
        
        return newHomePageResponse(request.name, items)
    }

    // ==========================================
    // 2. MENCARI FILM (HTML SCRAPING)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        val doc = app.get(url).document
        
        return doc.select(".list-item, .card, .drama-item").mapNotNull { element ->
            val title = element.select(".title, h3").text()
            val href = element.select("a").attr("href")
            val image = element.select("img").attr("src")
            
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            
            val fixUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            
            newTvSeriesSearchResponse(title, fixUrl) {
                this.posterUrl = image
            }
        }
    }

    // ==========================================
    // 3. MEMUAT DETAIL & DAFTAR EPISODE
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.select("h1, .drama-title").text().trim()
        val poster = doc.select(".poster img, .cover img").attr("src")
        val plot = doc.select(".description, .synopsis").text().trim()
        
        // Mengambil daftar episode dari web
        val episodes = doc.select(".episode-list a, .ep-item a").mapNotNull { ep ->
            val epName = ep.text().trim()
            val epHref = ep.attr("href")
            if (epHref.isBlank()) return@mapNotNull null
            
            val fixEpUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
            
            // Ekstrak nomor episodenya
            val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
            
            // PERBAIKAN 1: Menggunakan fungsi newEpisode yang diperbolehkan
            newEpisode(fixEpUrl) {
                this.name = epName
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ==========================================
    // 4. MEMUAT LINK VIDEO (SCRAPING DARI PLAYER)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val html = doc.html()
        
        val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
        val mp4Regex = Regex("""(https?://[^"']+\.mp4[^"']*)""")
        
        val m3u8Links = m3u8Regex.findAll(html).map { it.value }.toSet()
        val mp4Links = mp4Regex.findAll(html).map { it.value }.toSet()
        
        m3u8Links.forEach { link ->
            val fixLink = link.replace("\\/", "/")
            // PERBAIKAN 2: Menggunakan fungsi newExtractorLink
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - M3U8",
                    url = fixLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        
        mp4Links.forEach { link ->
            val fixLink = link.replace("\\/", "/")
            // PERBAIKAN 3: Menggunakan fungsi newExtractorLink
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - MP4",
                    url = fixLink,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        
        val subRegex = Regex("""(https?://[^"']+\.(vtt|srt)[^"']*)""")
        subRegex.findAll(html).forEach { match ->
            val subUrl = match.value.replace("\\/", "/")
            // PERBAIKAN 4: Menggunakan newSubtitleFile agar aman
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = "id",
                    url = subUrl
                )
            )
        }
        
        return true
    }
}
