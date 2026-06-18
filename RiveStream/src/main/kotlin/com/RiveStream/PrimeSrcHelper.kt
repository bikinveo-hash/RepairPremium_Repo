package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName

class PrimeSrcHelper {

    companion object {
        private const val SCRAPER_BASE = "https://scrapper.rivestream.app/api/provider"
        private const val AUDITED_API_KEY = "d64117f26031a428449f102ced3aba73"
        
        private val TEST_PROVIDERS = listOf(
            "primevids", "flowcast", "asiacloud", "guru", "ophim", "flow", "speed", "vidsrc"
        )
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

        val standardHeaders = mapOf(
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 11; OPPO CPH2235) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Mobile Safari/537.36",
            "Origin" to "https://rivestream.app",
            "Referer" to "https://rivestream.app/"
        )

        println("[RIVE_AUDIT] === MEMULAI AUDIT JARINGAN PIPELINE ===")
        println("[RIVE_AUDIT] TARGET ID KONTEN: $cleanId | TIPE: ${if (isMovie) "MOVIE" else "TV"}")

        TEST_PROVIDERS.forEach { service ->
            val finalApiUrl = if (isMovie) {
                "$SCRAPER_BASE?provider=$service&id=$cleanId&api_key=$AUDITED_API_KEY"
            } else {
                val season = cleanData.substringAfter("?season=").substringBefore("&")
                val episode = cleanData.substringAfter("&episode=")
                "$SCRAPER_BASE?provider=$service&id=$cleanId&season=$season&episode=$episode&api_key=$AUDITED_API_KEY"
            }

            println("[RIVE_AUDIT] ----------------------------------------------------")
            println("[RIVE_AUDIT] EVALUASI TOKEN PROVIDER: ${service.uppercase()}")
            println("[RIVE_AUDIT] URL REQUEST: $finalApiUrl")

            try {
                val response = app.get(finalApiUrl, headers = standardHeaders, timeout = 10)
                val httpStatus = response.code
                val rawJsonJsonBody = response.text

                println("[RIVE_AUDIT] HTTP STATUS CODE : $httpStatus")
                println("[RIVE_AUDIT] RAW JSON BODY     : $rawJsonJsonBody")

                val parsedData = tryParseJson<BackendFetchResponse>(rawJsonJsonBody)
                if (parsedData == null) {
                    println("[RIVE_AUDIT] STATUS PARSING    : GAGAL (Bukan format JSON valid)")
                    return@forEach
                }

                val sources = parsedData.data?.sources
                val captions = parsedData.data?.captions

                println("[RIVE_AUDIT] JUMLAH SOURCES    : ${sources?.size ?: 0}")
                println("[RIVE_AUDIT] JUMLAH CAPTIONS   : ${captions?.size ?: 0}")

                captions?.forEach { caption ->
                    val captionUrl = caption.file ?: return@forEach
                    val captionLabel = caption.label ?: "Subtitle"
                    // PERBAIKAN: Mengganti constructor usang dengan fungsi factory pembantu baru
                    [span_6](start_span)subtitleCallback(newSubtitleFile(captionLabel, captionUrl))[span_6](end_span)
                }

                sources?.forEach { source ->
                    val streamUrl = source.url ?: return@forEach
                    val qualityName = source.quality?.toString() ?: "AUTO"
                    val sourceLabel = source.source ?: "RiveStream"
                    val displayName = "$sourceLabel - $qualityName"

                    [span_7](start_span)callback(newExtractorLink([span_7](end_span)
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
                println("[RIVE_AUDIT] EXCEPTION TERJADI : ${e.message}")
                e.printStackTrace()
            }
        }
        
        println("[RIVE_AUDIT] === AUDIT PIPELINE SELESAI (TOTAL LINKS: $linksFound) ===")
        return linksFound > 0
    }
}

data class BackendFetchResponse(
    @JsonProperty("data") val data: BackendData?
)
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
