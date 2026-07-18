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

    // Mengamankan antrean request paralel agar tidak diblokir oleh Nginx Lokal Server
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 1000L

    // Inisialisasi Kategori Beranda riil berdasarkan susunan Dump HTML bixbox
    override val mainPage = mainPageOf(
        Pair("latest", "Update Episode Terbaru"),
        Pair("movies", "Film Pilihan"),
        Pair("ongoing", "Sedang Tayang (Ongoing)")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Pemetaan URL Target secara presisi berdasarkan deteksi halaman arsip Dramastream
        val targetUrl = when (request.data) {
            "latest" -> if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
            "movies" -> if (page > 1) "$mainUrl/series/page/$page/?type=Movie&order=update" else "$mainUrl/"
            "ongoing" -> if (page > 1) "$mainUrl/series/page/$page/?status=Ongoing&type=&order=update" else "$mainUrl/"
            else -> "$mainUrl/"
        }

        val html = app.get(targetUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        // Forensik Parsing berdasarkan data kontainer bixbox riil
        when (request.data) {
            "latest" -> {
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
                // Mengekstrak struktur .listupd.flex -> article.stylefor pada halaman utama
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
                
                // Jika berada di page > 1, tema Dramastream melebur tipe .stylefor kembali menjadi article.bs
                if (items.isEmpty()) {
                    document.select("article.bs").forEach { element: Element ->
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
            }
            "ongoing" -> {
                if (page == 1) {
                    // Ekstraksi data kolom widget sidebar ".ongoingseries" untuk halaman utama
                    document.select(".ongoingseries li").forEach { element: Element ->
                        val anchor = element.select("a").first()
                        val link = anchor?.attr("href")
                        val title = anchor?.select("span.l")?.first()?.text()?.trim()
                        
                        if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                            items.add(newMovieSearchResponse(title, link, TvType.AsianDrama) {
                                this.posterUrl = null
                            })
                        }
                    }
                } else {
                    // Ekstraksi arsip penayangan untuk page > 1
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
                val currentType = if (typeStr?.contains("Movie", ignoreCase = true) == true || link.contains("/movie-")) {
                    TvType.Movie
                } else {
                    TvType.AsianDrama
                }
                items.add(newMovieSearchResponse(title, link, currentType) {
                    this.posterUrl = poster
                })
            }
        }

        return items
    }
}
