package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * PrimeSrc Helper — Rewrite berdasarkan hasil verifikasi endpoint langsung.
 *
 * Endpoint aktual:
 *   Video sources:  /api/provider?provider=<p>&id=<tmdbId>[&season=&episode=]
 *   Embed sources:  /api/embed?provider=self&id=<tmdbId>[&season=&episode=]
 *   Torrent sources:/api/torrent?provider=<p>&id=<tmdbId>[&season=&episode=]
 *   Provider list:  /api/providers → ["primevids","flowcast","asiacloud","hindicast","guru","ophim"]
 *
 * Perubahan dari versi sebelumnya:
 *  - Hapus semua penggunaan `parsedSafe<T>()` (tidak ada di CS3 SDK)
 *  - Ganti dengan try-catch + `parsed<T>()`
 *  - Hapus hardcoded `Qualities.*.value` (Qualities enum punya .value, tapi
 *    untuk quality string seperti "ngcloud"/"tcloud"/"ipcloud" perlu mapping manual)
 *  - SubtitleFile langsung pakai constructor `SubtitleFile(lang, url)`
 *  - Embed self hanya dipanggil untuk provider "self" (prime selalu null)
 *  - Flowcast quality map dari Int langsung ke Qualities
 *  - Primevids quality string ("ngcloud","tcloud","ipcloud") → Unknown
 *  - Referer untuk primevids HLS pakai RIVE_BASE
 *  - Referer untuk flowcast MP4 pakai dari headers proxy atau fallback 123movienow.cc
 */
class PrimeSrcHelper {

    companion object {
        private const val SCRAPPER_BASE = "https://scrapper.rivestream.app"
        private const val RIVE_BASE     = "https://www.rivestream.app"

        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

        // Provider video utama — diurutkan dari yang paling stabil
        private val VIDEO_PROVIDERS = listOf(
            "primevids",   // HLS, 3 mirror (tcloud/ipcloud/ngcloud) — PALING STABIL
            "flowcast"     // MP4 multi-quality + subtitle
            // asiacloud, hindicast, guru, ophim — sering null, tidak di-loop
            // untuk menjaga performa; kalau mau ditambahkan, silakan.
        )

        // Embed: hanya "self" yang return data; "prime" selalu null
        private val EMBED_PROVIDERS = listOf("self")

        // Torrent providers
        private val TORRENT_PROVIDERS = listOf("yts", "tvx")
    }

    // ============================================================
    // ENTRY POINTS
    // ============================================================

    /**
     * Load semua video sources (HLS/MP4 langsung dari scrapper).
     * Ini adalah sumber utama yang paling stabil.
     */
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

            // 1. Video sources langsung (HLS/mp4) — prioritas utama
            anyInvoked = anyInvoked or loadVideoSources(params, subtitleCallback, callback)

            // 2. Embed sources (iframe via bysekoze dll) — fallback
            anyInvoked = anyInvoked or loadEmbedSources(params, subtitleCallback, callback)

            // 3. Torrent — opsional
            anyInvoked = anyInvoked or loadTorrentSources(params, callback)

