package com.Rebahin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromString
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
        val posterUrl = fixUrlNull(this.selectFirst("img.mli-thumb")?.attr("src"))
        
        val qualityStr = this.selectFirst("span.mli-quality")?.text()
        val quality = getQualityFromString(qualityStr)
        
        val isTvSeries = href.contains("/series/") || href.contains("season")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Mengambil Title dengan presisi dari tag h3
        val titleElement = document.selectFirst("div.mvic-desc h3")
        val title = titleElement?.ownText()?.trim() ?: return null
        
        // Mengambil Cover (Backdrop) dan Poster
        val background = fixUrlNull(document.selectFirst("a.mvi-cover")?.attr("style")?.substringAfter("url(")?.substringBefore(")"))
        val poster = fixUrlNull(document.selectFirst("div.mvic-thumb")?.attr("style")?.substringAfter("url(")?.substringBefore(")")) ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        // Mengambil Sinopsis dan membersihkan teks sampah (seo iklan)
        val plot = document.selectFirst("div.sinopsis-indo")?.text()?.replace("Nonton Film.*Sub Indo \\| REBAHIN".toRegex(), "")?.trim()
        
        // Sistem Score terbaru Cloudstream (from10)
        val ratingText = document.selectFirst("span.irank-voters, span.rating")?.text()?.replace(",", ".")?.trim()
        val score = ratingText?.toFloatOrNull()?.let { Score.from10(it) }

        // Ekstraksi info tambahan
        var year: Int? = null
        var duration: Int? = null
        val actors = mutableListOf<String>()

        document.select("div.mvic-info p").forEach { p ->
            val text = p.text()
            when {
                text.contains("Release Date:") -> {
                    year = p.selectFirst("meta[itemprop=datePublished]")?.attr("content")?.substringBefore("-")?.toIntOrNull()
                }
                text.contains("Duration:") -> {
                    duration = text.replace(Regex("[^0-9]"), "").toIntOrNull()
                }
                text.contains("Actors:") -> {
                    p.select("a[rel=tag]").forEach { actors.add(it.text()) }
                }
            }
        }

        // Cek apakah ini series (terdapat tombol episode atau url mengandung /series/)
        val episodeButtons = document.select("a.btn-eps")
        val isTvSeries = episodeButtons.isNotEmpty() || url.contains("/series/")

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            episodeButtons.forEach {
                val epUrl = fixUrlNull(it.attr("href")) ?: return@forEach
                val epName = it.text()
                
                episodes.add(newEpisode(epUrl) {
                    this.name = epName
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.rating = score
                this.year = year
                this.duration = duration
                // this.actors = actors // (Opsional)
            }
        } else {
            // Karena ini Movie, link extractor aslinya berada di url dengan tambahan "/play"
            val playUrl = if (url.endsWith("/")) "${url}play" else "$url/play"

            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.rating = score
                this.year = year
                this.duration = duration
                // this.actors = actors // (Opsional)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Melakukan request ke halaman `/play`
        val document = app.get(data).document
        
        // Membongkar atribut `data-iframe` yang di-encode menggunakan Base64
        document.select("div.server").forEach { server ->
            val encodedUrl = server.attr("data-iframe")
            if (encodedUrl.isNotBlank()) {
                // Proses decode Base64 URL 
                val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                
                if (decodedUrl.startsWith("http")) {
                    loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }
        
        // Jalur Cadangan (Fallback): Jika provider lupa membungkus iframe di dalam base64
        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            // Hindari link YouTube bawaan Rebahin yang dipakai untuk iframe trailer palsu
            if (!src.contains("youtube.com") && src.isNotBlank()) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
