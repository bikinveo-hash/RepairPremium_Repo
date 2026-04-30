package com.Pornhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PornhubProvider : MainAPI() {
    // Info Dasar Provider
    override var name = "Pornhub"
    override var mainUrl = "https://www.pornhub.com" // Pastikan URL sesuai
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    // 1. Fungsi untuk Halaman Utama (Main Page)
    override val mainPage = mainPageOf(
        "$mainUrl/video?o=mr" to "Recently Added",
        "$mainUrl/video?o=ht" to "Hot",
        "$mainUrl/video?o=mv" to "Most Viewed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + "&page=$page").document
        // Berdasarkan hasil-kode.html: video item ada di dalam li dengan class 'videoblock'
        val home = doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // 2. Fungsi Pencarian (Search)
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/video/search?search=$query"
        val doc = app.get(url).document
        return doc.select("li.videoblock").mapNotNull {
            it.toSearchResult()
        }
    }

    // Helper untuk mapping element HTML ke objek SearchResponse
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title a")?.text() ?: return null
        val href = mainUrl + this.selectFirst(".title a")?.attr("href")
        val posterUrl = this.selectFirst("img")?.attr("data-mediumthumb") ?: this.selectFirst("img")?.attr("src")
        
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            // Mengambil durasi jika ada
            addDuration(this@toSearchResult.selectFirst(".duration")?.text())
        }
    }

    // 3. Fungsi Memuat Detail (Load)
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.title")?.text() ?: ""
        val poster = doc.selectFirst("link[property=og:image]")?.attr("content")
        
        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = doc.selectFirst(".video-description")?.text()
            this.tags = doc.select(".categoriesWrapper a").map { it.text() }
            // Rekomendasi video di bawah
            this.recommendations = doc.select("li.videoblock").mapNotNull { it.toSearchResult() }
        }
    }

    // 4. Fungsi Mengambil Link Video (LoadLinks)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Catatan: Situs besar seperti ini biasanya menyembunyikan link di dalam script JSON/Base64
        // Kita mencari pola 'flashvars' atau link .m3u8 di dalam tag script
        val script = doc.select("script").find { it.data().contains("mediaDefinitions") }?.data()
        
        if (script != null) {
            // Mencari link m3u8 menggunakan regex sederhana
            val m3u8Regex = Regex("""(https?://.*?\.m3u8.*?)"""")
            m3u8Regex.findAll(script).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/")
                callback(
                    ExtractorLink(
                        this.name,
                        "Pornhub Player",
                        link,
                        data,
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        }
        return true
    }
}
