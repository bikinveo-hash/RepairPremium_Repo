package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import android.util.Base64

class PrimeSrcHelper {

    companion object {
        private val SALT_ARRAY = listOf(
            "4Z7lUo", "gwIVSMD", "PLmz2elE2v", "Z4OFV0", "SZ6RZq6Zc", "zhJEFYxrz8", "FOm7b0", "axHS3q4KDq", "o9zuXQ", "4Aebt",
            "wgjjWwKKx", "rY4VIxqSN", "kfjbnSo", "2DyrFA1M", "YUixDM9B", "JQvgEj0", "mcuFx6JIek", "eoTKe26gL", "qaI9EVO1rB", "0xl33btZL",
            "1fszuAU", "a7jnHzst6P", "wQuJkX", "cBNhTJlEOf", "KNcFWhDvgT", "XipDGjST", "PCZJlbHoyt", "2AYnMZkqd", "HIpJh", "KH0C3iztrG"
        )
    }

    // Fungsi matematika biner untuk bypass
    private fun splitMul(value: Int, multiplier: Int): Int {
        val low = (value and 0xFFFF) * multiplier
        val high = (((value ushr 16) * multiplier) and 0xFFFF) shl 16
        return low + high
    }

    private fun executeInnerHash(input: String): String {
        var t = 0
        for (n in input.indices) {
            val r = input[n].code
            t = r + (t shl 6) + (t shl 16) - t
            val shiftAmt = n % 5
            val rightShiftDist = (32 - shiftAmt) % 32
            val i = (t shl shiftAmt) or (t ushr rightShiftDist)
            val rotAmt = n % 7
            val rRot = (r shl rotAmt) or (r ushr (8 - rotAmt))
            t = t xor (i xor rRot)
            t = t + ((t ushr 11) xor (t shl 3))
        }
        t = t xor (t ushr 15)
        t = splitMul(t, 49842)
        t = t xor (t ushr 13)
        t = splitMul(t, 40503)
        t = t xor (t ushr 16)
        return String.format("%08x", t)
    }

    private fun executeOuterHash(input: String): Int {
        var nVal = 3735928559.toInt() xor input.length
        for (e in input.indices) {
            val r = input[e].code
            val saltMultiplier = ((131 * e + 89) xor (r shl (e % 5))) and 255
            val finalR = r xor saltMultiplier
            nVal = ((nVal shl 7) or (nVal ushr 25)) xor finalR
            nVal = splitMul(nVal, 60205)
            nVal = nVal xor (nVal ushr 11)
        }
        nVal = nVal xor (nVal ushr 15)
        nVal = splitMul(nVal, 49842)
        nVal = nVal xor (nVal ushr 13)
        nVal = splitMul(nVal, 40503)
        nVal = nVal xor (nVal ushr 16)
        val splitMulVal = splitMul(nVal, 10196)
        nVal = splitMulVal xor (splitMulVal ushr 15)
        return nVal
    }

    fun generateDynamicSecretKey(mediaId: String?): String {
        if (mediaId == null) return "LTE0NDkzOTE2"
        return try {
            // Penggunaan Token Master untuk bypass request
            Base64.encodeToString("LTE0NDkzOTE2".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (e: Exception) {
            "LTE0NDkzOTE2"
        }
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

        val secretKey = generateDynamicSecretKey(cleanId)

        try {
            val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"
            val servicesListUrl = "$mainUrl/api/backendfetch?requestID=VideoProviderServices&secretKey=rive&proxyMode=undefined"
            val servicesResponse = app.get(servicesListUrl).text
            val activeServices = tryParseJson<BackendServicesResponse>(servicesResponse)?.data 
                ?: listOf("primevids", "flowcast", "asiacloud", "guru", "ophim")

            activeServices.forEach { service ->
                val proxyMode = if (service == "primevids") "undefined" else "noProxy"
                val finalApiUrl = "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId&service=$service&secretKey=$secretKey&proxyMode=$proxyMode"

                val response = app.get(finalApiUrl).text
                val parsedData = tryParseJson<BackendFetchResponse>(response) ?: return@forEach
                
                parsedData.data?.captions?.forEach { subtitleCallback(SubtitleFile(it.label ?: "Sub", it.file ?: "")) }

                parsedData.data?.sources?.forEach { source ->
                    val streamUrl = source.url ?: return@forEach
                    
                    // Gunakan newExtractorLink yang WAJIB untuk versi terbaru Cloudstream
                    callback(newExtractorLink(
                        source = providerName,
                        name = "$providerName - ${source.quality ?: "AUTO"}",
                        url = streamUrl,
                        referer = "$mainUrl/",
                        quality = getQualityFromName(source.quality?.toString() ?: ""),
                        isM3u8 = streamUrl.contains(".m3u8")
                    ))
                    linksFound++
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return linksFound > 0
    }
}

// Data Classes Tetap Sama
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
