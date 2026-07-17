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
        
        // LANGSUNG AMBIL OBJEK DOCUMENT DARI 'app.get'. Gak perlu import Ksoup manual!
        val document = app.get(url).document
        
        val homePages = mutableListOf<HomePageList>()

        // 1. Ambil baris "Update Episode Terbaru"
        val latestElements = document.select("div.listupd.normal article.bs")
        val latestList = latestElements.mapNotNull { element ->
            val title = element.selectFirst("h2")?.text() ?: element.selectFirst(".tt")?.text() ?: ""
            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src") ?: ""
            val typeStr = element.selectFirst(".typez")?.text() ?: ""

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

        // 2. Ambil baris "Film Pilihan" (Hanya di halaman 1)
        if (page == 1) {
            val featuredElements = document.select("div.listupd.flex article.stylefor")
            val featuredList = featuredElements.mapNotNull { element ->
                val title = element.selectFirst("h2")?.text() ?: ""
                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val poster = element.selectFirst("img")?.attr("src") ?: ""
                
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            
            if (featuredList.isNotEmpty()) {
                homePages.add(HomePageList("Film Pilihan", featuredList, isHorizontalImages = false))
            }
        }

        val hasNext = document.selectFirst(".hpage a.r") != null

        return newHomePageResponse(homePages, hasNext)
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
