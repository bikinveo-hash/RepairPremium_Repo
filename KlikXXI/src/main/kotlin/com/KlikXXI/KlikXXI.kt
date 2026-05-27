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
        "$mainUrl/?s=&search=advanced&post_type=movie&index=&orderby=&genre=&movieyear=&country=&quality=" to "Latest Movies",
        "$mainUrl/tv" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) {
            if (page <= 1) request.data else request.data.replace("/?", "/page/$page/?")
        } else {
            if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = app.get(url).document
        val items = document.select("article.item, article.item-infinite, div.gmr-item-modulepost")
            .mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document.select("article.item, article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.replace("Streaming Film", "")?.trim() ?: ""
        
        val posterHtml = document.selectFirst(".gmr-movie-data img, .content-thumbnail img, figure img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }
        val poster = fixUrlNull(posterHtml)?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")

        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val year = document.selectFirst(".gmr-moviedata:contains(Year) a")?.text()?.toIntOrNull()
        val description = document.selectFirst(".entry-content-single p, .gmr-movie-content")?.text()
        val ratingValue = document.selectFirst(".gmr-rating-item")?.text()?.trim()?.toDoubleOrNull()

        val episodeElements = document.select(".gmr-season-episodes a.button-shadow")
        
        return if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull {
                val epHref = it.attr("href")
                val epName = it.text()
                if (epName.contains("Batch", true)) return@mapNotNull null
                
                val sMatch = Regex("""S(\d+)""").find(epName)
                val eMatch = Regex("""Eps(\d+)""").find(epName)
                
                newEpisode(epHref) {
                    this.name = epName
                    this.season = sMatch?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = eMatch?.groupValues?.get(1)?.toIntOrNull()
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(ratingValue)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(ratingValue)
            }
        }
    }

    // Engine Internal Helper Unpacker JS (Dean Edwards)
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

    /**
     * Parse master M3U8 dan invoke callback untuk setiap variant quality.
     * Ini yang membuat semua resolusi (1080p, 720p, 480p) muncul di CloudStream.
     */
    private suspend fun parseMasterM3u8AndCallback(
        masterUrl: String,
        refererUrl: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8Text = try {
            app.get(
                url = masterUrl,
                headers = mapOf("Referer" to refererUrl)
            ).text
        } catch (e: Exception) {
            // Jika gagal fetch master, fallback satu link Unknown quality
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = "HGCloud",
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = refererUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Base URL untuk resolve relative path
        val baseUrl = masterUrl.substringBeforeLast("/")

        // Regex menangkap RESOLUTION dan URI di baris berikutnya
        // Format standar M3U8: #EXT-X-STREAM-INF:BANDWIDTH=...,RESOLUTION=WxH\nstream_url
        val streamInfRegex = Regex(
            """#EXT-X-STREAM-INF:[^\n]*RESOLUTION=\d+x(\d+)[^\n]*\n([^\n]+)""",
            RegexOption.IGNORE_CASE
        )

        val matches = streamInfRegex.findAll(m3u8Text).toList()

        if (matches.isEmpty()) {
            // Bukan master playlist (mungkin langsung media playlist), kirim apa adanya
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = "HGCloud",
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = refererUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Invoke callback untuk tiap variant — inilah kunci semua resolusi muncul
        matches.forEach { match ->
            val height = match.groupValues[1].toIntOrNull() ?: return@forEach
            val variantPath = match.groupValues[2].trim()

            // Resolve absolute URL jika path masih relatif
            val variantUrl = when {
                variantPath.startsWith("http") -> variantPath
                variantPath.startsWith("/")    -> "https://masukestin.com$variantPath"
                else                           -> "$baseUrl/$variantPath"
            }

            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = "HGCloud ${height}p",
                    url = variantUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = refererUrl
                    this.quality = height  // nilai integer langsung (720, 1080, 480, dst.)
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isDataJob: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val ajaxId = document.selectFirst(".gmr-server-wrap, #muvipro_player_content_id")?.attr("data-id") ?: return false

        val servers = document.select("ul.muvipro-player-tabs li a, .gmr-player-nav li a").mapNotNull {
            val href = it.attr("href")
            if (href.startsWith("#p")) href.replace("#p", "") else null
        }

        servers.distinct().forEach { serverNum ->
            val response = app.post(
                url = "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to "p$serverNum",
                    "post_id" to ajaxId
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            val iframeUrl = Regex("""(?i)src='([^"']+)""").find(response)?.groupValues?.get(1)
                ?: Regex("""(?i)src="([^"']+)""").find(response)?.groupValues?.get(1)

            iframeUrl?.let { url ->
                val finalUrl = if (url.startsWith("//")) "https:$url" else url
                
                if (finalUrl.contains("hgcloud.to")) {
                    try {
                        val fileId = finalUrl.substringAfter("/e/")
                        val playerPageUrl = "https://masukestin.com/e/$fileId"
                        val playerPageHtml = app.get(
                            url = playerPageUrl,
                            headers = mapOf("Referer" to "https://hgcloud.to/")
                        ).text

                        val packedScript = playerPageHtml.lineSequence()
                            .firstOrNull { it.contains("eval(function(p,a,c,k,e,d)") }

                        if (packedScript != null) {
                            val unpackedJs = unpackDeanEdwards(packedScript)

                            val masterM3u8Match = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
                                .find(unpackedJs)?.groupValues?.get(1)

                            if (masterM3u8Match != null) {
                                val finalStreamUrl = if (masterM3u8Match.startsWith("/")) {
                                    "https://masukestin.com$masterM3u8Match"
                                } else {
                                    masterM3u8Match
                                }

                                // ✅ FIX UTAMA: parse master M3U8 → semua variant quality di-callback
                                parseMasterM3u8AndCallback(
                                    masterUrl   = finalStreamUrl,
                                    refererUrl  = playerPageUrl,
                                    sourceName  = this.name,
                                    callback    = callback
                                )
                            }
                        }
                    } catch (e: Exception) {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleLink = this.selectFirst(".entry-title a") ?: return null
        val title = titleLink.text()
        val href = titleLink.attr("href")
        
        val posterRaw = this.selectFirst("img")?.let { 
            it.attr("data-lazy-src").ifEmpty { it.attr("src") } 
        }
        val posterUrl = fixUrlNull(posterRaw)?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        
        val isTvSeries = this.selectFirst(".gmr-numbeps") != null || href.contains("/tv/")
        
        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }
}
