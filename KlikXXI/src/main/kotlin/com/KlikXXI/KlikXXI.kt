package com.KlikXXI

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KlikXXI : MainAPI() {
    override var mainUrl = "https://klikxxi.me"
    override var name = "KlikXXI"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // PERBAIKAN 1: Gunakan URL dasar, jangan pasang /page/ di sini
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/tv/" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // PERBAIKAN 2: Logika paginasi WordPress yang benar
        val url = if (page == 1) {
            request.data // Halaman 1 -> https://klikxxi.me/ atau https://klikxxi.me/tv/
        } else {
            "${request.data}page/$page/" // Halaman 2 -> https://klikxxi.me/page/2/
        }

        val document = app.get(url).document
        val home = document.select("div.gmr-item-modulepost").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    // (Fungsi load dibiarkan sama seperti milikmu sebelumnya...)

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: return null
        
        // Mengambil gambar dan membersihkan resolusi (-152x228) agar dapat gambar HD
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        
        val isTvSeries = this.selectFirst(".gmr-numbeps") != null || href.contains("/tv/")
        
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                // PERBAIKAN 3: Gunakan fixUrlNull untuk menambah https:// jika hilang
                this.posterUrl = fixUrlNull(posterUrl)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                // PERBAIKAN 3: Gunakan fixUrlNull
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }
}


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ambil ID untuk request AJAX
        val ajaxId = if (data.startsWith("http")) {
            app.get(data).document.selectFirst("#muvipro_player_content_id")?.attr("data-id") ?: return false
        } else data

        // Request ke AJAX endpoint
        val ajaxResponse = app.post(
            url = "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "muvipro_player_content", "post_id" to ajaxId)
        ).text
        
        // Di sini lu perlu parse iframe dari ajaxResponse, 
        // lalu panggil loadExtractor untuk setiap link yang ketemu.
        // Contoh: callback.invoke(newExtractorLink(...))
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        
        val isTvSeries = this.selectFirst(".gmr-numbeps") != null || href.contains("/tv/")
        return if (isTvSeries) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }
}
