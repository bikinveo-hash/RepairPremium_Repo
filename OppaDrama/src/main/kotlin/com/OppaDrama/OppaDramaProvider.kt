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

    override val mainPage = mainPageOf(
        Pair("$mainUrl/series/?status=&type=&order=update", "Update Episode Terbaru"),
        Pair("$mainUrl/series/?type=Movie&order=update", "Film Pilihan"),
        Pair("$mainUrl/series/?status=Ongoing&type=&order=update", "Drama Ongoing"),
        Pair("$mainUrl/series/?status=Completed&type=Drama&order=update", "Drama Completed")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val targetUrl = if (page > 1) {
            if (request.data.contains("?")) {
                val parts = request.data.split("?")
                "${parts[0]}page/$page/?${parts[1]}"
            } else {
                "${request.data}page/$page/"
            }
        } else {
            request.data
        }

        val html = app.get(targetUrl, headers = mapOf("User-Agent" to USER_AGENT)).text
        val document = Jsoup.parse(html)
        val items = mutableListOf<SearchResponse>()

        // Element ditambahkan secara eksplisit agar compiler tidak gagal inferensi tipe data
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

        document.select("article.stylefor").forEach { element: Element ->
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
