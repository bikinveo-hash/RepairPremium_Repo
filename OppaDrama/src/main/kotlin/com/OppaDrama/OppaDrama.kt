package com.OppaDrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class OppaDrama : MainAPI() {
    override var name = "OPPADRAMA"
    override var mainUrl = "http://45.11.57.192"
    override var lang = "id"
    
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        mainPage("", "Episode Terbaru")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
        val document = app.get(url).document
        val homePages = mutableListOf<HomePageList>()

        // 1. Ambil baris "Update Episode Terbaru"
        val latestElements = document.select("div.listupd.normal article.bs")
        val latestList = latestElements.mapNotNull { element ->
            val title = element.selectFirst("h2")?.text() ?: element.selectFirst(".tt")?.text() ?: ""
            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            
            // FIX: Ambil dari data-src dahulu (lazy load), jika kosong baru ambil src biasa
            val imgElement = element.selectFirst("img")
            val rawPoster = imgElement?.attr("data-src").takeIf { !it.isNullOrBlank() }
                ?: imgElement?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
                ?: imgElement?.attr("src") 
                ?: ""
            
            // FIX: Paksa merubah http:// menjadi https:// agar diizinkan oleh Android
            val poster = rawPoster.replace("http://", "https://")

            if (typeStr(element).contains("Movie", ignoreCase = true)) {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    addPoster(poster) // Menggunakan fungsi bawaan standard Cloudstream
                }
            } else {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    addPoster(poster)
                }
            }
        }
        
        if (latestList.isNotEmpty()) {
            homePages.add(HomePageList("Update Episode Terbaru", latestList, isHorizontalImages = false))
        }

        // 2. Ambil baris "Film Pilihan" (Hanya di halaman 1)
        if (page == 1) {
            val featuredElements = document.select("div.listupd.flex article.stylefor")
            val featuredList = featuredElements.mapNotNull { element ->
                val title = element.selectFirst("h2")?.text() ?: ""
                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                
                // FIX: Penanganan lazy load dan HTTPS untuk baris Film Pilihan
                val imgElement = element.selectFirst("img")
                val rawPoster = imgElement?.attr("data-src").takeIf { !it.isNullOrBlank() }
                    ?: imgElement?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
                    ?: imgElement?.attr("src") 
                    ?: ""
                val poster = rawPoster.replace("http://", "https://")
                
                newMovieSearchResponse(title, link, TvType.Movie) {
                    addPoster(poster)
                }
            }
            
            if (featuredList.isNotEmpty()) {
                homePages.add(HomePageList("Film Pilihan", featuredList, isHorizontalImages = false))
            }
        }

        val hasNext = document.selectFirst(".hpage a.r") != null

        return newHomePageResponse(homePages, hasNext)
    }

    // Helper sederhana untuk mempersingkat pengecekan tipe teks element
    private fun typeStr(element: org.jsoup.nodes.Element): String {
        return element.selectFirst(".typez")?.text() ?: ""
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
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
