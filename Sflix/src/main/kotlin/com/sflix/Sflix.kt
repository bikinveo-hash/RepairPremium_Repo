package com.sflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Sflix : MainAPI() {
    // Berdasarkan meta tag og:url di HTML yang lu kirim, domainnya sflix.ps
    override var mainUrl = "https://sflix.ps" 
    override var name = "SFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ==========================================
    // FUNGSI BANTUAN: EKSTRAK ITEM FILM (Super Gampang!)
    // ==========================================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a.film-poster-ahref")?.attr("href") ?: return null
        val title = this.selectFirst(".film-name a")?.text() ?: return null
        
        // Ambil poster, perhatikan atribut data-src untuk lazyload
        val posterUrl = this.selectFirst("img.film-poster-img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        }

        // Deteksi TV Series atau Movie dari URL
        val isTvSeries = href.contains("/tv/")

        // Ambil kualitas dan tahun dari class fdi-item
        var quality = ""
        var year: Int? = null
        this.select(".fd-infor .fdi-item").forEach { item ->
            val text = item.text()
            if (item.select("strong").isNotEmpty()) {
                quality = text // Contoh: FHD, HD, CAM
            } else if (text.matches(Regex("\\d{4}"))) {
                year = text.toIntOrNull() // Contoh: 2026, 2025
            }
        }

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
                addQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
                addQuality(quality)
                this.year = year
            }
        }
    }

    // ==========================================
    // HALAMAN UTAMA (HOME)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Halaman utamanya ada di /home
        val document = app.get("$mainUrl/home").document 
        val homeList = ArrayList<HomePageList>()

        // Looping setiap blok section (Trending, Latest Movies, Latest TV Shows, Coming Soon)
        document.select("section.block_area_home").forEach { block ->
            val sectionTitle = block.selectFirst("h2.cat-heading")?.text() ?: "Movies"
            
            // Karena Trending punya 2 tab (Movies & TV), kita gabungin aja
            val items = block.select(".flw-item").mapNotNull { it.toSearchResult() }
            
            if (items.isNotEmpty()) {
                homeList.add(HomePageList(sectionTitle, items))
            }
        }

        return newHomePageResponse(homeList)
    }

    // ==========================================
    // PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        // Format pencarian standar SFlix biasanya /search/keyword
        val url = "$mainUrl/search/${query.replace(" ", "-")}"
        val document = app.get(url).document

        return document.select(".flw-item").mapNotNull { it.toSearchResult() }
    }
}
