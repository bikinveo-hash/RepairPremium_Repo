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
        val fallbackChain = thumbElement?.attr("data-front-lazy-src")
            ?.let { listOf(it) }
            ?: emptyList()

        // Ambil juga data-front-lazy-src dari <a> wrapper (bukan <img>)
        val aLazySrc = this.selectFirst("a.front-video-thumb")?.attr("data-front-lazy-src")
            ?.takeIf { it.isNotBlank() }

        val allLazySrcs = (fallbackChain + listOfNotNull(aLazySrc)).distinct()

        // Prioritas 1 final: data-front-lazy-src di <img> atau <a>
        val primaryLazyFinal = primaryLazy ?: aLazySrc

        // Prioritas 2: data-front-lazy-fallback-src (daftar '|'-separated)
        val fallbackChainFinal = thumbElement?.attr("data-front-lazy-fallback-src")
            ?.split('|')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && !it.isPlaceholder() }
            ?: emptyList()

        val fallbackCandidate = fallbackChainFinal.firstOrNull { it != primaryLazyFinal }
            ?: fallbackChainFinal.firstOrNull()

        val posterUrl = primaryLazyFinal?.takeIf { !it.isPlaceholder() }
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

        // FITUR SARAN FILM: Mengambil item video terkait dari halaman detail
        val recommendationsList = document.select("article.front-video-card").mapNotNull {
            it.toSearchResult()
        }

        // Build response dulu
        val response = newMovieLoadResponse(title, url, TvType.NSFW, dataUrl = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.posterHeaders = baseHeaders
            this.plot = plot
            this.tags = tagsList
            this.actors = actorsList.map { ActorData(it) }
            this.recommendations = recommendationsList
        }

        // === TRAILER: derive preview URL dengan hostname fix dari card ===
        // Hostname preview BEDA dari hostname main video (main=r2.cloudflarestorage.com,
        // preview=syyejpyh6y...jav.si). Kita ambil hostname preview dari data-front-video-preview-src
        // di card rekomendasi pada halaman yang sama.
        val trailerUrl = extractPreviewUrl(document)
        if (!trailerUrl.isNullOrBlank()) {
            response.trailers.add(
                TrailerData(
                    extractorUrl = trailerUrl,
                    referer = "$mainUrl/",
                    raw = true,
                    headers = mapOf(
                        "User-Agent" to (baseHeaders["User-Agent"] ?: USER_AGENT),
                        "Referer" to "$mainUrl/",
                        "Accept" to "*/*"
                    )
                )
            )
        }

        return response
    }

    // ==================== TRAILER / PREVIEW VIDEO ====================
    // Frontend Javtiful nampilin preview video on-hover di card.
    // CDN URL pattern: https://{previewHost}/videos/previews/{YYYY}/{MM}/{VIDEO_CODE}/{VIDEO_CODE}_preview.mp4
    // Sumber URL preview bisa dari:
    //   - data-front-video-preview-src di <a class="front-video-thumb"> (cuma untuk card lain, BUKAN video yg sedang dibuka)
    //   - frontWatchConfig.playerSources[0].src (main URL, perlu ditransform)
    //   - schema.org JSON-LD VideoObject (uploadDate, duration)
    private fun extractPreviewUrl(document: org.jsoup.nodes.Document): String? {
        // Parse frontWatchConfig JSON
        val config = try {
            tryParseJson<FrontWatchConfig>(
                document.selectFirst("script#frontWatchConfig")?.data() ?: ""
            )
        } catch (_: Exception) { null }

        val mainSrc = config?.playerSources?.firstOrNull()?.src

        // ===== STEP 1: Cari hostname preview dari card rekomendasi =====
        // Di halaman detail ada section rekomendasi yang punya preview-src.
        // Ambil hostname pertama yang valid (dia konsisten untuk semua video di site).
        val previewHostname = document.select("a.front-video-thumb[data-front-video-preview-src]")
            .mapNotNull { it.attr("data-front-video-preview-src").takeIf { s -> s.isNotBlank() } }
            .mapNotNull { url ->
                Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1)
            }
            .firstOrNull()

        // ===== STEP 2: Ambil video code & date =====
        // video code dari main URL (e.g. "VID-E7F85587")
        val videoCode = mainSrc
            ?.let { Regex("""/([A-Za-z0-9_-]+)_\d+p\.mp4""").find(it)?.groupValues?.get(1) }
            ?: extractVideoCodeFromScripts(document)

        // upload date dari JSON-LD VideoObject (paling reliable)
        val uploadDate = document.select("script[type=\"application/ld+json\"]")
            .mapNotNull { script ->
                tryParseJson<Map<String, Any?>>(script.data())?.get("uploadDate") as? String
            }
            .firstOrNull { it.startsWith("20") }

        val datePath = uploadDate?.let { dateStr ->
            // Parse "2026-07-04T00:35:45+07:00" → "2026/07"
            Regex("""(20\d{2})-(\d{2})""").find(dateStr)?.let {
                "${it.groupValues[1]}/${it.groupValues[2]}"
            }
        }

        // ===== STEP 3: Construct preview URL =====
        if (!videoCode.isNullOrBlank() && !datePath.isNullOrBlank() && !previewHostname.isNullOrBlank()) {
            return "$previewHostname/videos/previews/$datePath/$videoCode/${videoCode}_preview.mp4"
        }

        // ===== STEP 4: Fallback — derive langsung dari mainSrc =====
        // Kalau hostname preview ga ketemu di card, pakai hostname dari mainSrc
        // (kadang host-nya sama, kadang beda — tergantung migrasi CDN)
        if (!videoCode.isNullOrBlank() && !datePath.isNullOrBlank() && !mainSrc.isNullOrBlank()) {
            val mainHostname = Regex("""(https?://[^/]+)""").find(mainSrc)?.groupValues?.get(1)
            if (!mainHostname.isNullOrBlank()) {
                return "$mainHostname/videos/previews/$datePath/$videoCode/${videoCode}_preview.mp4"
            }
        }

        // ===== STEP 5: Fallback terakhir — scan scripts =====
        if (!videoCode.isNullOrBlank() && !datePath.isNullOrBlank()) {
            // Hardcode hostname preview yang umum dipakai Javtiful sebagai last resort
            val fallbackHost = "https://syyejpyh6yupolshmbfitseg.jav.si"
            return "$fallbackHost/videos/previews/$datePath/$videoCode/${videoCode}_preview.mp4"
        }

        return null
    }

    private fun extractVideoCodeFromScripts(document: org.jsoup.nodes.Document): String? {
        val codeRegex = Regex("(VID-[A-F0-9]{6,}|\\b[a-f0-9]{16,}\\b)")
        return document.select("script").mapNotNull { script ->
            codeRegex.find(script.data())?.value
        }.firstOrNull()
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
            // 1. Normalisasi kode film
            val rawCode = data.substringAfterLast("/").substringBefore("?")
            val codeRegex = Regex("[a-zA-Z]{2,5}-?[0-9]{3,4}")
            val normalizedCode = (codeRegex.find(rawCode)?.value ?: rawCode).uppercase()
            val videoCode = normalizedCode.replace(Regex("([A-Z]{2,5})([0-9]{3,4})"), "$1-$2")
            val codeLetterPart = videoCode.substringBefore("-")
            val codeDigitPart = videoCode.substringAfter("-")

            // 2. Request halaman pencarian SubtitleCat
            val subSearchUrl = "https://www.subtitlecat.com/index.php?search=$videoCode"
            val subSearchDoc = app.get(subSearchUrl, headers = baseHeaders).document

            // 3. Seleksi kandidat baris hasil pencarian
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

            // 5. Kunjungi halaman detail HANYA untuk kandidat yang lolos pencocokan.
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
