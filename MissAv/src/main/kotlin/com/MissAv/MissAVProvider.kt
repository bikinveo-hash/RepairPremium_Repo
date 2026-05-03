package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MissAvProvider : MainAPI() {
    override var name = "MissAv"
    override var mainUrl = "https://missav.ws"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val usesWebView = true 

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    // Helper untuk memparsing daftar video (dipakai di Home & Search)
    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("div.my-2 a") ?: return null
        val title = titleElement.text().trim()
        val url = titleElement.attr("href")

        if (title.isEmpty() || url.startsWith("javascript")) return null

        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/id", headers = headers).document
        val items = ArrayList<HomePageList>()

        document.select("div.sm\\:container:has(h2)").forEach { section ->
            val sectionTitle = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            if (sectionTitle.contains("Memuat") || sectionTitle.contains("Rekomendasi")) return@forEach

            val videos = section.select("div.thumbnail").mapNotNull { it.toSearchResult() }
            if (videos.isNotEmpty()) items.add(HomePageList(sectionTitle, videos))
        }

        return newHomePageResponse(items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Kita cari link pencarian yang valid dari halaman utama dulu supaya DM-nya bener
        val mainDoc = app.get("$mainUrl/id", headers = headers).document
        val searchBaseUrl = mainDoc.selectFirst("meta[property=og:url]")?.attr("content") ?: "$mainUrl/id"
        
        // Bersihkan URL dari path akhiran jika ada, lalu tambah /search/
        val finalSearchUrl = "${searchBaseUrl.removeSuffix("/")}/search/${query.trim()}"
        val document = app.get(finalSearchUrl, headers = headers).document

        return document.select("div.thumbnail").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = document.select("a[href*=/genres/], a[href*=/actresses/]").map { it.text().trim() }

        // Fitur Saran Video (Recommendations)
        val recommendations = document.select("div.thumbnail").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        var m3u8Url: String? = null

        [span_0](start_span)// Coba unpack skrip (Packed JS)[span_0](end_span)
        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                [span_1](start_span)val unpacked = getAndUnpack(script.data()) //[span_1](end_span)
                Regex("""https?://[^"']+\.m3u8[^"']*""").find(unpacked)?.let { m3u8Url = it.value }
            }
        }
        
        // Backup: Cari link mentah
        if (m3u8Url == null) {
            Regex("""https?://[^"']+\.m3u8[^"']*""").find(document.html())?.let { m3u8Url = it.value }
        }

        m3u8Url?.let {
            callback.invoke(
                ExtractorLink(this.name, this.name, it, data, Qualities.Unknown.value, type = ExtractorLinkType.M3U8)
            )
            return true
        }
        return false
    }
}