            anyInvoked
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    /**
     * Fallback: scrape iframe dari halaman embed aggregator Rive.
     */
    suspend fun invokeEmbedMode(
        data: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val params = parseDataParams(data, mainUrl) ?: return false
            val embedUrl = buildEmbedPageUrl(params)

            val response = app.get(embedUrl, headers = baseHeaders(embedUrl)).text
            val document = org.jsoup.Jsoup.parse(response)
            var invoked = false

            for (iframe in document.select("iframe[src]")) {
                val src = iframe.attr("src").let {
                    if (it.startsWith("//")) "https:$it"
                    else if (!it.startsWith("http")) "$RIVE_BASE$it"
                    else it
                }
                if (src.isNotBlank() && loadExtractorFromUrl(src, embedUrl, subtitleCallback, callback)) {
                    invoked = true
                }
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
        val seenUrls = mutableSetOf<String>()
        var invoked = false

        for (provider in VIDEO_PROVIDERS) {
            val url = buildVideoProviderUrl(provider, params)
            val resp: ScrapperResponse

            try {
                resp = app.get(url, headers = baseHeaders(SCRAPPER_BASE)).parsed()
            } catch (e: Exception) {
                logError(Exception("Scrapper error [$provider]: ${e.message}"))
                continue
            }

            // Cek wrapper error dari server (HTTP 200 dgn body error)
            if (resp.error != null) {
                logError(Exception("Scrapper error [$provider]: ${resp.error}"))
                continue
            }

            val data = resp.data ?: continue

            // --- EMIT VIDEO SOURCES ---
            data.sources?.forEach { src ->
                val srcUrl = src.url ?: return@forEach
                if (srcUrl in seenUrls) return@forEach
                seenUrls.add(srcUrl)

                val isHls = src.format?.lowercase() == "hls"
                val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                // Tentukan referer berdasarkan provider
                val referer = when (provider) {
                    "flowcast" -> decodeFlowcastReferer(srcUrl) ?: RIVE_BASE
                    else       -> RIVE_BASE
                }

                // Decode extra headers dari URL flowcast
                val extraHeaders = if (provider == "flowcast") {
                    decodeFlowcastHeaders(srcUrl) ?: emptyMap()
                } else emptyMap()

                val qualityValue = parseQuality(src.quality)

                callback(
                    newExtractorLink(
                        source = src.source ?: provider.capitalize(),
                        name   = "${src.source ?: provider.capitalize()} - ${qualityLabel(src.quality)}",
                        url    = srcUrl,
                        type   = type
                    ) {
                        this.referer = referer
                        this.quality = qualityValue
                        this.headers = extraHeaders + mapOf(
                            "User-Agent" to USER_AGENT,
                            "Origin" to SCRAPPER_BASE
                        )
                    }
                )
                invoked = true
            }

            // --- EMIT SUBTITLES (khusus flowcast) ---
            data.captions?.forEach { cap ->
                val file = cap.file ?: return@forEach
                subtitleCallback(
                    SubtitleFile(lang = cap.label ?: "Unknown", url = file)
                )
            }
        }
        return invoked
    }

    // ============================================================
    // EMBED SOURCE LOADER
    // ============================================================

    private suspend fun loadEmbedSources(
        params: DataParams,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var invoked = false

        for (provider in EMBED_PROVIDERS) {
            val url = buildEmbedProviderUrl(provider, params)
            val resp: ScrapperEmbedResponse

            try {
                resp = app.get(url, headers = baseHeaders(SCRAPPER_BASE)).parsed()
            } catch (e: Exception) {
                logError(Exception("Scrapper embed error [$provider]: ${e.message}"))
                continue
            }

            if (resp.error != null) {
                logError(Exception("Scrapper embed error [$provider]: ${resp.error}"))
                continue
            }

            val sources = resp.data?.sources ?: continue

            for (src in sources) {
                val link = src.link ?: continue
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
            val resp: TorrentResponse

            try {
                resp = app.get(url, headers = baseHeaders(SCRAPPER_BASE)).parsed()
            } catch (e: Exception) {
                logError(Exception("Scrapper torrent error [$provider]: ${e.message}"))
                continue
            }

            if (resp.error != null) {
                logError(Exception("Scrapper torrent error [$provider]: ${resp.error}"))
                continue
            }

            val torrentData = resp.data ?: continue
            val sources = torrentData.sources ?: continue

            for (t in sources) {
                val actualUrl = t.magnetUrl ?: t.url ?: continue

                callback(
                    newExtractorLink(
                        source = "Torrent - ${torrentData.provider ?: provider}",
                        name   = "${t.name ?: t.title ?: "Unknown"} [${t.quality ?: "?"}]",
                        url    = actualUrl,
                        type   = ExtractorLinkType.TORRENT
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
    // QUALITY PARSING
    // ============================================================

    /**
     * Parse quality dari berbagai format yang dikembalikan server:
     * - Int: 720, 480, 360
     * - String: "ngcloud", "tcloud", "ipcloud", "HLS", "HLS 1"
     * - null
     */
    private fun parseQuality(q: Any?): Int {
        return when (q) {
            is Int -> {
                // Map ke Qualities terdekat
                when {
                    q >= 2160 -> Qualities.P2160.value
                    q >= 1440 -> Qualities.P1440.value
                    q >= 1080 -> Qualities.P1080.value
                    q >= 720  -> Qualities.P720.value
                    q >= 480  -> Qualities.P480.value
                    q >= 360  -> Qualities.P360.value
                    q >= 240  -> Qualities.P240.value
                    q >= 144  -> Qualities.P144.value
                    else      -> Qualities.Unknown.value
                }
            }
            is String -> {
                val s = q.lowercase().trim()
                when {
                    s.contains("2160") || s.contains("4k")   -> Qualities.P2160.value
                    s.contains("1440") || s.contains("2k")   -> Qualities.P1440.value
                    s.contains("1080")                       -> Qualities.P1080.value
                    s.contains("720")                        -> Qualities.P720.value
                    s.contains("480")                        -> Qualities.P480.value
                    s.contains("360")                        -> Qualities.P360.value
                    // String kualitas dari primevids: "ngcloud","tcloud","ipcloud"
                    // Tidak bisa ditentukan resolusinya — default Unknown
                    else                                     -> Qualities.Unknown.value
                }
            }
            else -> Qualities.Unknown.value
        }
    }

    /**
     * Label untuk ditampilkan di UI (nama source - quality).
     */
    private fun qualityLabel(q: Any?): String {
        return when (q) {
            is Int -> "${q}p"
            is String -> {
                val s = q.trim()
                // Primevids quality names — tampilkan sebagai "HD"
                when (s.lowercase()) {
                    "ngcloud", "tcloud", "ipcloud" -> "HD"
                    "hls" -> "Auto"
                    else -> s
                }
            }
            else -> "Auto"
        }
    }

    // ============================================================
    // FLOWCAST HEADER DECODING
    // ============================================================

    /**
     * Decode parameter `headers` dari URL flowcast proxy.
     * Contoh URL:
     *   https://proxy.valhallastream.dpdns.org/proxy?url=...&headers=%7B%22Referer%22%3A...%7D
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

            if (!headersParam.trimStart().startsWith("{")) return null

            val result = mutableMapOf<String, String>()
            val regex = "\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"".toRegex()
            regex.findAll(headersParam).forEach { m ->
                result[m.groupValues[1]] = m.groupValues[2]
            }
            result.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ekstrak referer dari URL flowcast (dari headers parameter).
     * Fallback ke RIVE_BASE jika tidak ada.
     */
    private fun decodeFlowcastReferer(url: String): String? {
        return decodeFlowcastHeaders(url)?.get("Referer")
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
        val sb = StringBuilder("$RIVE_BASE/embed/agg?type=${p.type}&id=${p.tmdbId}")
        if (p.isTv && p.season != null && p.episode != null) {
            sb.append("&season=${p.season}&episode=${p.episode}")
        }
        return sb.toString()
    }

    // ============================================================
    // DATA PARAMS PARSING
    // ============================================================

    private data class DataParams(
        val type: String,      // "movie" | "tv"
        val tmdbId: Int,
        val isTv: Boolean,
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun parseDataParams(data: String, mainUrl: String): DataParams? {
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

            // Format: /detail?id=XXX&type=YYY (format aktual dari getMainPage/search)
            // atau legacy: /movie/XXX, /tv/XXX
            val qp = parseQuery(queryPart)
            val typeFromQuery = qp["type"]

            val type: String
            val tmdbId: Int

            if (typeFromQuery != null && qp["id"] != null) {
                // Format baru: ?id=...&type=...
                type = typeFromQuery
                tmdbId = qp["id"]!!.toIntOrNull() ?: return null
            } else {
                // Format legacy: /movie/{id} or /tv/{id}
                val typeRegex = "(movie|tv)/(\\d+)".toRegex()
                val match = typeRegex.find(cleanedPath) ?: return null
                type = match.groupValues[1]
                tmdbId = match.groupValues[2].toIntOrNull() ?: return null
            }

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

    private fun parseQuery(query: String): Map<String, String> {
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

    private fun baseHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to SCRAPPER_BASE
        )
    }

    // ============================================================
    // DATA CLASSES — RESPON JSON AKTUAL DARI SERVER
    // ============================================================

    // --- VIDEO SOURCE ---
    private data class ScrapperResponse(
        @JsonProperty("data")  val data:  ScrapperData?,
        @JsonProperty("error") val error: String?
    )

    private data class ScrapperData(
        @JsonProperty("sources")  val sources:  List<ScrapperSource>?,
        @JsonProperty("captions") val captions: List<ScrapperCaption>?
    )

    private data class ScrapperSource(
        @JsonProperty("url")      val url:      String?,
        @JsonProperty("quality")  val quality:  Any?,     // Int (720) atau String ("ngcloud")
        @JsonProperty("source")   val source:   String?,
        @JsonProperty("format")   val format:   String?,  // "hls" | "mp4"
        @JsonProperty("size")     val size:     String?,
        @JsonProperty("priority") val priority: Int?
    )

    private data class ScrapperCaption(
        @JsonProperty("label") val label: String?,
        @JsonProperty("file")  val file:  String?
    )

    // --- EMBED SOURCE ---
    private data class ScrapperEmbedResponse(
        @JsonProperty("data")  val data:  ScrapperEmbedData?,
        @JsonProperty("error") val error: String?
    )

    private data class ScrapperEmbedData(
        @JsonProperty("sources") val sources: List<ScrapperEmbedSource>?
    )

    private data class ScrapperEmbedSource(
        @JsonProperty("host") val host: String?,
        @JsonProperty("link") val link: String?
    )

    // --- TORRENT ---
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
        @JsonProperty("url")           val url:           String?,
        @JsonProperty("magnet_url")    val magnetUrl:     String?,
        @JsonProperty("quality")       val quality:       String?,
        @JsonProperty("type")          val type:          String?,
        @JsonProperty("video_codec")   val videoCodec:    String?,
        @JsonProperty("seeds")         val seeds:         Int?,
        @JsonProperty("peers")         val peers:         Int?,
        @JsonProperty("size")          val size:          String?,
        @JsonProperty("size_bytes")    val sizeBytes:     String?,
        @JsonProperty("date_uploaded") val dateUploaded:  Long?
    )
}
