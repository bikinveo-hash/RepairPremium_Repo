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

    // Pengaturan menu halaman utama Cloudstream
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

    // Fungsi pemroses item pencarian & halaman utama
    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.ml-mask") ?: return null
        val title = a.attr("title")
        val href = fixUrlNull(a.attr("href")) ?: return null
        
        // UPDATE PENYELESAIAN LAZY LOADING POSTER
        val img = this.selectFirst("img.mli-thumb")
        val posterUrl = fixUrlNull(img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
        
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

        // Mengambil title dari struktur mvic-desc h3
        val titleElement = document.selectFirst("div.mvic-desc h3")
        val title = titleElement?.ownText()?.trim() ?: return null
        
        // Penarikan background dan poster dari CSS inline / metadata
        val background = fixUrlNull(document.selectFirst("a.mvi-cover")?.attr("style")?.substringAfter("url(")?.substringBefore(")"))
        val poster = fixUrlNull(document.selectFirst("div.mvic-thumb")?.attr("style")?.substringAfter("url(")?.substringBefore(")")) ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val plot = document.selectFirst("div.sinopsis-indo, div.rt-Text")?.text()?.replace("Nonton Film.*Sub Indo \\| REBAHIN".toRegex(), "")?.trim()
        
        // Memformat rating teks menjadi skor numerik per 10 (Format Cloudstream 3 Baru)
        val ratingText = document.selectFirst("span.irank-voters, span.rating, div.btn-danger.averagerate")?.text()?.replace(",", ".")?.trim()
        val score = ratingText?.toFloatOrNull()?.let { Score.from10(it) }

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
            // Jika TV Series, panggil halaman tontonnya untuk mendapatkan daftar episode
            val watchUrl = if (url.endsWith("/")) "${url}watch" else "$url/watch"
            val watchDocument = app.get(watchUrl).document

            val episodes = mutableListOf<Episode>()
            val episodeButtons = watchDocument.select("div#list-eps a.btn-eps")
            
            episodeButtons.forEach { epElem ->
                val epName = epElem.text().trim()
                val base64Iframe = epElem.attr("data-iframe")
                
                if (base64Iframe.isNotBlank()) {
                    // Menyimpan sandi Base64 iframe ke dalam episode
                    episodes.add(newEpisode(base64Iframe) {
                        this.name = epName
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.rating = score
                this.year = year
                this.duration = duration
            }
        } else {
            // Jika film layar lebar, giring pencarian link video ke halaman pemutarnya
            val playUrl = if (url.endsWith("/")) "${url}play" else "$url/play"

            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.rating = score
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
            // Jalur TV Series: Parameter data adalah Base64
            try {
                val decodedUrl = String(Base64.decode(data, Base64.DEFAULT))
                if (decodedUrl.startsWith("http")) {
                     loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
               // Cegah crash apabila gagal decode
            }
        } else {
            // Jalur Movie: Parameter data adalah halaman URL `/play`
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
            
            // Mekanisme pencadangan jika Rebahin luput membungkus iframe di dalam div server
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
