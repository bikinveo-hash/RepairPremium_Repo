package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

// ─────────────────────────────────────────────────────────────
//  FIX #8 : Konstanta API key dipindah ke sini dan dipakai
//           bersama oleh RiveStreamProvider via companion object
//           RiveStreamProvider. Tidak ada duplikasi lagi.
// ─────────────────────────────────────────────────────────────

class PrimeSrcHelper {

    companion object {
        private const val SCRAPER_BASE = "https://scrapper.rivestream.app/api/provider"

        // Daftar provider yang dicoba secara berurutan
        private val TEST_PROVIDERS = listOf(
            "primevids", "flowcast", "asiacloud", "guru", "ophim", "flow", "speed", "vidsrc"
        )
    }

    // ─────────────────────────────────────────────────────────
    //  Catatan parameter baru: apiKey diterima dari luar
    //  sehingga tidak perlu duplikat konstanta di sini.
    // ─────────────────────────────────────────────────────────
    suspend fun invokePrimeSrc(
        data: String,
        mainUrl: String,
        providerName: String,
        apiKey: String,                               // FIX #8 – diterima dari RiveStreamProvider
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?season=")
        val cleanId = cleanData.substringBefore("?").substringAfterLast("/")
        var linksFound = 0

        val standardHeaders = mapOf(
            "Accept"     to "application/json",
            "User-Agent" to USER_AGENT,              // FIX #6 – pakai konstanta global CS3
            "Origin"     to "https://rivestream.app",
            "Referer"    to "https://rivestream.app/"
        )

        // FIX #6 – Ganti println dengan android.util.Log
        android.util.Log.d("RiveStream", "=== MEMULAI PIPELINE === ID=$cleanId | TIPE=${if (isMovie) "MOVIE" else "TV"}")

        TEST_PROVIDERS.forEach { service ->
            // 1. Rakit URL Final
            val finalApiUrl = if (isMovie) {
                "$SCRAPER_BASE?provider=$service&id=$cleanId&api_key=$apiKey"
            } else {
                val season  = cleanData.substringAfter("?season=").substringBefore("&")
                val episode = cleanData.substringAfter("&episode=")
                "$SCRAPER_BASE?provider=$service&id=$cleanId&season=$season&episode=$episode&api_key=$apiKey"
            }

            android.util.Log.d("RiveStream", "PROVIDER: ${service.uppercase()} | URL: $finalApiUrl")

            try {
                // 2. Eksekusi request
                val response     = app.get(finalApiUrl, headers = standardHeaders, timeout = 10)
                val httpStatus   = response.code
                val rawBody      = response.text

                android.util.Log.d("RiveStream", "HTTP $httpStatus | BODY: $rawBody")

                // 3. Parse JSON
                val parsedData = tryParseJson<BackendFetchResponse>(rawBody)
                if (parsedData == null) {
                    android.util.Log.w("RiveStream", "PARSE GAGAL untuk provider $service")
                    return@forEach
                }

                val sources  = parsedData.data?.sources
                val captions = parsedData.data?.captions

                android.util.Log.d("RiveStream", "SOURCES: ${sources?.size ?: 0} | CAPTIONS: ${captions?.size ?: 0}")

                // 4. Proses subtitle
                // ─────────────────────────────────────────
                //  FIX #1 : Ganti constructor lama SubtitleFile(lang, url)
                //           → newSubtitleFile() (suspend builder resmi)
                // ─────────────────────────────────────────
                captions?.forEach { caption ->
                    val captionUrl   = caption.file  ?: return@forEach
                    val captionLabel = caption.label ?: "Subtitle"
                    subtitleCallback(newSubtitleFile(captionLabel, captionUrl))
                }

                // 5. Proses sumber video
                sources?.forEach { source ->
                    val streamUrl   = source.url     ?: return@forEach
                    val qualityName = source.quality?.toString() ?: "AUTO"
                    val sourceLabel = source.source  ?: "RiveStream"
                    val displayName = "$sourceLabel - $qualityName"

                    // ─────────────────────────────────────
                    //  FIX #2 : Deteksi tipe yang lebih akurat.
                    //           Prioritas: field `format` dari JSON →
                    //           fallback string-match pada URL →
                    //           terakhir INFER_TYPE (auto-detect CS3).
                    // ─────────────────────────────────────
                    val linkType: ExtractorLinkType? = when {
                        source.format?.contains("hls",  ignoreCase = true) == true -> ExtractorLinkType.M3U8
                        source.format?.contains("dash", ignoreCase = true) == true -> ExtractorLinkType.DASH
                        source.format?.contains("mp4",  ignoreCase = true) == true -> ExtractorLinkType.VIDEO
                        streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                        streamUrl.contains(".mpd")  -> ExtractorLinkType.DASH
                        else -> INFER_TYPE           // biarkan CS3 auto-detect dari URL path
                    }

                    callback(newExtractorLink(
                        source = providerName,
                        name   = displayName,
                        url    = streamUrl,
                        type   = linkType
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(qualityName)
                        this.headers = mapOf("Origin" to mainUrl)
                    })
                    linksFound++
                }

            } catch (e: Exception) {
                // FIX #6 – logError() adalah cara resmi CS3 untuk log exception
                logError(e)
            }
        }

        android.util.Log.d("RiveStream", "=== PIPELINE SELESAI | TOTAL LINKS: $linksFound ===")
        return linksFound > 0
    }
}

// ─── Data classes respons backend ───────────────────────────────────────────

data class BackendFetchResponse(
    @JsonProperty("data") val data: BackendData?
)

data class BackendData(
    @JsonProperty("sources")  val sources:  List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)

data class BackendSource(
    @JsonProperty("quality") val quality: Any?,
    @JsonProperty("url")     val url:     String?,
    @JsonProperty("source")  val source:  String?,
    @JsonProperty("format")  val format:  String?   // dipakai sekarang untuk deteksi tipe
)

data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file")  val file:  String?
)
