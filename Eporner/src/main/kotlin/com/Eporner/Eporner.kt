package com.Eporner

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.math.BigInteger

class Eporner : MainAPI() {
    override var mainUrl              = "https://www.eporner.com"
    override var name                 = "Eporner"
    override val hasMainPage          = true
    override var lang                 = "en"
    
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    companion object {
        // KUNCI RAHASIA: Mutex untuk memaksa request berjalan satu-satu (Sistem Antrian)
        // Ini mencegah server Eporner memblokir kita karena terlalu banyak request serentak.
        private val mutex = Mutex()
    }

    override val mainPage = mainPageOf(
        "" to "Recent Videos",
        "best-videos" to "Best Videos",
        "top-rated" to "Top Rated",
        "most-viewed" to "Most Viewed",
        "cat/milf" to "Milf",
        "cat/japanese" to "Japanese",
        "cat/hd-1080p" to "1080 Porn",
        "cat/4k-porn" to "4K Porn",
        "country-top/id" to "Indonesia"
        // "recommendations" DIHAPUS karena Eporner tidak punya halaman ini (Bikin Error 404 dan merusak kategori lain!)
    )

    // ==========================================
    // 1. HALAMAN DEPAN (Home Page)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Perbaikan URL agar tidak ada double slash "//" dan rapi untuk page 1 vs page 2+
        val url = if (request.data.isEmpty()) {
            if (page == 1) "$mainUrl/" else "$mainUrl/$page/"
        } else {
            if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/$page/"
        }

        var home: List<SearchResponse> = emptyList()

        // Menggunakan Mutex agar request CloudStream tidak menabrak limit server Eporner
        mutex.withLock {
            for (i in 1..2) { // Auto-retry: Coba 2 kali jika VPN sedang lelet
                try {
                    val document = app.get(url, timeout = 15L).document
                    home = document.select("#div-search-results div.mb").mapNotNull { it.toSearchResult() }
                    if (home.isNotEmpty()) break // Jika berhasil dapat data, keluar dari loop
                } catch (e: Exception) {
                    delay(1000L) // Tunggu 1 detik sebelum mencoba lagi
                }
            }
            delay(250L) // Jeda nafas untuk server sebelum membiarkan request kategori berikutnya jalan
        }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                )
            ),
            hasNext = home.isNotEmpty()
        )
    }

    // ==========================================
    // FUNGSI BANTUAN PARSING VIDEO
    // ==========================================
    private fun Element.toSearchResult(): SearchResponse {
        val title = fixTitle(this.select("div.mbunder p.mbtit a").text().ifEmpty { "No Title" }).trim()
        val href = fixUrl(this.select("div.mbcontent a").attr("href"))
        
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-src").ifBlank { it.attr("src") } 
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==========================================
    // 2. FITUR PENCARIAN (Search)
    // ==========================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val subquery = query.replace(" ","-")
        val document = app.get("$mainUrl/search/$subquery/$page/").document
        val results = document.select("div.mb").mapNotNull { it.toSearchResult() }
        val hasNext = results.isNotEmpty()
        return newSearchResponseList(results, hasNext)
    }

    // ==========================================
    // 3. HALAMAN DETAIL & SARAN FILM (Load Info)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val recommendationsList = document.select("div#relateddiv div.mb").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendationsList
        }
    }

    // ==========================================
    // 4. PEMUTAR VIDEO (Load Links)
    // ==========================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // FIX: Menggunakan .text bukan .toString() karena toString() tidak merender isi body di CloudStream versi terbaru
        val doc = app.get(data).text
        val vid = Regex("EP\\.video\\.player\\.vid = '([^']+)'").find(doc)?.groupValues?.get(1).toString()
        val hash = Regex("EP\\.video\\.player\\.hash = '([^']+)'").find(doc)?.groupValues?.get(1).toString()
        
        val url = "https://www.eporner.com/xhr/video/$vid?hash=${base36(hash)}"
        val json = app.get(url).text
        val jsonObject = JSONObject(json)
        val sources = jsonObject.getJSONObject("sources")
        val mp4Sources = sources.getJSONObject("mp4")
        val qualities = mp4Sources.keys()
 
        while (qualities.hasNext()) {
            val quality = qualities.next() as String
            val sourceObject = mp4Sources.getJSONObject(quality)
            val src = sourceObject.getString("src")
            val labelShort = sourceObject.getString("labelShort") ?: ""
            
            // Menggunakan fungsi newExtractorLink yg aman dari Deprecated
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = src,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.eporner.com/"
                    this.quality = getIndexQuality(labelShort)
                }
            )
        }
        return true
    }

    // ==========================================
    // FUNGSI DEKRIPSI BASE36 (Bawaan Kode Aslimu)
    // ==========================================
    fun base36(hash: String): String {
        return if (hash.length >= 32) {
            val part1 = BigInteger(hash.substring(0, 8), 16).toString(36)
            val part2 = BigInteger(hash.substring(8, 16), 16).toString(36)
            val part3 = BigInteger(hash.substring(16, 24), 16).toString(36)
            val part4 = BigInteger(hash.substring(24, 32), 16).toString(36)

            part1 + part2 + part3 + part4
        } else {
            throw IllegalArgumentException("Hash length is invalid")
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
