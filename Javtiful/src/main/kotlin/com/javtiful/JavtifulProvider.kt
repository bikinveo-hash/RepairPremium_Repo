package com.javtiful

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class JavtifulProvider : MainAPI() {
    override var name = "Javtiful"
    override var mainUrl = "https://javtiful.com"
    override var lang = "id"
    override val supportedTypes: Set<TvType> = setOf(TvType.NSFW)

    override val hasMainPage = true

    // Base headers untuk bypass proteksi bot Cloudflare & penyamaran browser
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        Pair("id/foryou", "Untuk Anda"),
        Pair("id/censored", "Sensor"),
        Pair("id/uncensored", "Tanpa Sensor"),
        Pair("id/reducing-mosaic", "Reducing Mosaic")
    )

    // ==================== LOGIKA HALAMAN UTAMA ====================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}?page=$page"
        }

        val res = app.get(url, headers = baseHeaders)
        val document = res.document

        val homeItems = document.select("article.front-video-card").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, homeItems)
    }

    // ==================== LOGIKA PENCARIAN BERPAGINASI ====================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchUrl = if (page <= 1) {
            "$mainUrl/id/search?q=$query"
        } else {
            "$mainUrl/id/search?page=$page&q=$query"
        }

        val res = app.get(searchUrl, headers = baseHeaders)
        val document = res.document

        val searchResults = document.select("article.front-video-card").mapNotNull {
            it.toSearchResult()
        }

        val hasNext = document.select("nav.front-pagination a.front-pagination-link").any {
            it.text().contains("Berikutnya", ignoreCase = true)
        }

        return newSearchResponseList(searchResults, hasNext)
    }

    // Pemrosesan item card grid & Filter ganda (Partner & Kualitas Non-HD)
    private fun Element.toSearchResult(): SearchResponse? {
        // 1. FILTER PARTNER: Langsung skip jika kartu video berlabel partner / rekomendasi luar
        if (this.hasClass("front-partner-card") || this.selectFirst(".front-partner-badge") != null) {
            return null
        }

        // 2. FILTER LOGO HD: Cari elemen badge kualitas, wajib ada dan harus berlogo HD/4K
        val qualityElement = this.selectFirst(".front-quality-tag") ?: return null
        val quality = qualityElement.text()

        if (!quality.contains("HD", ignoreCase = true) && !quality.contains("4K", ignoreCase = true)) {
            return null
        }

        val titleElement = this.selectFirst("a.front-video-title") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")

        val thumbElement = this.selectFirst("a.front-video-thumb img")
        val posterUrl = thumbElement?.attr("data-front-lazy-src")?.takeIf { it.isNotBlank() }
            ?: thumbElement?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
            addQuality(quality)
        }
    }

    // ==================== LOGIKA HALAMAN DETAIL (LOAD) ====================
    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url, headers = baseHeaders)
        val document = res.document

        val title = document.selectFirst(".front-watch-title h1")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: "Javtiful Video"

        val posterUrl = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")

        val tagsList = document.select(".front-watch-link-chip").map { it.text() }

        val actorsList = document.select(".front-watch-actor-card").map { actorCard ->
            val actorName = actorCard.selectFirst("span")?.text() ?: ""
            val actorThumb = actorCard.selectFirst("img")?.attr("src")
            Actor(actorName, fixUrlNull(actorThumb))
        }

        // FITUR SARAN FILM: Mengambil item video terkait dari halaman detail (otomatis menyaring Partner & Non-HD)
        val recommendationsList = document.select("article.front-video-card").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = plot
            this.tags = tagsList
            this.actors = actorsList.map { ActorData(it) }
            this.recommendations = recommendationsList
        }
    }

    // ==================== EKSTRAKSI TAUTAN & SUBTITLE ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = baseHeaders)
        val document = res.document

        // ------------------------------------------------------------------
        // FITUR PENCARIAN SUBTITLE OTOMATIS VIA SUBTITLECAT (PATCHED: hard-match)
        // ------------------------------------------------------------------
        try {
            // 1. Normalisasi kode film (tahan input: "adn-784", "ADN784", "/watch/adn-784", dll)
            val rawCode = data.substringAfterLast("/").substringBefore("?")
            val codeRegex = Regex("""[a-zA-Z]{2,5}-?\d{3,4}""")
            val normalizedCode = (codeRegex.find(rawCode)?.value ?: rawCode).uppercase()
            val videoCode = normalizedCode.replace(Regex("""([A-Z]{2,5})(\d{3,4})"""), "$1-$2")
            val codeLetterPart = videoCode.substringBefore("-")
            val codeDigitPart = videoCode.substringAfter("-")

            // 2. Request halaman pencarian SubtitleCat
            val subSearchUrl = "https://www.subtitlecat.com/index.php?search=$videoCode"
            val subSearchDoc = app.get(subSearchUrl, headers = baseHeaders).document

            // 3. Seleksi kandidat baris hasil pencarian
            val candidateRows = subSearchDoc.select("table.sub-table tbody tr")

            data class SubtitleCandidate(val row: Element, val anchor: Element?, val labelText: String)

            val matchedCandidates = candidateRows.mapNotNull { row ->
                val anchor = row.selectFirst("td a")
                val rowText = row.text().uppercase()
                val anchorText = anchor?.text()?.uppercase().orEmpty()
                val hrefText = anchor?.attr("href")?.uppercase().orEmpty()

                // 4. Pencocokan keras:
                //    a) kode film lengkap (mis. "ADN-784") muncul di salah satu teks baris, ATAU
                //    b) huruf seri ("ADN") + digit ("784" sebagai whole word) muncul bersamaan.
                val fullCodeMatch = rowText.contains(videoCode) ||
                    anchorText.contains(videoCode) ||
                    hrefText.contains(videoCode)

                val looseMatch = rowText.contains(codeLetterPart) &&
                    Regex("""\b""" + Regex.escape(codeDigitPart) + """\b""").containsMatchIn(rowText)

                if (!fullCodeMatch && !looseMatch) {
                    null
                } else {
                    // Anti false-positive: pastikan tidak ada seri beda dengan digit identik
                    // (mis. baris menyebut "STAR-784" atau "ABC-784" padahal target kita "ADN-784")
                    val otherSeriesWithSameDigits = Regex(
                        """([A-Z]{2,5})-?" + Regex.escape(codeDigitPart) + """\b"""
                    ).findAll(rowText).any { match ->
                        match.value.replace("-", "") != videoCode.replace("-", "")
                    }
                    if (otherSeriesWithSameDigits) {
                        null
                    } else {
                        val labelText = row.text().takeIf { it.isNotBlank() }
                            ?: anchor?.text()?.takeIf { it.isNotBlank() }
                            ?: "Opsi"
                        SubtitleCandidate(row, anchor, labelText)
                    }
                }
            }.distinctBy { it.anchor?.attr("href") ?: it.labelText }

            // 5. Kunjungi halaman detail HANYA untuk kandidat yang lolos pencocokan
            matchedCandidates.forEachIndexed { index, candidate ->
                val anchor = candidate.anchor
                val subLabel = candidate.labelText

                val resultPath = anchor?.attr("href")
                if (resultPath.isNullOrBlank()) return@forEachIndexed

                var detailUrl = if (resultPath.startsWith("http")) resultPath else "https://www.subtitlecat.com/${resultPath.removePrefix("/")}"
                detailUrl = detailUrl.replace(" ", "%20")

                try {
                    val subDetailDoc = app.get(detailUrl, headers = baseHeaders).document

                    // 6. Validasi silang pada halaman detail: judul halaman harus memuat kode film.
                    val detailTitleRaw = subDetailDoc.title().ifBlank {
                        subDetailDoc.selectFirst("h1, h2, .sub-title, .page-title")?.text().orEmpty()
                    }
                    val detailTitle = detailTitleRaw.uppercase()
                    val detailTitleNormalized = detailTitle.replace(Regex("""\s+"""), " ")
                    val detailMatches = detailTitleNormalized.contains(videoCode) ||
                        (detailTitleNormalized.contains(codeLetterPart) &&
                            Regex("""\b""" + Regex.escape(codeDigitPart) + """\b""")
                                .containsMatchIn(detailTitleNormalized))

                    if (!detailMatches) {
                        return@forEachIndexed
                    }

                    val indoSubPath = subDetailDoc.selectFirst("#download_id")?.attr("href")

                    if (!indoSubPath.isNullOrBlank()) {
                        var finalDownloadUrl = if (indoSubPath.startsWith("http")) indoSubPath else "https://www.subtitlecat.com/${indoSubPath.removePrefix("/")}"
                        finalDownloadUrl = finalDownloadUrl.replace(" ", "%20")

                        // Sanitasi URL: hanyaizinkan yang tampak seperti file subtitle
                        val lowerUrl = finalDownloadUrl.lowercase()
                        val isPlausibleSubtitle = lowerUrl.endsWith(".srt") ||
                            lowerUrl.endsWith(".zip") ||
                            lowerUrl.endsWith(".rar") ||
                            lowerUrl.endsWith(".ass") ||
                            lowerUrl.endsWith(".sub")

                        if (isPlausibleSubtitle) {
                            subtitleCallback(
                                newSubtitleFile(
                                    lang = "ID - $subLabel (${index + 1}/${matchedCandidates.size})",
                                    url = finalDownloadUrl
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Mencegah kerusakan loop jika satu berkas gagal dimuat
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // ------------------------------------------------------------------

        // Jalur Utama Player: Parsing objek JSON konfigurasi #frontWatchConfig
        val configScript = document.selectFirst("script#frontWatchConfig")?.data()
        if (!configScript.isNullOrBlank()) {
            val parsedConfig = tryParseJson<FrontWatchConfig>(configScript)

            parsedConfig?.playerSources?.forEach { source ->
                val streamUrl = source.src
                if (!streamUrl.isNullOrBlank()) {
                    val resQuality = source.size ?: 720

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Javtiful (R2 Storage)",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = resQuality
                            // PERBAIKAN BANDWIDTH: Memaksa ExoPlayer mengirimkan browser headers agar tidak di-throttle Cloudflare
                            this.headers = baseHeaders
                        }
                    )
                }
            }
        }

        // Jalur Cadangan 1: Cek player iframe eksternal
        document.select("iframe").forEach { iframe ->
            val frameUrl = iframe.attr("src")
            if (frameUrl.isNotBlank()) {
                loadExtractor(frameUrl, subtitleCallback, callback)
            }
        }

        // Jalur Cadangan 2: Cek raw video source html5 native tag
        document.select("video source").forEach { srcTag ->
            val videoUrl = srcTag.attr("src")
            if (videoUrl.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Javtiful Native Source",
                        url = fixUrl(videoUrl),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = baseHeaders
                    }
                )
            }
        }

        return true
    }

    data class FrontWatchConfig(
        @JsonProperty("playerSources") val playerSources: List<PlayerSource>? = null,
        @JsonProperty("videoTitle") val videoTitle: String? = null
    )

    data class PlayerSource(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("size") val size: Int? = null
    )
}
