package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName

class PrimeSrcHelper {

    companion object {
        // Domain API Gateway Utama hasil audit pipa jaringan universal
        private const val SCRAPER_BASE = "https://scrapper.rivestream.app/api/provider"
        // API Key statis universal yang terbukti tembus status 200 OK
        private const val AUDITED_API_KEY = "d64117f26031a428449f102ced3aba73"
        
        // Daftar provider hasil verifikasi lapangan (PrimeVids terbukti menghasilkan link)
        private val TARGET_PROVIDERS = listOf("primevids", "flow", "speed", "vidsrc")
    }

    suspend fun invokePrimeSrc(
        data: String, 
        mainUrl: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?season=")
        val cleanId = cleanData.substringBefore("?").substringAfterLast("/")
        var linksFound = 0

        // Headers emulasi aman agar menyerupai request Python/Browser yang valid
        val standardHeaders = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 11; OPPO CPH2235) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Mobile Safari/537.36",
            "Origin" to "https://rivestream.app",
            "Referer" to "https://rivestream.app/"
        )

        // Lakukan iterasi langsung ke core scraper API tanpa melewati proxy website frontend
        TARGET_PROVIDERS.forEach { service ->
            try {
                // Merakit URL secara deterministik sesuai manifes jaringan Next.js
                val finalApiUrl = if (isMovie) {
                    "$SCRAPER_BASE?provider=$service&id=$cleanId&api_key=$AUDITED_API_KEY"
                } else {
                    val season = cleanData.substringAfter("?season=").substringBefore("&")
                    val episode = cleanData.substringAfter("&episode=")
                    "$SCRAPER_BASE?provider=$service&id=$cleanId&season=$season&episode=$episode&api_key=$AUDITED_API_KEY"
                }

                // Eksekusi HTTP GET request dengan batasan timeout aman
                val response = app.get(finalApiUrl, headers = standardHeaders, timeout = 10).text
                val parsedData = tryParseJson<BackendFetchResponse>(response) ?: return@forEach
                val sources = parsedData.data?.sources ?: return@forEach

                // Ekstraksi subtitel jika disediakan oleh respons API
                parsedData.data.captions?.forEach { caption ->
                    val captionUrl = caption.file ?: return@forEach
                    val captionLabel = caption.label ?: "Subtitle"
                    subtitleCallback(SubtitleFile(captionLabel, captionUrl))
                }

                // Injeksi tautan video langsung ke core player Cloudstream
                for (source in sources) {
                    val streamUrl = source.url ?: continue
                    val qualityName = source.quality?.toString() ?: "AUTO"
                    val sourceLabel = source.source ?: "RiveStream"
                    val displayName = "$sourceLabel - $qualityName"

                    callback(newExtractorLink(
                        source = providerName,
                        name = displayName,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(qualityName)
                        this.headers = mapOf("Origin" to mainUrl)
                    })
                    linksFound++
                }
            } catch (e: Exception) {
                // Mencegah kegagalan satu provider merusak perulangan provider lainnya
                e.printStackTrace()
            }
        }

        return linksFound > 0
    }
}

// =======================================================
// DATA SKEMA JSON YANG DISINKRONKAN DENGAN PAYLOAD RESPONS
// =======================================================
data class BackendFetchResponse(@JsonProperty("data") val data: BackendData?)
data class BackendData(
    @JsonProperty("sources") val sources: List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)
data class BackendSource(
    @JsonProperty("quality") val quality: Any?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("format") val format: String?
)
data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file") val file: String?
)
