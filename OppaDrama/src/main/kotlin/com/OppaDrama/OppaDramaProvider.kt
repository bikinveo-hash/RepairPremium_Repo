package com.OppaDrama

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class OppaDramaProvider : MainAPI() {
    override var name = "OPPADRAMA"
    override var mainUrl = "http://45.11.57.192"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    // SOLUSI LAG: Memaksa Cloudstream memuat kategori secara berurutan, bukan bersamaan[span_2](start_span)[span_2](end_span)
    override var sequentialMainPage = true[span_3](start_span)[span_3](end_span)
    override var sequentialMainPageDelay = 1000L // Jeda 1 detik antar request untuk menghindari rate-limit[span_4](start_span)[span_4](end_span)

    // Menggunakan query identifier internal agar langsung membaca struktur dari halaman utama
    override val mainPage = mainPageOf(
        Pair("latest", "Update Episode Terbaru"),
        Pair("movies", "Film Pilihan"),
        Pair("ongoing", "Sedang Tayang (Ongoing)")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Jika halaman > 1, arahkan ke penanganan arsip WordPress standar
        val targetUrl = if (page > 1) {
            when (request.data) {
                "latest" -> "$mainUrl/series/page/$page/?status=&type=&order=update"
                "movies" -> "$mainUrl/series/page/$page/?type=Movie&order=update"
                "ongoing" -> "$mainUrl/series/page/$page/?status=Ongoing&type=&order=update"
                else -> "$mainUrl/page/$page/"
            }
        } else {
            "$mainUrl/"
        }

        val html = app.get(targetUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        // Pilah ekstraksi berdasarkan kategori beranda menggunakan data selector yang terverifikasi
        when (request.data) {
            "latest" -> {
                // Berdasarkan struktur: .listupd.normal -> .excstf -> article.bs
                document.select(".listupd.normal article.bs").forEach { element: Element ->
                    val anchor = element.select("div.bsx a").first()
                    val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                    val link = anchor?.attr("href")
                    val poster = element.select("img").first()?.attr("src")
                    
                    if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                        items.add(newMovieSearchResponse(title, link, TvType.AsianDrama) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
            "movies" -> {
                // Berdasarkan struktur: .listupd.flex -> .excstf -> article.stylefor
                document.select(".listupd.flex article.stylefor").forEach { element: Element ->
                    val anchor = element.select("div.bsx a").first()
                    val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                    val link = anchor?.attr("href")
                    val poster = element.select("img").first()?.attr("src")

                    if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                        items.add(newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
            "ongoing" -> {
                // Berdasarkan struktur sidebar: .ongoingseries -> ul -> li
                document.select(".ongoingseries li").forEach { element: Element ->
                    val anchor = element.select("a").first()
                    val link = anchor?.attr("href")
                    // Mengambil nama drama bersih dari elemen span kelas kiri (.l)
                    val title = anchor?.select("span.l")?.first()?.text()?.trim() 
                    
                    if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                        items.add(newMovieSearchResponse(title, link, TvType.AsianDrama) {
                            // Sidebar bawaan Dramastream tidak menyediakan gambar poster mikro, 
                            // Kosongkan agar Cloudstream menggunakan mode card text default atau generator lokal
                            this.posterUrl = null
                        })
                    }
                }
            }
            else -> {
                // Fallback jika memuat halaman arsip (page > 1)
                document.select("article.bs").forEach { element: Element ->
                    val anchor = element.select("div.bsx a").first()
                    val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                    val link = anchor?.attr("href")
                    val poster = element.select("img").first()?.attr("src")

                    if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                        items.add(newMovieSearchResponse(title, link, TvType.AsianDrama) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val html = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        document.select("article.bs").forEach { element: Element ->
            val anchor = element.select("div.bsx a").first()
            val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
            val link = anchor?.attr("href")
            val poster = element.select("img").first()?.attr("src")
            val typeStr = element.select(".typez").first()?.text()

            if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                val currentType = if (typeStr?.contains("Movie", ignoreCase = true) == true) TvType.Movie else TvType.AsianDrama
                items.add(newMovieSearchResponse(title, link, currentType) {
                    this.posterUrl = poster
                })
            }
        }

        return items
    }
}
