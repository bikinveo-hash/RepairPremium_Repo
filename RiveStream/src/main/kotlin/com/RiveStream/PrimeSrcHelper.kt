package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import android.util.Base64

class PrimeSrcHelper {

    // Kunci Master yang telah divalidasi dari log peramban asli untuk RiveStream
    private val masterSecretKey = "LTE0NDkzOTE2" 

    suspend fun invokePrimeSrc(
        data: String, 
        mainUrl: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanId = data.substringBefore("?").substringAfterLast("/")
        var linksFound = 0

        val standardHeaders = mapOf(
            "Host" to "www.rivestream.app",
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        )

        try {
            // Ambil daftar layanan aktif dari peladen
            val servicesListUrl = "$mainUrl/api/backendfetch?requestID=VideoProviderServices&secretKey=rive&proxyMode=undefined"
            val servicesResponse = app.get(servicesListUrl, headers = standardHeaders).text
            val parsedServices = tryParseJson<BackendServicesResponse>(servicesResponse)
            val activeServices = parsedServices?.data ?: listOf("primevids", "flowcast", "asiacloud", "guru", "ophim")

            activeServices.forEach { service ->
                try {
                    val proxyMode = if (service == "primevids") "undefined" else "noProxy"
                    val finalApiUrl = "$mainUrl/api/backendfetch?requestID=movieVideoProvider&id=$cleanId&service=$service&secretKey=$masterSecretKey&proxyMode=$proxyMode"

                    val response = app.get(finalApiUrl, headers = standardHeaders).text
                    val parsedData = tryParseJson<BackendFetchResponse>(response) ?: return@forEach
                    
                    // Callback subtitle jika ada
                    parsedData.data?.captions?.forEach { 
                        subtitleCallback(SubtitleFile(it.label ?: "Sub", it.file ?: "")) 
                    }

                    // Loop sumber video menggunakan standar newExtractorLink
                    parsedData.data?.sources?.forEach { source ->
                        val streamUrl = source.url ?: return@forEach
                        
                        callback(newExtractorLink(
                            source = providerName,
                            name = "$providerName - ${source.quality ?: "AUTO"}",
                            url = streamUrl,
                            referer = "$mainUrl/",
                            quality = getQualityFromName(source.quality?.toString() ?: ""),
                            isM3u8 = streamUrl.contains(".m3u8"),
                            headers = mapOf("Origin" to mainUrl)
                        ))
                        linksFound++
                    }
                } catch (e: Exception) {
                    // Abaikan server yang mati atau kosong
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return linksFound > 0
    }
}

// Data Classes pendukung
data class BackendServicesResponse(@JsonProperty("data") val data: List<String>?)
data class BackendFetchResponse(@JsonProperty("data") val data: BackendData?)
data class BackendData(
    @JsonProperty("sources") val sources: List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)
data class BackendSource(
    @JsonProperty("quality") val quality: Any?, 
    @JsonProperty("url") val url: String?
)
data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file") val file: String?
)
