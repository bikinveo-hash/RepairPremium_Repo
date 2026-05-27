package com.KlikXXI

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KlikXXI : MainAPI() {
    override var mainUrl = "https://klikxxi.me"
    override var name = "KlikXXI"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movie/page/" to "Latest Movies",
        "$mainUrl/tv/page/" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.gmr-item-modulepost").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1.entry-title")?.text() ?: ""
        val title = rawTitle.replace(Regex("(?i)\\s*(Season\\s*\\d+.*|[0-9]{4})$"), "").trim()
        val poster = document.selectFirst(".gmr-movie-data figure img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        val plot = document.selectFirst(".entry-content[itemprop=description] p")?.text()
        val dataId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id") ?: ""
        
        val isTvSeries = url.contains("/tv/") || url.contains("/eps/")

        return if (isTvSeries) {
            val episodes = document.select(".gmr-season-episodes a.button").mapNotNull { epNode ->
                val epUrl = epNode.attr("href")
                val epTitle = epNode.text()
                if (epTitle.contains("Batch", true)) return@mapNotNull null
       
                val match = Regex("S(\\d+)Eps(\\d+)").find(epTitle)
                newEpisode(epUrl) {
                    this.name = epTitle
                    this.season = match?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = match?.groupValues?.get(2)?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, dataId) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // Helper Engine untuk membongkar enkripsi Javascript Packer secara native & aman
    private fun unpackDeanEdwards(packedScript: String): String {
        return try {
            val payload = packedScript.substringAfter("}('").substringBefore("',")
            val remaining = packedScript.substringAfter("',")
            val base = remaining.substringBefore(",").trim().toIntOrNull() ?: 36
            val wordsStr = remaining.substringAfter("'").substringBefore("'.split")
            val words = wordsStr.split("|")

            val wordRegex = Regex("""\b[0-9a-zA-Z]+\b""")
            wordRegex.replace(payload) { matchResult ->
                val wordCode = matchResult.value
                val index = wordCode.toIntOrNull(base) ?: return@replace wordCode
                if (index < words.size && words[index].isNotEmpty()) words[index] else wordCode
            }
        } catch (e: Exception) {
            ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Ambil ID unik konten film/episode
        val ajaxId = if (data.startsWith("http")) {
            app.get(data).document.selectFirst("#muvipro_player_content_id")?.attr("data-id") ?: return false
        } else data

        // 2. Ambil Iframe Gateway dari WordPress AJAX KlikXXI
        val ajaxResponse = app.post(
            url = "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "muvipro_player_content", "tab" to "p1", "post_id" to ajaxId)
        ).text
        
        // 3. Cari URL src dari hgcloud.to
        val hgCloudUrl = Regex("""src=["'](https://hgcloud\.to/e/[a-zA-Z0-9]+)["']""").find(ajaxResponse)?.groupValues?.get(1) ?: return false
        
        // 4. Lompati gerbang redirect hgcloud dan langsung konversi ke domain tujuan (masukestin.com)
        val fileId = hgCloudUrl.substringAfter("/e/")
        val playerPageUrl = "https://masukestin.com/e/$fileId"

        // 5. Load halaman JWPlayer dari masukestin.com dengan menyertakan Referer hgcloud
        val playerPageHtml = app.get(
            url = playerPageUrl,
            headers = mapOf("Referer" to "https://hgcloud.to/")
        ).text

        // 6. Tangkap baris kode yang berisi enkripsi Packer eval()
        val packedScript = playerPageHtml.lineSequence()
            .firstOrNull { it.contains("eval(function(p,a,c,k,e,d)") } ?: return false

        // 7. Bongkar skrip enkripsi secara native menggunakan engine helper kita
        val unpackedJs = unpackDeanEdwards(packedScript)

        // 8. Ekstrak link master.m3u8 langsung dari hasil bongkaran skrip tersebut
        val masterM3u8 = Regex("""["'](https?://[^"']+\.m3u8)["']""").find(unpackedJs)?.groupValues?.get(1) ?: return false

        // 9. Kembalikan link streaming menggunakan pembungkus lambda builder yang benar
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Server HGCloud (HLS Multi-Quality)",
                url = masterM3u8,
                [span_6](start_span)[span_7](start_span)type = ExtractorLinkType.M3U8[span_6](end_span)[span_7](end_span)
            ) {
                [span_8](start_span)[span_9](start_span)this.referer = playerPageUrl[span_8](end_span)[span_9](end_span)
                [span_10](start_span)[span_11](start_span)[span_12](start_span)this.quality = Qualities.P720.value[span_10](end_span)[span_11](end_span)[span_12](end_span)
            }
        )

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        
        val isTvSeries = this.selectFirst(".gmr-numbeps") != null || href.contains("/tv/")
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }
}
