package com.sflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Sflix : MainAPI() {
    override var mainUrl = "https://sflix.ps"
    override var name = "SFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home"
    )

    // Header khusus buat ngelewatin pelindung Bot SFlix
    private val ajaxHeaders = mapOf(
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // Penangkap JSON Iframe
    data class SourceResponse(
        @JsonProperty("type") val type: String?,
        @JsonProperty("link") val link: String?
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.selectFirst("a.film-poster-ahref")?.attr("href")) ?: return null
        val title = this.selectFirst(".film-name a")?.text() ?: return null
        
        val posterUrl = fixUrlNull(this.selectFirst("img.film-poster-img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        
        val isTvSeries = href.contains("/tv/")
        var quality = ""
        var year: Int? = null
        
        this.select(".fd-infor .fdi-item").forEach { item ->
            val text = item.text()
            if (item.select("strong").isNotEmpty()) {
                quality = text
            } else if (text.matches(Regex("\\d{4}"))) {
                year = text.toIntOrNull()
            }
        }

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val homeList = arrayListOf<HomePageList>()

        document.select("section.block_area_home").forEach { block ->
            val sectionTitle = block.selectFirst("h2.cat-heading")?.text() ?: "Movies"
            val items = block.select(".flw-item").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homeList.add(HomePageList(sectionTitle, items))
            }
        }
        return newHomePageResponse(homeList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}"
        val document = app.get(url).document
        return document.select(".flw-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h2.heading-name a")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("img.film-poster-img")?.attr("src"))
        val bgStyle = document.selectFirst("div.cover_follow")?.attr("style")
        val background = fixUrlNull(bgStyle?.substringAfter("url(")?.substringBefore(")"))
        val description = document.selectFirst("div.description")?.text()?.replace("Overview:", "")?.trim()
        val year = document.select("div.row-line:contains(Released)").text().substringAfter("Released:").trim().take(4).toIntOrNull()
        val duration = document.selectFirst("span.duration")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        val tags = document.select("div.row-line:contains(Genre) a").map { it.text() }
        
        // 💥 FIX ERROR AKTOR: Aturan baru CS3 wajib pakai ActorData
        val actors = document.select("div.row-line:contains(Casts) a").map { 
            ActorData(Actor(it.text())) 
        }
        
        val recommendations = document.select(".film_list-wrap .flw-item").mapNotNull { it.toSearchResult() }

        val watchDiv = document.selectFirst(".detail_page-watch")
        val dataId = watchDiv?.attr("data-id") ?: return null
        val isMovie = watchDiv.attr("data-type") == "1"

        if (isMovie) {
            // 💥 FIX ERROR EPISODE: Aturan baru CS3 wajib pakai newEpisode
            val episodeList = listOf(
                newEpisode("$mainUrl/ajax/episode/list/$dataId") {
                    this.name = "Movie"
                }
            )
            return newMovieLoadResponse(title, url, TvType.Movie, episodeList) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.tags = tags
                this.actors = actors
                this.duration = duration
                this.recommendations = recommendations
            }
        } else {
            val episodeList = arrayListOf<Episode>()
            val seasonsResponse = app.get("$mainUrl/ajax/season/list/$dataId", headers = ajaxHeaders).document
            
            seasonsResponse.select("a.dropdown-item").forEach { season ->
                val seasonId = season.attr("data-id")
                val seasonNum = season.text().replace(Regex("[^0-9]"), "").toIntOrNull()
                
                val seasonEpisodes = app.get("$mainUrl/ajax/season/episodes/$seasonId", headers = ajaxHeaders).document
                seasonEpisodes.select("a.nav-link").forEach { ep ->
                    val epId = ep.attr("data-id")
                    val epNum = ep.selectFirst("strong")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    val epTitle = ep.attr("title").ifEmpty { ep.text() }
                    
                    // 💥 FIX ERROR EPISODE: Aturan baru CS3 wajib pakai newEpisode
                    episodeList.add(
                        newEpisode("$mainUrl/ajax/episode/servers/$epId") {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = description
                this.tags = tags
                this.actors = actors
                this.duration = duration
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serversResponse = app.get(data, headers = ajaxHeaders).document
        
        serversResponse.select("a.nav-link").forEach { serverNode ->
            val serverId = serverNode.attr("data-id")
            if (serverId.isNotBlank()) {
                val sourceUrl = "$mainUrl/ajax/episode/sources/$serverId"
                val sourceJson = app.get(sourceUrl, headers = ajaxHeaders).parsedSafe<SourceResponse>()
                
                val iframeUrl = sourceJson?.link
                if (!iframeUrl.isNullOrBlank()) {
                    loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
