package com.Rebahin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.autos"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/series/page/" to "TV Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.ml-mask") ?: return null
        val title = a.attr("title")
        val href = fixUrlNull(a.attr("href")) ?: return null
        
        val img = this.selectFirst("img.mli-thumb")
        val posterUrl = fixUrlNull(img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
        
        // Perbaikan: Mapping SearchQuality manual tanpa perlu fungsi getQualityFromString yang hilang
        val qualityStr = this.selectFirst("span.mli-quality")?.text()?.lowercase()
        val qualityResult = when {
            qualityStr == null -> null
            qualityStr.contains("fhd") || qualityStr.contains("hd") -> SearchQuality.HD
            qualityStr.contains("blu") -> SearchQuality.BlueRay
            qualityStr.contains("cam") -> SearchQuality.Cam
            qualityStr.contains("sd") -> SearchQuality.SD
            else -> null
        }
        
        val isTvSeries = href.contains("/series/") || href.contains("season")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = qualityResult
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = qualityResult
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleElement = document.selectFirst("div.mvic-desc h3")
        val title = titleElement?.ownText()?.trim() ?: return null
        
        val background = fixUrlNull(document.selectFirst("a.mvi-cover")?.attr("style")?.substringAfter("url(")?.substringBefore(")"))
        val poster = fixUrlNull(document.selectFirst("div.mvic-thumb")?.attr("style")?.substringAfter("url(")?.substringBefore(")")) ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val plot = document.selectFirst("div.sinopsis-indo, div.rt-Text")?.text()?.replace("Nonton Film.*Sub Indo \\| REBAHIN".toRegex(), "")?.trim()
        
        val ratingText = document.selectFirst("span.irank-voters, span.rating, div.btn-danger.averagerate")?.text()?.replace(",", ".")?.trim()
        val parsedScore = ratingText?.toFloatOrNull()?.let { Score.from10(it) }

        var year: Int? = null
        var duration: Int? = null

        document.select("div.mvic-info p").forEach { p ->
            val text = p.text()
            when {
                text.contains("Release Date:") -> {
                    year = p.selectFirst("meta[itemprop=datePublished]")?.attr("content")?.substringBefore("-")?.toIntOrNull()
                }
                text.contains("Duration:") -> {
                    duration = text.replace(Regex("[^0-9]"), "").toIntOrNull()
                }
            }
        }

        val isTvSeries = url.contains("/series/")

        return if (isTvSeries) {
            val watchUrl = if (url.endsWith("/")) "${url}watch" else "$url/watch"
            val watchDocument = app.get(watchUrl).document

            val episodes = mutableListOf<Episode>()
            val episodeButtons = watchDocument.select("div#list-eps a.btn-eps")
            
            episodeButtons.forEach { epElem ->
                val epName = epElem.text().trim()
                val base64Iframe = epElem.attr("data-iframe")
                
                if (base64Iframe.isNotBlank()) {
                    episodes.add(newEpisode(base64Iframe) {
                        this.name = epName
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                // Perbaikan: assignment menggunakan 'score', bukan 'rating'
                this.score = parsedScore 
                this.year = year
                this.duration = duration
            }
        } else {
            val playUrl = if (url.endsWith("/")) "${url}play" else "$url/play"

            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                // Perbaikan: assignment menggunakan 'score', bukan 'rating'
                this.score = parsedScore 
                this.year = year
                this.duration = duration
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        if (!data.startsWith("http")) {
            try {
                val decodedUrl = String(Base64.decode(data, Base64.DEFAULT))
                if (decodedUrl.startsWith("http")) {
                     loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
            }
        } else {
            val document = app.get(data).document
            
            document.select("div.server").forEach { server ->
                val encodedUrl = server.attr("data-iframe")
                if (encodedUrl.isNotBlank()) {
                    val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                    if (decodedUrl.startsWith("http")) {
                        loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
                    }
                }
            }
            
            document.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
                if (!src.contains("http://googleusercontent.com/youtube.com") && src.isNotBlank()) {
                    loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
