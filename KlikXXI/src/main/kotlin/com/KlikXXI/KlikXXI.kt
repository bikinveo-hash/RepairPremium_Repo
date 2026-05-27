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
        // ── Beranda ──────────────────────────────────────────────────────
        "$mainUrl/?s=&search=advanced&post_type=movie&index=&orderby=&genre=&movieyear=&country=&quality=" to "Latest Movies",
        "$mainUrl/tv"                          to "TV Series",
        // ── Kategori ─────────────────────────────────────────────────────
        "$mainUrl/category/action/"            to "Action",
        "$mainUrl/category/adventure/"         to "Adventure",
        "$mainUrl/category/crime/"             to "Crime",
        "$mainUrl/category/drama/"             to "Drama",
        "$mainUrl/category/korea/"             to "Korea",
        "$mainUrl/category/fantasy/"           to "Fantasy",
        "$mainUrl/category/horror/"            to "Horror",
        "$mainUrl/category/india-series/"      to "India Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            // URL dengan query string (?s=...) → sisipkan /page/N/ sebelum tanda ?
            request.data.contains("?") ->
                if (page <= 1) request.data
                else request.data.replace("/?", "/page/$page/?")
            // URL bersih (kategori, /tv, dsb.) → tambahkan /page/N/
            else ->
                if (page <= 1) request.data
                else "${request.data.removeSuffix("/")}/page/$page/"
        }

        val document = app.get(url).document
        val items = document
            .select("article.item, article.item-infinite, div.gmr-item-modulepost")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
        return document
            .select("article.item, article.item-infinite")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()
            ?.replace("Streaming Film", "")?.trim() ?: ""

        val posterHtml = document
            .selectFirst(".gmr-movie-data img, .content-thumbnail img, figure img")
            ?.let { it.attr("data-lazy-src").ifEmpty { it.attr("src") } }
        val poster = fixUrlNull(posterHtml)?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")

        val tags        = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val year        = document.selectFirst(".gmr-moviedata:contains(Year) a")?.text()?.toIntOrNull()
        val description = document.selectFirst(".entry-content-single p, .gmr-movie-content")?.text()

        // Rating: ambil nilai dari .gmr-rating-item (format "8.5" atau "8,5")
        val ratingValue = document.selectFirst(".gmr-rating-item")
            ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()

        val episodeElements = document.select(".gmr-season-episodes a.button-shadow")

        return if (episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapNotNull {
                val epHref = it.attr("href")
                val epName = it.text()
                if (epName.contains("Batch", true)) return@mapNotNull null
                val sMatch = Regex("""S(\d+)""").find(epName)
                val eMatch = Regex("""Eps(\d+)""").find(epName)
                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = sMatch?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = eMatch?.groupValues?.get(1)?.toIntOrNull()
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = Score.from10(ratingValue)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = Score.from10(ratingValue)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOAD LINKS  (tidak diubah sama sekali)
    // ─────────────────────────────────────────────────────────────────────────

    private fun extractHlsUrl(unpackedJs: String): String? {
        listOf("hls4", "hls3", "hls2").forEach { key ->
            val match = Regex(""""$key"\s*:\s*"([^"]+)"""").find(unpackedJs)
                ?: Regex("""'$key'\s*:\s*'([^']+)'""").find(unpackedJs)
            val url = match?.groupValues?.get(1) ?: return@forEach
            if (url.isBlank()) return@forEach
            return when {
                url.startsWith("http") -> url
                url.startsWith("//")   -> "https:$url"
                url.startsWith("/")    -> "https://masukestin.com$url"
                else                   -> "https://masukestin.com/$url"
            }
        }
        return null
    }

    private fun extractQualityLabels(unpackedJs: String): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        val block = Regex("""['"']qualityLabels['"']\s*:\s*\{([^}]+)\}""")
            .find(unpackedJs)?.groupValues?.get(1) ?: return result
        Regex(""""(\d+)"\s*:\s*"(\d+)p"""").findAll(block).forEach { m ->
            val bw     = m.groupValues[1].toIntOrNull() ?: return@forEach
            val height = m.groupValues[2].toIntOrNull() ?: return@forEach
            result[bw] = height
        }
        return result
    }

    private suspend fun parseMasterM3u8(
        masterUrl: String,
        referer: String,
        sourceName: String,
        qualityLabels: Map<Int, Int>,
        callback: (ExtractorLink) -> Unit
    ) {
        val content = try {
            app.get(masterUrl, headers = mapOf("Referer" to referer)).text
        } catch (e: Exception) {
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name   = "HGCloud",
                    url    = masterUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        if (!content.contains("#EXT-X-STREAM-INF")) {
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name   = "HGCloud",
                    url    = masterUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        val baseUrl          = masterUrl.substringBeforeLast("/")
        var pendingBandwidth = -1
        var pendingHeight    = -1

        content.lines().forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val bwBps  = Regex("""BANDWIDTH=(\d+)""").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    val height = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: run {
                        if (bwBps > 0) qualityLabels[bwBps / 1000] ?: -1 else -1
                    }
                    pendingBandwidth = bwBps
                    pendingHeight    = height
                }
                !line.startsWith("#") && line.isNotBlank() && pendingHeight != -1 -> {
                    val variantUrl = when {
                        line.trim().startsWith("http") -> line.trim()
                        line.trim().startsWith("/")    -> "https://masukestin.com${line.trim()}"
                        else                           -> "$baseUrl/${line.trim()}"
                    }
                    val label = if (pendingHeight > 0) "${pendingHeight}p" else "HGCloud"
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name   = "HGCloud $label",
                            url    = variantUrl,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = if (pendingHeight > 0) pendingHeight
                                          else Qualities.Unknown.value
                        }
                    )
                    pendingBandwidth = -1
                    pendingHeight    = -1
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isDataJob: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val ajaxId   = document
            .selectFirst(".gmr-server-wrap, #muvipro_player_content_id")
            ?.attr("data-id") ?: return false

        val servers = document
            .select("ul.muvipro-player-tabs li a, .gmr-player-nav li a")
            .mapNotNull {
                val href = it.attr("href")
                if (href.startsWith("#p")) href.replace("#p", "") else null
            }

        servers.distinct().forEach { serverNum ->
            val response = app.post(
                url     = "$mainUrl/wp-admin/admin-ajax.php",
                data    = mapOf(
                    "action"  to "muvipro_player_content",
                    "tab"     to "p$serverNum",
                    "post_id" to ajaxId
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            val iframeUrl =
                Regex("""(?i)src='([^"']+)""").find(response)?.groupValues?.get(1)
                    ?: Regex("""(?i)src="([^"']+)""").find(response)?.groupValues?.get(1)

            iframeUrl?.let { raw ->
                val finalUrl = if (raw.startsWith("//")) "https:$raw" else raw

                if (finalUrl.contains("hgcloud.to")) {
                    try {
                        val fileId        = finalUrl.substringAfter("/e/")
                        val playerPageUrl = "https://masukestin.com/e/$fileId"

                        val playerPageHtml = app.get(
                            url     = playerPageUrl,
                            headers = mapOf("Referer" to "https://hgcloud.to/")
                        ).text

                        val unpackedJs    = getAndUnpack(playerPageHtml)
                        val masterUrl     = extractHlsUrl(unpackedJs) ?: run {
                            loadExtractor(finalUrl, data, subtitleCallback, callback)
                            return@let
                        }
                        val qualityLabels = extractQualityLabels(unpackedJs)

                        parseMasterM3u8(
                            masterUrl     = masterUrl,
                            referer       = playerPageUrl,
                            sourceName    = this.name,
                            qualityLabels = qualityLabels,
                            callback      = callback
                        )
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

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPER — toSearchResult()
    //  Sekarang juga mengisi:
    //    • quality  → badge HD / CAM / WebRip dll. di kartu (SearchQuality)
    //    • score    → rating bintang di kartu (Score)
    // ─────────────────────────────────────────────────────────────────────────
    private fun Element.toSearchResult(): SearchResponse? {
        val titleLink = this.selectFirst(".entry-title a") ?: return null
        val title     = titleLink.text()
        val href      = titleLink.attr("href")

        val posterRaw = this.selectFirst("img")?.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        }
        val posterUrl = fixUrlNull(posterRaw)?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")

        // ── Quality label (HD / CAM / BluRay / WebRip dsb.) ──────────────
        // KlikXXI meletakkan label kualitas di .gmr-quality-item atau .quality
        val qualityStr = this
            .selectFirst(".gmr-quality-item, .quality, .qualitylabel, span.quality")
            ?.text()?.trim()
        // getQualityFromString() dari MainAPI.kt mengkonversi "HD"→SearchQuality.HD,
        // "CAM"→SearchQuality.Cam, "BluRay"→SearchQuality.BlueRay, dst.
        val searchQuality = getQualityFromString(qualityStr)

        // ── Score / Rating ────────────────────────────────────────────────
        // KlikXXI menampilkan rating di .gmr-rating-item (format "8.5")
        val ratingValue = this
            .selectFirst(".gmr-rating-item, .rating, .star-rating")
            ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()
        // Score.from10() menerima Double? dalam rentang [0.0, 10.0]
        val score = Score.from10(ratingValue)

        val isTvSeries = this.selectFirst(".gmr-numbeps") != null || href.contains("/tv/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality   = searchQuality   // ← SearchQuality (badge kartu)
                this.score     = score            // ← Score (rating bintang kartu)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality   = searchQuality   // ← SearchQuality (badge kartu)
                this.score     = score            // ← Score (rating bintang kartu)
            }
        }
    }
}
