package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder

/**
 * PrimeSrc Helper - Berdasarkan Analisis Aktual Endpoint (lihat CHANGELOG)
 *
 * FIXED (dibanding versi "-new" sebelumnya):
 *  - SubtitleFile dulu dipanggil dengan parameter `label` yang tidak pernah ada
 *    di class SubtitleFile (cuma ada lang/url/headers) -> tidak akan compile.
 *    Sekarang pakai newSubtitleFile(lang = ..., url = ...).
 *  - src.url bertipe String? tapi dipakai di beberapa tempat yang butuh String
 *    non-null (decodeFlowcastHeaders, newExtractorLink) -> tidak akan compile.
 *    Sekarang di-null-check sekali lalu url non-null diteruskan sebagai parameter.
 *  - loadEmbedSources() sebelumnya salah pakai data class ScrapperResponse/
 *    ScrapperSource (skema video source: url/quality/source/format) padahal
 *    respons endpoint /api/embed skemanya beda (host/link). Field `src.link`
 *    dipanggil padahal ScrapperSource tidak punya field itu -> tidak akan
 *    compile. Sekarang pakai ScrapperEmbedResponse/ScrapperEmbedSource yang
 *    memang sudah didefinisikan tapi belum pernah dipakai.
 *  - Ditambahkan pengecekan wrapper {"error": "..."} sesuai temuan testing:
 *    server bisa return HTTP 200 dengan body error, bukan 4xx.
 *
 * Data endpoint aktual (hasil inspect langsung ke server):
 *  - Provider list: https://scrapper.rivestream.app/api/providers
 *  - Source (movie): https://scrapper.rivestream.app/api/provider?provider=<p>&id=<tmdbId>
 *  - Source (tv):    https://scrapper.rivestream.app/api/provider?provider=<p>&id=<tmdbId>&season=<n>&episode=<m>
 *  - Embed:          https://scrapper.rivestream.app/api/embed?provider=<self|prime>&id=<tmdbId>[&season=&episode=]
 *  - Torrent:        https://scrapper.rivestream.app/api/torrent?provider=<yts|tvx>&id=<tmdbId>[&season=&episode=]
 *
 * Tidak ada secret key/salt mechanism di server asli (lihat CHANGELOG untuk detail
 * kenapa versi lama yang punya SALT_ARRAY/generateSecretKey/decryptVoePayload
 * dianggap fabricated dan sengaja tidak dipertahankan di sini).
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

        private val EMBED_PROVIDERS = listOf("self", "prime")

        private val TORRENT_PROVIDERS = listOf("yts", "tvx")
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

            // 2. Ambil embed iframe URLs (untuk user WebView/external extractor)
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
     * Fallback mode: kalau invokePrimeSrc gagal, scrape iframe dari embed page Rive.
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

            // Fetch halaman embed Rive, scrape iframe
            val response = app.get(embedUrl, headers = baseHeaders(embedUrl)).text
            val document = org.jsoup.Jsoup.parse(response)
            var invoked = false

            for (iframe in document.select("iframe[src]")) {
                val src = iframe.attr("src").let {
                    if (it.startsWith("//")) "https:$it" else it
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
    private fun emitSource(src: ScrapperSource, url: String, callback: (ExtractorLink) -> Unit) {
        val type = when (src.format?.lowercase()) {
            "hls" -> ExtractorLinkType.M3U8
            "mp4" -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.VIDEO
        }

        // Decode headers dari query string (khusus flowcast URL)
        val headers = decodeFlowcastHeaders(url) ?: emptyMap()

        callback(
            newExtractorLink(
                source = src.source ?: "Rive",
                name = "${src.source ?: "Rive"} - ${src.quality ?: "Auto"}",
                url = url,
                type = type
            ) {
                this.referer = headers["Referer"] ?: SCRAPPER_BASE
                // Attach headers lain kalau ada (Origin, dll)
                this.headers = headers.filterKeys { it != "Referer" }
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

            // Validate JSON before parsing
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
    // EMBED SOURCE LOADER (iframe URLs)
    // ============================================================
    private suspend fun loadEmbedSources(
        params: DataParams,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var invoked = false

        for (provider in EMBED_PROVIDERS) {
            val url = buildEmbedProviderUrl(provider, params)
            // FIX: skema /api/embed adalah {host, link}, bukan skema video source
            // (url/quality/source/format) — harus pakai ScrapperEmbedResponse.
            val resp = try {
                app.get(url, headers = baseHeaders(SCRAPPER_BASE)).parsedSafe<ScrapperEmbedResponse>()
            } catch (e: Exception) {
                null
            } ?: continue

            if (resp.error != null) {
                logError(Exception("Scrapper embed error [$provider]: ${resp.error}"))
                continue
            }

            val sources = resp.data?.sources ?: continue

            sources.forEach { src ->
                val link = src.link ?: return@forEach
                if (loadExtractorFromUrl(link, url, subtitleCallback, callback)) {
                    invoked = true
                }
            }
        }
        return invoked
    }

    private fun loadExtractorFromUrl(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            com.lagradost.cloudstream3.utils.loadExtractor(url, referer, subtitleCallback, callback)
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

            val sources = resp.data?.sources ?: continue

            sources.forEach { t ->
                val actualUrl = t.magnetUrl ?: t.url ?: return@forEach

                callback(
                    newExtractorLink(
                        source = "Torrent - ${t.provider ?: resp.data.provider ?: provider}",
                        name = "${t.name ?: "Unknown"} [${t.quality ?: "?"}]",
                        url = actualUrl,
                        type = ExtractorLinkType.TORRENT
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
        val sb = StringBuilder("$RIVE_BASE/embed/agg?type=${p.type}&id=${p.tmdbId}")
        if (p.isTv && p.season != null && p.episode != null) {
            sb.append("&season=${p.season}&episode=${p.episode}")
        }
        return sb.toString()
    }

    // ============================================================
    // PARSING & HELPERS
    // ============================================================
    private data class DataParams(
        val type: String,           // "movie" | "tv"
        val tmdbId: Int,
        val isTv: Boolean,
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun parseDataParams(data: String, mainUrl: String): DataParams? {
        // Accept formats:
        //   https://www.rivestream.app/movie/550           (legacy, sudah 404 di server asli)
        //   https://www.rivestream.app/tv/1399?season=1&episode=1
        //   movie/550
        //   /detail?id=1399&type=tv&season=1&episode=1      (format aktual yang dipakai)
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

            // Detect legacy /movie/{id} or /tv/{id} path
            val typeRegex = "(movie|tv)/(\\d+)".toRegex()
            val match = typeRegex.find(cleanedPath)

            val type: String
            val tmdbId: Int
            if (match != null) {
                type = match.groupValues[1]
                tmdbId = match.groupValues[2].toIntOrNull() ?: return null
            } else {
                // Format aktual: parse dari query (?id=...&type=...)
                val qp = parseQuery(queryPart)
                type = qp["type"] ?: "movie"
                tmdbId = qp["id"]?.toIntOrNull() ?: return null
            }

            val qp = parseQuery(queryPart)
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
