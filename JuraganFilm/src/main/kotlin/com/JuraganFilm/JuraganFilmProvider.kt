package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class JuraganFilmProvider : MainAPI() {
    override var name = "JuraganFilm"
    override var mainUrl = "https://tv44.juragan.film"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    // Halaman utama akan di-scrape, jadi kita gunakan satu halaman "Home"
    override val mainPage = listOf(
        mainPage("$mainUrl/", "Home")
    )

    // ========== HOMEPAGE ==========
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Halaman utama tidak ada pagination widget, jadi abaikan page > 1
        if (page > 1) return null

        val doc = app.get(request.data).document
        val homeLists = mutableListOf<HomePageList>()

        // Cari semua section widget
        val sections = doc.select(".home-widget, .gmr-module-posts")
        for (section in sections) {
            // Ambil judul section
            val sectionTitle = section.selectFirst(".module-title, .widget-title, h3")?.text()
                ?: section.selectFirst("h2")?.text() ?: "JuraganFilm"
            
            // Ambil item-item film
            val items = section.select(".gmr-item-modulepost, article.item")
            val searchResponses = items.mapNotNull { parseSearchItem(it) }
            
            if (searchResponses.isNotEmpty()) {
                homeLists.add(HomePageList(sectionTitle, searchResponses))
            }
        }

        return if (homeLists.isEmpty()) null
        else newHomePageResponse(homeLists, hasNext = false)
    }

    // ========== SEARCH ==========
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchUrl = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv&page=$page"
        val doc = app.get(searchUrl).document
        
        val items = doc.select(".gmr-item-modulepost, article.item")
        val results = items.mapNotNull { parseSearchItem(it) }
        
        val hasNext = doc.selectFirst("ul.page-numbers li a.next") != null
        return newSearchResponseList(results, hasNext)
    }

    // ========== LOAD (Detail Film/Series) ==========
    // STUB: Nanti diisi setelah ada sample halaman detail
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        // TODO: Parse detail page
        // Untuk sementara return null dulu
        return null
    }

    // ========== LOAD LINKS ==========
    // STUB: Nanti diisi setelah ada sample halaman download/embed
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: Parse halaman download, ambil embed links, panggil loadExtractor
        return false
    }

    // ========== HELPER: Parse item dari list ==========
    private fun parseSearchItem(element: Element): SearchResponse? {
        // Title & URL
        val titleEl = element.selectFirst(".entry-title a") ?: return null
        val title = titleEl.text().trim()
        val url = titleEl.attr("href").trim()
        if (title.isEmpty() || url.isEmpty()) return null

        // Poster
        val img = element.selectFirst("img.wp-post-image")
        val posterUrl = img?.attr("data-src")?.ifBlank { img.attr("src") }

        // Kualitas (HD, Bluray, dll)
        val qualityText = element.selectFirst(".gmr-quality-item a")?.text()?.trim()

        // Episode / Subtitle info
        val episodeText = element.selectFirst(".strokeepisode")?.text()?.trim()

        // Durasi
        val durationText = element.selectFirst(".gmr-duration-item")?.text()?.trim()

        // Tanggal rilis
        val dateEl = element.selectFirst("time[itemprop=dateCreated]")
        val dateText = dateEl?.attr("datetime")?.trim()

        // Sutradara (untuk metadata tambahan)
        val director = element.selectFirst("span[itemprop=director] a")?.text()?.trim()

        // Trailer URL
        val trailerUrl = element.selectFirst("a.gmr-trailer-popup")?.attr("href")

        // Tentukan tipe: cek dari URL atau episode
        val type = if (url.contains("/tv/") || episodeText != null) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        // Buat SearchResponse sesuai tipe
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, type) {
                this.posterUrl = posterUrl
                addQuality(qualityText ?: "")
                // Simpan info episode di tempat yang bisa ditampilkan
                // Tidak ada field khusus, tapi bisa disimpan di name atau diabaikan
                // Bisa juga ditambahkan di constructor, tapi TvSeriesSearchResponse tidak punya
                // Gunakan year untuk menyimpan info episode (workaround)
                if (episodeText != null) {
                    // Simpan sebagai year karena tidak ada field episode di search response
                    // Alternatif: tambahkan ke name
                    // Kita bisa modifikasi name untuk menampilkan info episode
                }
                if (durationText != null) {
                    // Bisa ditambahkan sebagai durasi? Tidak ada field di search
                }
            }
            else -> newMovieSearchResponse(title, url, type) {
                this.posterUrl = posterUrl
                addQuality(qualityText ?: "")
                // year bisa diambil dari dateText jika ada
                year = dateText?.substringBefore("-")?.toIntOrNull()
            }
        }.also { response ->
            // Tambahkan trailer jika ada (tapi search response tidak punya trailer field)
            // Trailer hanya bisa ditambahkan di LoadResponse
        }
    }
}
