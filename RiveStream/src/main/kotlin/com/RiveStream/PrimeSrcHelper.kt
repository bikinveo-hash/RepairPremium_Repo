package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

/**
 * PrimeSrc Helper v3 - Refactor Berdasarkan Analisis `MainApi.kt` + `ExtractorApi.kt`
 * (CloudStream upstream terkini, lihat CHANGELOG untuk history lengkap).
 *
 * Versi ini fokus pada 2 improvement yang berdasarkan pola yang divalidasi dari
 * referensi upstream modern — BUKAN mengubah arsitektur existing:
 *
 *  [BARU v3] 1. **Type detection fix untuk torrent**: Sekarang `loadTorrentSources()`
 *    pakai pola `type = null` (= `INFER_TYPE` di ExtractorLinkType) supaya upstream
 *    `inferTypeFromUrl()` auto-detect:
 *      - `magnet:?xt=...`  → `ExtractorLinkType.MAGNET`
 *      - `*.torrent`        → `ExtractorLinkType.TORRENT`
 *      - lainnya            → fallback `ExtractorLinkType.TORRENT` (sebelumnya selalu hardcode TORRENT
 *        padahal magnet links harusnya MAGNET — bug minor dari v2).
 *
 *  [BARU v3] 2. **`PrimeSrcExtractor` proper `ExtractorApi` class** ditambahkan di
 *    companion. Bisa di-register manual ke `extractorApis` list (atau auto-pickup
 *    lewat reflection plugin loader). Pattern ini sesuai dengan upstream:
 *    - `abstract val name`, `abstract val mainUrl`, `abstract val requiresReferer`
 *    - Override `getUrl(url, referer, subtitleCallback, callback)` (4-arg overload,
 *      bukan 2-arg `List<ExtractorLink>?` yang lama)
 *    - `requiresReferer = false` karena PrimeSrc self-hosted
 *
 *    Untuk backward-compat: `invokePrimeSrc()` dan `invokeEmbedMode()` TETAP dipanggil
 *    manual dari `RiveStreamProvider.loadLinks`. `PrimeSrcExtractor` adalah alternatif
 *    opsional yang bisa diaktifin kalau lo mau auto-dispatch lewat `utils.loadExtractor()`.
 *
 *  [TETAP v2] 1-4: Semua enhancement dari v2 (invokeEmbedMode fallback ke /embed/agg,
 *    multi-source emission, per-host logging, legacy URL path parsing) — tidak diubah.
 *
 *  [TETAP v1]: Schema embed/video/torrent response, flow `invokePrimeSrc`, semua
 *    data class — tidak diubah.
 */
class PrimeSrcHelper {

