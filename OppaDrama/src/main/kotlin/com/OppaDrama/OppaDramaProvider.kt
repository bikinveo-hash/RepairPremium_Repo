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

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 1500L

    // Injeksi Headers & Cookie mutlak hasil sniffing lalu lintas paket data browser
    private val desktopBypassHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
        "Cookie" to "user_is_human=true",
        "Upgrade-Insecure-Requests" to "1",
        "Cache-Control" to "max-age=0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        Pair("latest", "Update Episode Terbaru"),
        Pair("movies", "Film Pilihan"),
        Pair("ongoing", "Sedang Tayang (Ongoing)")
    )

    // Helper khusus untuk mengekstrak URL poster asli dari elemen gambar Jsoup
    private fun Element.extractPoster(): String? {
        val img = this.select("img").first() ?: return null
        
        // Memeriksa atribut lazy-load secara berurutan untuk menghindari placeholder kosong
        val rawUrl = when {
            img.hasAttr("data-src") -> img.attr("data-src")
            img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
            img.hasAttr("srcset") -> img.attr("srcset").substringBefore(" ")
            else -> img.attr("src")
        }
        
        if (rawUrl.isNullOrBlank()) return null
        
        // Membersihkan query parameter Jetpack (?resize=...) agar Cloudstream mendapatkan resolusi asli gambar
        return rawUrl.replace(Regex("[?&]resize=\\d+,\\d+"), "")
                     .replace(Regex("[?&]quality=\\d+"), "")
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val targetUrl = when (request.data) {
            "latest" -> if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"
            "movies" -> if (page > 1) "$mainUrl/series/page/$page/?type=Movie&order=update" else "$mainUrl/"
            "ongoing" -> if (page > 1) "$mainUrl/series/page/$page/?status=Ongoing&type=&order=update" else "$mainUrl/"
            else -> "$mainUrl/"
        }

        val html = app.get(targetUrl, headers = desktopBypassHeaders).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        when (request.data) {
            "latest" -> {
                document.select("article.bs").forEach { element: Element ->
                    val anchor = element.select("div.bsx a").first()
                    val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                    val link = anchor?.attr("href")
                    val poster = element.extractPoster() // Menggunakan extractor poster baru
                    
                    if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                        items.add(newMovieSearchResponse(title, link, TvType.AsianDrama) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
            "movies" -> {
                document.select("article.stylefor").forEach { element: Element ->
                    val anchor = element.select("div.bsx a").first()
                    val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                    val link = anchor?.attr("href")
                    val poster = element.extractPoster() // Menggunakan extractor poster baru

                    if (!link.isNullOrEmpty() && !title.isNullOrEmpty()) {
                        items.add(newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
                
                if (items.isEmpty()) {
                    document.select("article.bs").forEach { element: Element ->
                        val anchor = element.select("div.bsx a").first()
                        val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                        val link = anchor?.attr("href")
                        val poster = element.extractPoster() // Menggunakan extractor poster baru

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
                    document.select("article.bs").forEach { element: Element ->
                        val anchor = element.select("div.bsx a").first()
                        val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
                        val link = anchor?.attr("href")
                        val poster = element.extractPoster() // Menggunakan extractor poster baru

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
        val html = app.get(searchUrl, headers = desktopBypassHeaders).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        document.select("article.bs").forEach { element: Element ->
            val anchor = element.select("div.bsx a").first()
            val title = element.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
            val link = anchor?.attr("href")
            val poster = element.extractPoster() // Menggunakan extractor poster baru
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
