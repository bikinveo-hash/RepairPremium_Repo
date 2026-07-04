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
        mainPage("id/foryou", "Untuk Anda", horizontalImages = true),
        mainPage("id/censored", "Sensor", horizontalImages = true),
        mainPage("id/uncensored", "Tanpa Sensor", horizontalImages = true),
        mainPage("id/reducing-mosaic", "Reducing Mosaic", horizontalImages = true)
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

        return newHomePageResponse(request, homeItems)
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

        // === Resolusi poster: ambil gambar paling tajam yang tersedia ===
        val thumbElement = this.selectFirst("a.front-video-thumb img")

        // Helper kecil: cek apakah URL keliatan kayak placeholder yang harus diskip
        fun String.isPlaceholder(): Boolean {
            val s = this.lowercase()
            return s.endsWith(".svg") || s.contains("placeholder")
        }

        // Prioritas 1: data-front-lazy-src (strategi utama website ini)
        val primaryLazy = thumbElement?.attr("data-front-lazy-src")?.takeIf { it.isNotBlank() }

        // Prioritas 2: data-front-lazy-fallback-src (daftar '|'-separated)
        val fallbackChain = thumbElement?.attr("data-front-lazy-fallback-src")
            ?.split('|')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && !it.isPlaceholder() }
            ?: emptyList()

        val fallbackCandidate = fallbackChain.firstOrNull { it != primaryLazy }
            ?: fallbackChain.firstOrNull()

        val posterUrl = primaryLazy?.takeIf { !it.isPlaceholder() }
            ?: fallbackCandidate
            ?: thumbElement?.attr("data-original")?.takeIf { it.isNotBlank() && !it.isPlaceholder() }
            ?: thumbElement?.attr("data-src")?.takeIf { it.isNotBlank() && !it.isPlaceholder() }
            ?: thumbElement?.attr("data-hi-res-src")?.takeIf { it.isNotBlank() && !it.isPlaceholder() }
            ?: thumbElement?.attr("data-source")?.takeIf { it.isNotBlank() && !it.isPlaceholder() }
            ?: thumbElement?.attr("data-full")?.takeIf { it.isNotBlank() && !it.isPlaceholder() }
            ?: thumbElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() && !it.isPlaceholder() }
            ?: thumbElement?.attr("src")?.takeIf { !it.isPlaceholder() }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.posterHeaders = baseHeaders
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
            val actorThumb = actorCard.selectFirst("img")?.attr("data-original")
                ?.takeIf { it.isNotBlank() }
                ?: actorCard.selectFirst("img")?.attr("src")
            Actor(actorName, fixUrlNull(actorThumb))
        }

        // FITUR SARAN FILM: Mengambil item video terkait dari halaman detail (otomatis menyaring Partner & Non-HD)
        val recommendationsList = document.select("article.front-video-card").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.posterHeaders = baseHeaders
            this.plot = plot
            this.tags = tagsList
            this.actors = actorsList.map { ActorData(it) }
            this.recommendations = recommendationsList

            // === TRAILER: attach preview video dari CDN Javtiful ===
            scanAndAttachTrailer(this, document)
        }
    }

    // ==================== TRAILER / PREVIEW VIDEO ====================
    // Frontend Javtiful nampilin preview video on-hover di card.
    // CDN URL pattern: https://{cdn}/videos/previews/{YYYY}/{MM}/{VIDEO_CODE}/{VIDEO_CODE}_preview.mp4
    // Strategi: scan markup → parse JSON config → derive dari playerSources URL.
    private suspend fun scanAndAttachTrailer(
        response: MovieLoadResponse,
        document: org.jsoup.nodes.Document
    ) {
        val trailerUrl = extractPreviewUrl(document)
        if (trailerUrl.isNullOrBlank()) return

        response.addTrailer(
            trailerUrl = trailerUrl,
            referer = "$mainUrl/",
            addRaw = true,                  // direct .mp4 file dari CDN
            headers = baseHeaders           // CDN butuh Referer
        )
    }

    private fun extractPreviewUrl(document: org.jsoup.nodes.Document): String? {
        // ===== STRATEGI 1: Native <video>/<source> tag di markup =====
        document.selectFirst("video source")?.attr("src")?.takeIf { it.isNotBlank() }?.let { return it }
        document.selectFirst("video[src]")?.attr("src")?.takeIf { it.isNotBlank() }?.let { return it }

        // ===== STRATEGI 2: data-* attribute di player container =====
        val playerSelectors = listOf(
            ".front-watch-player", ".front-player", ".front-video-player",
            ".player-wrapper", ".video-wrapper", "[data-preview]"
        )
        for (selector in playerSelectors) {
            val container = document.selectFirst(selector) ?: continue
            listOf("data-preview", "data-preview-src", "data-preview-url",
                   "data-trailer", "data-trailer-url", "data-hover-video",
                   "data-front-preview-src", "data-front-lazy-preview-src")
                .firstNotNullOfOrNull { attr ->
                    container.attr(attr).takeIf { it.isNotBlank() }
                }?.let { return it }
        }

        // ===== STRATEGI 3: og:video / twitter:player meta tags =====
        listOf("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream")
            .firstNotNullOfOrNull { prop ->
                document.selectFirst("meta[property=\"$prop\"]")?.attr("content")?.takeIf { it.isNotBlank() }
            }?.let { return it }

        // ===== STRATEGI 4: Parse frontWatchConfig JSON =====
        val config = try {
            tryParseJson<FrontWatchConfig>(document.selectFirst("script#frontWatchConfig")?.data() ?: "")
        } catch (_: Exception) { null }

        // 4a. Cek explicit preview/trailer URL field
        config?.let { cfg ->
            listOfNotNull(cfg.previewUrl, cfg.previewVideoUrl, cfg.trailerUrl)
                .firstOrNull { it.isNotBlank() }
                ?.let { return it }
        }

        // 4b. Scan semua string field di JSON — cari URL .mp4/.webm/.m3u8 yang ada keyword preview/trailer
        document.selectFirst("script#frontWatchConfig")?.data()?.let { jsonText ->
            Regex("""["'](https?://[^"']+\.(?:mp4|webm|m3u8))["']""")
                .findAll(jsonText)
                .map { it.groupValues[1] }
                .firstOrNull { url ->
                    url.contains("preview", ignoreCase = true) ||
                    url.contains("trailer", ignoreCase = true)
                }?.let { return it }
        }

        // ===== STRATEGI 5: Derive dari playerSources[0].src =====
        // Main URL pattern: https://{cdn}/videos/{YYYY}/{MM}/{CODE}/{CODE}.mp4
        // Target:           https://{cdn}/videos/previews/{YYYY}/{MM}/{CODE}/{CODE}_preview.mp4
        config?.playerSources?.firstOrNull()?.src?.takeIf { it.isNotBlank() }?.let { mainSrc ->
            val derived = derivePreviewUrl(mainSrc)
            if (derived != null && derived != mainSrc) {
                return derived
            }
        }

        // ===== STRATEGI 6: Scan semua <script> JSON buat field hash + date =====
        val codeFromAnywhere = extractVideoCodeFromScripts(document)
        val dateFromAnywhere = extractPublishDateFromScripts(document)
        if (!codeFromAnywhere.isNullOrBlank() && !dateFromAnywhere.isNullOrBlank()) {
            val cdnBase = config?.playerSources?.firstOrNull()?.src
                ?.let { Regex("""(https?://[^/]+)""").find(it)?.groupValues?.get(1) }

            if (!cdnBase.isNullOrBlank()) {
                return "$cdnBase/videos/previews/$dateFromAnywhere/$codeFromAnywhere/${codeFromAnywhere}_preview.mp4"
            }
        }

        return null
    }

    private fun derivePreviewUrl(mainSrc: String): String? {
        // Pattern target: /videos/previews/{YYYY}/{MM}/{CODE}/{CODE}_preview.mp4
        return runCatching {
            val withPreviewDir = mainSrc.replace(Regex("/videos/"), "/videos/previews/")
            withPreviewDir.replace(Regex("(\\.[a-z0-9]+)$"), "_preview$1")
        }.getOrNull()?.takeIf {
            it != mainSrc && it.contains("_preview.")
        }
    }

    private fun extractVideoCodeFromScripts(document: org.jsoup.nodes.Document): String? {
        val codeRegex = Regex("(VID-[A-F0-9]{6,}|\\b[a-f0-9]{16,}\\b)")
        return document.select("script").mapNotNull { script ->
            codeRegex.find(script.data())?.value
        }.firstOrNull()
    }

    private fun extractPublishDateFromScripts(document: org.jsoup.nodes.Document): String? {
        val isoRegex = Regex("""["']?(20\d{2})[-/](0[1-9]|1[0-2])[-/]?(0[1-9]|[12]\d|3[01])?["']?""")
        return document.select("script").mapNotNull { script ->
            isoRegex.find(script.data())?.value?.trim('"', '\'')
        }.firstOrNull { it.length >= 7 }
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
            val rawCode = data.substringAfterLast("/").substringBefore("?")
            val codeRegex = Regex("[a-zA-Z]{2,5}-?[0-9]{3,4}")
            val normalizedCode = (codeRegex.find(rawCode)?.value ?: rawCode).uppercase()
            val videoCode = normalizedCode.replace(Regex("([A-Z]{2,5})([0-9]{3,4})"), "$1-$2")
            val codeLetterPart = videoCode.substringBefore("-")
            val codeDigitPart = videoCode.substringAfter("-")

            val subSearchUrl = "https://www.subtitlecat.com/index.php?search=$videoCode"
            val subSearchDoc = app.get(subSearchUrl, headers = baseHeaders).document

            val candidateRows = subSearchDoc.select("table.sub-table tbody tr")

            data class SubtitleCandidate(val row: Element, val anchor: Element?, val labelText: String)

            val videoCodeNoDash = videoCode.replace("-", "")
            val fullCodeRegex = Regex(videoCode.replace("-", "[-]?"), RegexOption.IGNORE_CASE)
            val digitWordRegex = Regex("\\b" + Regex.escape(codeDigitPart) + "\\b")
            val otherSeriesRegex = Regex("([A-Z]{2,5})[-]?" + Regex.escape(codeDigitPart) + "\\b", RegexOption.IGNORE_CASE)

            val matchedCandidates: List<SubtitleCandidate> = candidateRows.mapNotNull { row ->
                val anchor = row.selectFirst("td a")
                val rowText = row.text().uppercase()
                val anchorText = anchor?.text()?.uppercase().orEmpty()
                val hrefText = anchor?.attr("href")?.uppercase().orEmpty()

                val haystack = "$rowText $anchorText $hrefText"
                val fullCodeMatch = fullCodeRegex.containsMatchIn(haystack) || haystack.contains(videoCode)
                val looseMatch = rowText.contains(codeLetterPart) && digitWordRegex.containsMatchIn(rowText)

                if (!fullCodeMatch && !looseMatch) {
                    null
                } else {
                    val conflictingSeries = otherSeriesRegex.findAll(rowText).any { match ->
                        val normalized = match.value.replace("-", "").uppercase()
                        normalized != videoCodeNoDash
                    }
                    if (conflictingSeries) {
                        null
                    } else {
                        val labelText = row.text().takeIf { it.isNotBlank() }
                            ?: anchor?.text()?.takeIf { it.isNotBlank() }
                            ?: "Opsi"
                        SubtitleCandidate(row, anchor, labelText)
                    }
                }
            }.distinctBy { it.anchor?.attr("href") ?: it.labelText }

            val finalCandidates = matchedCandidates.take(2)
            if (finalCandidates.isNotEmpty()) {
                finalCandidates.forEachIndexed { index, candidate ->
                    val anchor = candidate.anchor
                    val subLabel = candidate.labelText

                    val resultPath = anchor?.attr("href")
                    if (resultPath.isNullOrBlank()) return@forEachIndexed

                    val detailUrl = if (resultPath.startsWith("http")) {
                        resultPath
                    } else {
                        "https://www.subtitlecat.com/${resultPath.removePrefix("/")}"
                    }.replace(" ", "%20")

                    try {
                        val subDetailDoc = app.get(detailUrl, headers = baseHeaders).document

                        val detailTitleRaw = subDetailDoc.title().ifBlank {
                            subDetailDoc.selectFirst("h1, h2, .sub-title, .page-title")?.text().orEmpty()
                        }
                        val detailTitleNormalized = detailTitleRaw.uppercase().replace(Regex("\\s+"), " ")
                        val detailMatches = detailTitleNormalized.contains(videoCode) ||
                            (detailTitleNormalized.contains(codeLetterPart) &&
                                digitWordRegex.containsMatchIn(detailTitleNormalized))

                        if (!detailMatches) {
                            return@forEachIndexed
                        }

                        val indoSubPath = subDetailDoc.selectFirst("#download_id")?.attr("href")

                        if (!indoSubPath.isNullOrBlank()) {
                            val finalDownloadUrl = if (indoSubPath.startsWith("http")) {
                                indoSubPath
                            } else {
                                "https://www.subtitlecat.com/${indoSubPath.removePrefix("/")}"
                            }.replace(" ", "%20")

                            val lowerUrl = finalDownloadUrl.lowercase()
                            val isPlausibleSubtitle = lowerUrl.endsWith(".srt") ||
                                lowerUrl.endsWith(".zip") ||
                                lowerUrl.endsWith(".rar") ||
                                lowerUrl.endsWith(".ass") ||
                                lowerUrl.endsWith(".sub")

                            if (isPlausibleSubtitle) {
                                subtitleCallback(
                                    newSubtitleFile(
                                        lang = "ID - $subLabel (${index + 1}/${finalCandidates.size})",
                                        url = finalDownloadUrl
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Mencegah kerusakan loop jika satu berkas gagal dimuat
                    }
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
        @JsonProperty("videoTitle") val videoTitle: String? = null,
        // Field tambahan untuk trailer
        @JsonProperty("previewUrl") val previewUrl: String? = null,
        @JsonProperty("previewVideoUrl") val previewVideoUrl: String? = null,
        @JsonProperty("trailerUrl") val trailerUrl: String? = null,
        @JsonProperty("hash") val hash: String? = null,
        @JsonProperty("videoCode") val videoCode: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("publishedAt") val publishedAt: String? = null,
        @JsonProperty("published_at") val publishedAtSnake: String? = null,
        @JsonProperty("createdAt") val createdAt: String? = null
    )

    data class PlayerSource(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("size") val size: Int? = null
    )
}