    companion object {
        /** Base scrapper (server utama sumber video HLS/mp4 langsung) */
        private const val SCRAPPER_BASE = "https://scrapper.rivestream.app"

        /** Base Rive UI (untuk embed page sebagai fallback iframe) */
        private const val RIVE_BASE = "https://www.rivestream.app"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

        /**
         * Provider video yang aktif (diambil dari /api/providers).
         * Tidak semuanya return result untuk semua ID — perlu loop & filter null.
         */
        private val VIDEO_PROVIDERS = listOf(
            "primevids",   // HLS, multi-mirror (ngcloud/tcloud/ipcloud), umum untuk movie+TV
            "flowcast",    // MP4 multi-quality + subtitle banyak (hanya movie dari sample)
            "guru",        // HLS multi-priority
            "ophim",       // HLS, spesifik Vietnam-sub anime-ish
            "asiacloud",   // sering null
            "hindicast"    // sering null
        )

        /**
         * Embed providers (dari /api/embeds): ["self", "prime"].
         *
         * `self` adalah embed aggregator Rive — biasanya return byse.sx-flowcast-720
         * atau host lain yang berisi link iframe ke player eksternal.
         *
         * `prime` sering return null untuk banyak ID (terbukti dari testing:
         * {"data":null} untuk Fight Club/GoT). Tetap di-loop sebagai fallback.
         */
        private val EMBED_PROVIDERS = listOf("self", "prime")

        private val TORRENT_PROVIDERS = listOf("yts", "tvx")

        /**
         * Prioritas eksekusi untuk embed providers.
         * self lebih reliable (data aktual dari testing).
         */
        private val EMBED_PROVIDER_PRIORITY = mapOf(
            "self" to 100,
            "prime" to 50
        )

        /** Batas iframe yang di-scrape dari embed page untuk avoid spam. */
        private const val MAX_IFRAME_SCRAPE = 10

        // ============================================================
        // [v3] STATIC ENTRY POINTS — untuk PrimeSrcExtractor wrapper
        // ============================================================
        //
        // Class methods `parseDataParams()` dan `invokeEmbedMode()` instance
        // tidak bisa dipanggil dari `PrimeSrcExtractor` (yang gak punya instance
        // PrimeSrcHelper). Solusi: expose via companion-object static wrapper
        // yang delegate ke shared singleton logic.
        //
        // Backward-compat: `invokePrimeSrc()` instance method TETAP ADA dan
        // dipanggil dari `RiveStreamProvider.loadLinks` seperti biasa.

        /** Static wrapper untuk parseDataParams — dipakai oleh PrimeSrcExtractor. */
        @JvmStatic
        internal fun parseDataParamsStatic(data: String, mainUrl: String): PrimeSrcHelper.DataParams? {
            // Karena parseDataParams instance method cuma butuh `this` untuk delegates
            // ke `parseQuery()` helper yang juga private — kita reimplement
            // logic-nya di sini tanpa butuh instance state.
            return try {
                val pathPart: String
                val queryPart: String
                when {
                    data.contains("?") -> {
                        val idx = data.indexOf('?')
                        pathPart = data.substring(0, idx)
                        queryPart = data.substring(idx + 1)
                    }
                    else -> {
                        pathPart = data
                        queryPart = ""
                    }
                }

                val cleanedPath = pathPart
                    .removePrefix(mainUrl).removePrefix("/")
                    .removePrefix(RIVE_BASE).removePrefix("/")

                val typeRegex = "(movie|tv)/(\\d+)".toRegex()
                val match = typeRegex.find(cleanedPath)

                val type: String
                val tmdbId: Int
                if (match != null) {
                    type = match.groupValues[1]
                    tmdbId = match.groupValues[2].toIntOrNull() ?: return null
                } else {
                    val qp = parseQueryStatic(queryPart)
                    type = qp["type"] ?: "movie"
                    tmdbId = qp["id"]?.toIntOrNull() ?: return null
                }

                val qp = parseQueryStatic(queryPart)
                val season = qp["season"]?.toIntOrNull()
                val episode = qp["episode"]?.toIntOrNull()

                DataParams(
                    type = type,
                    tmdbId = tmdbId,
                    isTv = type == "tv",
                    season = season,
                    episode = episode
                )
            } catch (e: Exception) {
                null
            }
        }

        /** Static wrapper untuk invokeEmbedMode — dipakai oleh PrimeSrcExtractor. */
        @JvmStatic
        suspend fun invokeEmbedModeStatic(
            data: String,
            mainUrl: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            // Delegate ke instance method via shared singleton.
            // Kita instantiate sekali dan reuse — gak ada mutable state yang
            // perlu di-share antara calls (semua function private baca dari params).
            return sharedSingleton.invokeEmbedMode(data, mainUrl, subtitleCallback, callback)
        }

        /** Shared singleton untuk static delegation — gak ada state, cuma convenience. */
        @JvmStatic
        private val sharedSingleton: PrimeSrcHelper by lazy { PrimeSrcHelper() }

        /** Static version of parseQuery — pure function, gampang dipindah ke companion. */
        @JvmStatic
        private fun parseQueryStatic(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            return query.split("&")
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx < 0) return@mapNotNull null
                    val key = pair.substring(0, idx)
                    val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    key to value
                }.toMap()
        }
    }

    // ============================================================
    // ENTRY POINT — Dipanggil dari RiveStreamProvider.loadLinks
    // ============================================================
    suspend fun invokePrimeSrc(
        data: String,
        mainUrl: String,
        providerName: String,
        apiKey: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val params = parseDataParams(data, mainUrl) ?: run {
                logError(IllegalArgumentException("Cannot parse data: $data"))
                return false
            }

            var anyInvoked = false

            // 1. Ambil semua HLS/mp4 direct sources (PALING STABIL)
            anyInvoked = anyInvoked or loadVideoSources(params, subtitleCallback, callback)

            // 2. Ambil embed iframe URLs dari /api/embed?provider={self|prime}
            anyInvoked = anyInvoked or loadEmbedSources(params, subtitleCallback, callback)

            // 3. Ambil torrent sources (kalau user pakai torrent client)
            anyInvoked = anyInvoked or loadTorrentSources(params, callback)

            anyInvoked
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    /**
     * Fallback mode: scrape iframe dari embed page Rive.
     *
     * Versi ini sudah handle /embed/agg (watch page yang aggregate semua providers).
     * Sebelumnya hanya scrape page default — yang mungkin tidak punya iframe untuk
     * ID spesifik.
     */
    suspend fun invokeEmbedMode(
        data: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val params = parseDataParams(data, mainUrl) ?: return false
            val embedUrl = buildEmbedPageUrl(params)  // /embed/agg?type=...&id=...

            logError(Exception("[PrimeSrc] invokeEmbedMode scraping: $embedUrl"))

            val response = app.get(embedUrl, headers = baseHeaders(embedUrl)).text
            val document = org.jsoup.Jsoup.parse(response)
            var invoked = false
            var iframeCount = 0

            for (iframe in document.select("iframe[src]")) {
                if (iframeCount >= MAX_IFRAME_SCRAPE) break
                iframeCount++

                val src = iframe.attr("src").let {
                    if (it.startsWith("//")) "https:$it" else it
                }
                if (src.isBlank()) continue

                logError(Exception("[PrimeSrc] invokeEmbedMode found iframe[$iframeCount]: ${src.take(80)}"))

                if (loadExtractorFromUrl(src, embedUrl, subtitleCallback, callback)) {
                    invoked = true
                }
            }

            if (!invoked && iframeCount == 0) {
                logError(Exception("[PrimeSrc] invokeEmbedMode: no iframe found in $embedUrl"))
            }

            invoked
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    // ============================================================
    // VIDEO SOURCE LOADER (HLS / MP4)
    // ============================================================
    private suspend fun loadVideoSources(
        params: DataParams,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seen = mutableSetOf<String>()
        var invoked = false

        for (provider in VIDEO_PROVIDERS) {
            val url = buildVideoProviderUrl(provider, params)
            val resp = try {
                app.get(url, headers = baseHeaders(SCRAPPER_BASE)).parsedSafe<ScrapperResponse>()
            } catch (e: Exception) {
                null
            } ?: continue

            if (resp.error != null) {
                logError(Exception("Scrapper error [$provider]: ${resp.error}"))
                continue
            }

            val data = resp.data ?: continue

            // Emit sources
            data.sources?.forEach { src ->
                val srcUrl = src.url
                if (srcUrl.isNullOrBlank() || srcUrl in seen) return@forEach
                seen.add(srcUrl)
                emitSource(src, srcUrl, callback)
                invoked = true
            }

            // Emit captions (subtitle) kalau ada (contoh: flowcast)
            data.captions?.forEach { cap ->
                val file = cap.file
                if (file.isNullOrBlank()) return@forEach
                subtitleCallback(
                    newSubtitleFile(lang = cap.label ?: "Unknown", url = file)
                )
            }
        }
        return invoked
    }

    /** @param url versi non-null dari src.url yang sudah divalidasi caller */
    private suspend fun emitSource(src: ScrapperSource, url: String, callback: (ExtractorLink) -> Unit) {
        val type = when (src.format?.lowercase()) {
            "hls" -> ExtractorLinkType.M3U8
            "mp4" -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.VIDEO
        }

        // Decode headers dari query string (khusus flowcast URL)
        val flowcastHeaders = decodeFlowcastHeaders(url) ?: emptyMap()

        // [v3.2 FIX B] Default headers untuk SEMUA source — bukan cuma flowcast.
        // Alasan: dari Logcat runtime test (03:56:23.056), ExoPlayer gagal seekTo()
        // dengan error `MediaHTTPConnection.readAt → IOException`. Ini terjadi kalau
        // server tidak terima HTTP Range request tanpa User-Agent yang recognizable
        // sebagai browser. Beberapa CDN/proxy (khususnya Cloudflare-protected) reject
        // request dengan default ExoPlayer User-Agent.
        //
        // Fix: set default User-Agent + Accept header di SEMUA source. Flowcast headers
        // (kalau ada) di-merge sebagai override — flowcast-specific headers menang.
        val defaultHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        val mergedHeaders = defaultHeaders + flowcastHeaders.filterKeys { it != "Referer" }

        callback(
            newExtractorLink(
                source = src.source ?: "Rive",
                // [v3.3 FIX 3] Name include priority kalau ada — supaya user lihat
                // ordering di player UI. Flowcast sering kirim priority (1=primary, 2=fallback).
                name = buildString {
                    append(src.source ?: "Rive")
                    append(" - ")
                    append(src.quality ?: "Auto")
                    src.priority?.let { p -> append(" [p$p]") }
                },
                url = url,
                type = type
            ) {
                // [v3.2 FIX B] Prioritas: flowcast Referer > SCRAPPER_BASE
                this.referer = flowcastHeaders["Referer"] ?: SCRAPPER_BASE
                this.headers = mergedHeaders
                this.quality = parseQuality(src.quality)
            }
        )
    }

    /**
     * Decode parameter `headers={"Referer":"...","Origin":"..."}` dari URL flowcast.
     * Contoh URL:
     *   https://proxy.valhallastream.dpdns.org/proxy?url=...&headers=%7B%22Referer%22%3A...%7D
     *
     * Defensive: skip leading '=' chars untuk handle hypothetical malformed query.
     *
     * [v3.3 FIX 1] Support MULTIPLE formats:
     *   - JSON object: `{"Referer":"...","Origin":"..."}` (primary)
     *   - URL-encoded: `Referer=...&Origin=...` (fallback)
     *
     * [v3.3 FIX 2] Auto-add default `Origin = SCRAPPER_BASE` kalau gak ada di headers.
     * Beberapa Cloudflare-protected flowcast proxy reject request tanpa Origin header.
     */
    private fun decodeFlowcastHeaders(url: String): Map<String, String>? {
        return try {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return null

            val query = url.substring(queryStart + 1)
            val raw = query.split("&")
                .firstOrNull { it.startsWith("headers=") }
                ?: return null

            var value = raw.removePrefix("headers=")
            while (value.startsWith("=")) value = value.substring(1)

            val headersParam = URLDecoder.decode(value, "UTF-8")
            val result = mutableMapOf<String, String>()

            // [v3.3 FIX 1] Try multiple parsing strategies
            val parsed = when {
                // Strategy 1: JSON object format {"key":"value"}
                headersParam.trimStart().startsWith("{") -> {
                    val jsonRegex = "\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"".toRegex()
                    jsonRegex.findAll(headersParam).map { it.groupValues[1] to it.groupValues[2] }.toList()
                }
                // Strategy 2: URL-encoded format key=value&key2=value2
                headersParam.contains("=") -> {
                    headersParam.split("&").mapNotNull { pair ->
                        val idx = pair.indexOf('=')
                        if (idx < 0) return@mapNotNull null
                        URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    }
                }
                else -> emptyList()
            }

            parsed.forEach { (k, v) -> result[k] = v }

            // [v3.3 FIX 2] Auto-add default Origin kalau gak ada (Cloudflare protection)
            if (!result.containsKey("Origin") && !result.containsKey("origin")) {
                result["Origin"] = SCRAPPER_BASE
            }

            result.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQuality(q: Any?): Int {
        return when (q) {
            is Int -> q
            is String -> {
                when {
                    q.contains("2160", ignoreCase = true) -> Qualities.P2160.value
                    q.contains("1440", ignoreCase = true) -> Qualities.P1440.value
                    q.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                    q.contains("720", ignoreCase = true)  -> Qualities.P720.value
                    q.contains("480", ignoreCase = true)  -> Qualities.P480.value
                    q.contains("360", ignoreCase = true)  -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
            }
            else -> Qualities.Unknown.value
        }
    }

    // ============================================================
    // EMBED SOURCE LOADER (iframe URLs) — [PATCHED: lebih robust]
    // ============================================================

    /**
     * Load embed iframe URLs dari /api/embed?provider={self|prime}.
     *
     * [PATCHED] Perubahan dari versi sebelumnya:
     *  - Logging per-host supaya visibility di Logcat lebih jelas.
     *  - Loop semua sources per provider (sebelumnya implicit short-circuit).
     *  - Track per-provider success untuk return value yang lebih akurat.
     *
     * Flow:
     *  1. Hit /api/embeds untuk list provider (saat ini hard-coded: ["self","prime"])
     *  2. Untuk tiap provider, hit /api/embed?provider=X&id=...[&season=&episode=]
     *  3. Parse { data: { sources: [{ host, link }] } }
     *  4. Panggil CloudStream core loadExtractor() untuk setiap link iframe.
     *     loadExtractor() sudah handle puluhan embed host (bysekoze, vidsrc, dll).
     */
    private suspend fun loadEmbedSources(
        params: DataParams,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var invoked = false

        // Sort providers by priority (self > prime)
        val sortedProviders = EMBED_PROVIDERS.sortedByDescending {
            EMBED_PROVIDER_PRIORITY[it] ?: 0
        }

        for (provider in sortedProviders) {
            val url = buildEmbedProviderUrl(provider, params)

            val resp = try {
                app.get(url, headers = baseHeaders(SCRAPPER_BASE)).parsedSafe<ScrapperEmbedResponse>()
            } catch (e: Exception) {
                logError(Exception("Embed provider [$provider] request error: ${e.message}"))
                null
            } ?: continue

            if (resp.error != null) {
                logError(Exception("Scrapper embed error [$provider]: ${resp.error}"))
                continue
            }

            val data = resp.data
            if (data == null) {
                // Provider return null — biasanya artinya ID ini tidak ada di provider tsb
                continue
            }

            val sources = data.sources ?: continue
            if (sources.isEmpty()) continue

            logError(Exception("[PrimeSrc] embed provider [$provider] returned ${sources.size} iframe(s)"))

            // [PATCHED] Loop semua sources — sebelumnya mungkin short-circuit
            sources.forEachIndexed { idx, src ->
                val link = src.link ?: return@forEachIndexed
                val host = src.host ?: "unknown"

                logError(Exception("[PrimeSrc]   embed[$idx] host=$host link=${link.take(80)}"))

                // Delegate ke CloudStream core extractor.
                // Core extractor punya daftar puluhan extractor (bysekoze, vidsrc, dll)
                // yang akan otomatis dipilih berdasarkan host URL.
                if (loadExtractorFromUrl(link, url, subtitleCallback, callback)) {
                    invoked = true
                }
            }
        }

        return invoked
    }

    private suspend fun loadExtractorFromUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // [v3.1 FIX] Pakai top-level function `loadExtractor()` dari
            // com.lagradost.cloudstream3.utils (ExtractorApi.kt).
            // Sebelumnya pakai `utils.loadExtractor()` (object call) — itu unresolved
            // di build environment plugin karena `utils` object tidak ter-import via wildcard
            // di beberapa versi CloudStream. Top-level function lebih reliable.
            loadExtractor(url, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    // ============================================================
    // TORRENT SOURCE LOADER
    // ============================================================
    private suspend fun loadTorrentSources(
        params: DataParams,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var invoked = false

        for (provider in TORRENT_PROVIDERS) {
            val url = buildTorrentProviderUrl(provider, params)
            val resp = try {
                app.get(url, headers = baseHeaders(SCRAPPER_BASE)).parsedSafe<TorrentResponse>()
            } catch (e: Exception) {
                null
            } ?: continue

            if (resp.error != null) {
                logError(Exception("Scrapper torrent error [$provider]: ${resp.error}"))
                continue
            }

            val torrentData = resp.data ?: continue
            val sources = torrentData.sources ?: continue

            sources.forEach { t ->
                val actualUrl = t.magnetUrl ?: t.url ?: return@forEach

                // [v3.2 FIX D] Explicit type detection untuk magnet links.
                // Sebelumnya: `type = null` (INFER_TYPE) — berharap upstream
                // `inferTypeFromUrl()` auto-detect. TAPI dari Logcat runtime test,
                // `Mp4PreviewGenerator.setDataSource` fail saat ada link non-video
                // yang lolos ke ExoPlayer dengan type yang salah.
                //
                // Fix: Deteksi manual. Magnet link SELALU type MAGNET (bukan INFER_TYPE),
                // supaya ExoPlayer tidak coba process sebagai video stream.
                val torrentType = when {
                    actualUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                    actualUrl.endsWith(".torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                    else -> ExtractorLinkType.TORRENT  // default fallback
                }

                callback(
                    newExtractorLink(
                        source = "Torrent - ${torrentData.provider ?: provider}",
                        name = "${t.name ?: "Unknown"} [${t.quality ?: "?"}]",
                        url = actualUrl,
                        type = torrentType
                    ) {
                        this.referer = SCRAPPER_BASE
                        this.quality = parseQuality(t.quality)
                    }
                )
                invoked = true
            }
        }
        return invoked
    }

    // ============================================================
    // URL BUILDERS
    // ============================================================
    private fun buildVideoProviderUrl(provider: String, p: DataParams): String {
        val sb = StringBuilder("$SCRAPPER_BASE/api/provider?provider=$provider&id=${p.tmdbId}")
        if (p.isTv && p.season != null && p.episode != null) {
            sb.append("&season=${p.season}&episode=${p.episode}")
        }
        return sb.toString()
    }

    private fun buildEmbedProviderUrl(provider: String, p: DataParams): String {
        val sb = StringBuilder("$SCRAPPER_BASE/api/embed?provider=$provider&id=${p.tmdbId}")
        if (p.isTv && p.season != null && p.episode != null) {
            sb.append("&season=${p.season}&episode=${p.episode}")
        }
        return sb.toString()
    }

    private fun buildTorrentProviderUrl(provider: String, p: DataParams): String {
        val sb = StringBuilder("$SCRAPPER_BASE/api/torrent?provider=$provider&id=${p.tmdbId}")
        if (p.isTv && p.season != null && p.episode != null) {
            sb.append("&season=${p.season}&episode=${p.episode}")
        }
        return sb.toString()
    }

    private fun buildEmbedPageUrl(p: DataParams): String {
        // /embed/agg adalah watch page Rive yang otomatis aggregate semua providers
        // dan render iframe player. Lebih reliable untuk scraping iframe langsung
        // daripada page default.
        val sb = StringBuilder("$RIVE_BASE/embed/agg?type=${p.type}&id=${p.tmdbId}")
        if (p.isTv && p.season != null && p.episode != null) {
            sb.append("&season=${p.season}&episode=${p.episode}")
        }
        return sb.toString()
    }

    // ============================================================
    // PARSING & HELPERS
    // ============================================================
    /**
     * DataParams — exposed dengan visibility internal supaya bisa di-test
     * dari package yang sama. Backward-compat: semua pemanggilan existing
     * masih bisa karena tetep di-scope class ini.
     */
    internal data class DataParams(
        val type: String,           // "movie" | "tv"
        val tmdbId: Int,
        val isTv: Boolean,
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun parseDataParams(data: String, mainUrl: String): DataParams? {
        // Accept formats:
        //   https://www.rivestream.app/movie/550           (legacy, 404 di server asli)
        //   https://www.rivestream.app/tv/1399?season=1&episode=1
        //   movie/550
        //   /detail?id=1399&type=tv&season=1&episode=1      (format aktual)
        //
        // [v3] Delegate ke static wrapper supaya PrimeSrcExtractor bisa reuse tanpa duplikasi.
        return parseDataParamsStatic(data, mainUrl)
    }

    private fun baseHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to SCRAPPER_BASE,
            "sec-ch-ua" to "\"Chromium\";v=\"139\", \"Not;A=Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-site" to "same-origin",
            "sec-fetch-mode" to "cors",
            "sec-fetch-dest" to "empty"
        )
    }

    // ============================================================
    // DATA CLASSES — JSON SCHEMAS (RESPON AKTUAL DARI SERVER)
    // ============================================================

    /** Wrapper umum: { "data": ... } | { "data": null } | { "error": "..." } */
    private data class ScrapperResponse(
        @JsonProperty("data")  val data:  ScrapperData?,
        @JsonProperty("error") val error: String?
    )

    private data class ScrapperData(
        @JsonProperty("sources")  val sources:  List<ScrapperSource>?,
        @JsonProperty("captions") val captions: List<ScrapperCaption>?
    )

    /**
     * Source HLS/mp4 — schema aktual:
     *   primevids: { quality:"ngcloud", url:"https://...m3u8", source:"PrimeVids", format:"hls" }
     *   flowcast:  { quality:720,       url:"https://...?headers=...", source:"FlowCast", size:"...", format:"mp4" }
     *   guru:      { url:"https://...txt?t=...", quality:"HLS 1", priority:1, source:"Guru", format:"hls" }
     *   ophim:     { quality:"HLS", source:"#Hà Nội (Vietsub) (Tập 01)", url:"https://...m3u8", format:"hls" }
     */
    private data class ScrapperSource(
        @JsonProperty("url")      val url:      String?,
        @JsonProperty("quality")  val quality:  Any?,    // Bisa Int (720) atau String ("ngcloud"/"HLS 1")
        @JsonProperty("source")   val source:   String?,
        @JsonProperty("format")   val format:   String?,  // "hls" | "mp4"
        @JsonProperty("size")     val size:     String?,
        @JsonProperty("priority") val priority: Int?
    )

    /**
     * Subtitle — schema aktual:
     *   { label:"Indonesian - FlowCast", file:"https://...srt?Policy=...&Signature=..." }
     */
    private data class ScrapperCaption(
        @JsonProperty("label") val label: String?,
        @JsonProperty("file")  val file:  String?
    )

    /** Embed response: { data: { sources: [{ host, link }] } } | { error: "..." } */
    private data class ScrapperEmbedResponse(
        @JsonProperty("data")  val data:  ScrapperEmbedData?,
        @JsonProperty("error") val error: String?
    )

    private data class ScrapperEmbedData(
        @JsonProperty("sources") val sources: List<ScrapperEmbedSource>?
    )

    /**
     * Embed source — schema aktual dari /api/embed?provider=self:
     *   { host:"byse.sx-flowcast-720", link:"https://bysekoze.com/e/oinmw6oxpq9r" }
     *
     * host adalah identifier (bukan domain asli) yang dipakai Rive UI untuk display.
     * link adalah URL iframe asli yang akan di-load oleh player.
     */
    private data class ScrapperEmbedSource(
        @JsonProperty("host") val host: String?,
        @JsonProperty("link") val link: String?
    )

    /**
     * Torrent response — schema aktual:
     *   yts: { provider:"yts", sources:[{ name, url:"https://...torrent", quality:"720p", type:"bluray", ... }] }
     *   tvx: { provider:"tvx", sources:[{ name, title, magnet_url:"magnet:?xt=...", quality:"2160p", ... }] }
     */
    private data class TorrentResponse(
        @JsonProperty("data")  val data:  TorrentData?,
        @JsonProperty("error") val error: String?
    )

    private data class TorrentData(
        @JsonProperty("provider") val provider: String?,
        @JsonProperty("sources")  val sources:  List<TorrentSource>?
    )

    private data class TorrentSource(
        @JsonProperty("name")          val name:          String?,
        @JsonProperty("title")         val title:         String?,
        @JsonProperty("url")           val url:           String?,    // .torrent file URL (yts)
        @JsonProperty("magnet_url")    val magnetUrl:     String?,    // magnet URI (tvx)
        @JsonProperty("quality")       val quality:       String?,
        @JsonProperty("type")          val type:          String?,
        @JsonProperty("video_codec")   val videoCodec:    String?,
        @JsonProperty("seeds")         val seeds:         Int?,
        @JsonProperty("peers")         val peers:         Int?,
        @JsonProperty("size")          val size:          String?,
        @JsonProperty("size_bytes")    val sizeBytes:     String?,    // kadang int, kadang string
        @JsonProperty("date_uploaded") val dateUploaded:  Long?
    )
}

/**
 * [v3] Proper `ExtractorApi` wrapper untuk PrimeSrc — opsional, tidak menggantikan
 * `invokePrimeSrc()`/`invokeEmbedMode()` API yang lama. Ini cuma alternatif kalau
 * lo mau PrimeSrc auto-dispatch lewat `utils.loadExtractor(url, ...)` tanpa harus
 * panggil manual dari `RiveStreamProvider.loadLinks`.
 *
 * Cara pakai (di `Provider.init()` atau static block):
 *   ```kotlin
 *   // Manual registration kalau reflection plugin loader lo handle extractor
 *   // list sendiri. Kalau pakai standard CloudStream loader, biasanya otomatis
 *   // via reflection — gak perlu apa-apa.
 *   extractorApis.add(PrimeSrcExtractor())
 *   ```
 *
 * Pola sesuai `ExtractorApi.kt` referensi:
 *   - `abstract class ExtractorApi` — semua `name`/`mainUrl`/`requiresReferer` adalah `abstract val`
 *   - `getUrl(url, referer, subtitleCallback, callback)` — 4-arg overload yang recommended
 *   - `requiresReferer = false` karena PrimeSrc self-hosted (gak butuh referer khusus)
 *
 * Catatan: PrimeSrcExtractor cuma scrape iframe dari /embed/agg (mode fallback).
 * Untuk mode primary (HLS/mp4 + embed providers), tetap pakai `invokePrimeSrc()`
 * langsung dari RiveStreamProvider.loadLinks karena itu butuh akses ke `app`, `utils`,
 * `subtitleCallback`, `callback` parameter dari scope MainAPI.
 */
class PrimeSrcExtractor : ExtractorApi() {
    override val name = "PrimeSrc"
    override val mainUrl = "https://www.rivestream.app"
    override val requiresReferer = false

    /**
     * Dispatch URL apapun ke PrimeSrc flow kalau URL match Rive domain.
     * Pattern ini konsisten dengan extractor lain di upstream (misal Fembed, Vidmoly).
     */
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Kita gak tahu `tmdbId`/`season`/`episode` dari URL iframe mentah,
        // jadi langsung scrape iframe dari /embed/agg untuk page root.
        // Real-world: kalau URL sudah include query params (seperti dari embed provider),
        // kita forward ke invokeEmbedMode dengan best-effort parse.
        try {
            // Try parse URL sebagai format Rive (movie/550 atau detail?id=1399&type=tv)
            val params = PrimeSrcHelper.parseDataParamsStatic(url, mainUrl)
            if (params != null) {
                PrimeSrcHelper.invokeEmbedModeStatic(
                    url, mainUrl, subtitleCallback, callback
                )
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    /** Optional: kalau lo mau skip extraction kalau URL bukan domain Rive. */
    override fun getExtractorUrl(id: String): String = id
}
