package com.OppaDrama

import com.fleeksoft.ksoup.Ksoup
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class OppaDrama : MainAPI() {
    override var name = "OPPADRAMA"
    override var mainUrl = "http://45.11.57.192" // Menggunakan IP/domain aktif dari HTML
    override var lang = "id"
    
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val hasMainPage = true

    // Mendeklarasikan menu beranda utama di aplikasi
    override val mainPage = mainPageOf(
        mainPage("", "Episode Terbaru")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Menyusun URL berdasarkan halaman aktif (Paginasi)
        val url = if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
        
        // Melakukan HTTP GET request menggunakan client internal 'app'
        val html = app.get(url).text
        val document = Ksoup.parse(html)
        
        val homePages = mutableListOf<HomePageList>()

        // 1. Ambil baris "Update Episode Terbaru" (Ada di semua halaman)
        val latestElements = document.select("div.listupd.normal article.bs")
        val latestList = latestElements.mapNotNull { element ->
            val title = element.selectFirst("h2")?.text() ?: element.selectFirst(".tt")?.text() ?: ""
            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            val typeStr = element.selectFirst(".typez")?.text() ?: ""

            // Mengelompokkan tipe video secara akurat
            if (typeStr.contains("Movie", ignoreCase = true)) {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
        
        if (latestList.isNotEmpty()) {
            homePages.add(HomePageList("Update Episode Terbaru", latestList, isHorizontalImages = false))
        }

        // 2. Ambil baris "Film Pilihan" (Biasanya hanya muncul di halaman 1 beranda)
        if (page == 1) {
            val featuredElements = document.select("div.listupd.flex article.stylefor")
            val featuredList = featuredElements.mapNotNull { element ->
                val title = element.selectFirst("h2")?.text() ?: ""
                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val poster = element.selectFirst("img")?.attr("src") ?: ""
                
                // Baris ini khusus untuk Film/Movie
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            
            if (featuredList.isNotEmpty()) {
                homePages.add(HomePageList("Film Pilihan", featuredList, isHorizontalImages = false))
            }
        }

        // Memeriksa apakah ada halaman berikutnya (tombol "Selanjutnya")
        val hasNext = document.selectFirst(".hpage a.r") != null

        // Mengembalikan kumpulan beranda dengan builder ter-anyar
        return newHomePageResponse(homePages, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Langkah selanjutnya
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        // Langkah selanjutnya
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
