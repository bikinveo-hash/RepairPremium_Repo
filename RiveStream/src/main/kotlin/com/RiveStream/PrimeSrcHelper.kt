package com.lagradost.cloudstream3

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import android.util.Base64

class PrimeSrcHelper {

    companion object {
        // Larik garam statis resmi hasil ekstraksi Modul 2873 / 7522
        private val SALT_ARRAY = listOf(
            "4Z7lUo", "gwIVSMD", "PLmz2elE2v", "Z4OFV0", "SZ6RZq6Zc", "zhJEFYxrz8", "FOm7b0", "axHS3q4KDq", "o9zuXQ", "4Aebt",
            "wgjjWwKKx", "rY4VIxqSN", "kfjbnSo", "2DyrFA1M", "YUixDM9B", "JQvgEj0", "mcuFx6JIek", "eoTKe26gL", "qaI9EVO1rB", "0xl33btZL",
            "1fszuAU", "a7jnHzst6P", "wQuJkX", "cBNhTJlEOf", "KNcFWhDvgT", "XipDGjST", "PCZJlbHoyt", "2AYnMZkqd", "HIpJh", "KH0C3iztrG",
            "W81hjts92", "rJhAT", "NON7LKoMQ", "NMdY3nsKzI", "t4En5v", "Qq5cOQ9H", "Y9nwrp", "VX5FYVfsf", "cE5SJG", "x1vj1",
            "HegbLe", "zJ3nmt4OA", "gt7rxW57dq", "clIE9b", "jyJ9g", "B5jXjMCSx", "cOzZBZTV", "FTXGy", "Dfh1q1", "ny9jqZ2POI",
            "X2NnMn", "MBtoyD", "qz4Ilys7wB", "68lbOMye", "3YUJnmxp", "1fv5Imona", "PlfvvXD7mA", "ZarKfHCaPR", "owORnX", "dQP1YU",
            "dVdkx", "qgiK0E", "cx9wQ", "5F9bGa", "7UjkKrp", "Yvhrj", "wYXez5Dg3", "pG4GMU", "MwMAu", "rFRD5wlM"
        )
    }

    /**
     * Memperbaiki overflow matematika perkalian besar sesuai standard JavaScript V8 engine
     */
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
        var nVal = 0xDEADBEEF.toInt() xor input.length
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

    /**
     * Generator Kunci Otomatis Dinamis - Menghasilkan Luaran Hex ke Base64
     */
    fun generateDynamicSecretKey(mediaId: String?): String {
        if (mediaId == null) return "rive"
        return try {
            val idStr = mediaId.trim()
            val idLong = idStr.toLongOrNull() ?: return "rive"
            
            val tWord = SALT_ARRAY[(idLong % SALT_ARRAY.size).toInt()]
            val insertIdx = ((idLong % idStr.length).toInt()) / 2
            
            val combinedStr = idStr.substring(0, insertIdx) + tWord + idStr.substring(insertIdx)
            val inner = executeInnerHash(combinedStr)
            val outerInt = executeOuterHash(inner)
            
            val hexStr = String.format("%08x", outerInt)
            Base64.encodeToString(hexStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (e: Exception) {
            "topSecret"
        }
    }

    /**
     * Fungsi Pemicu Utama untuk Ekstraksi Link Video multi-server
     */
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
            "Host" to "www.rivestream.app",
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$mainUrl/watch?type=${if (isMovie) "movie" else "tv"}&id=$cleanId",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to mainUrl,
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )

        try {
            val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"
            val secretKey = generateDynamicSecretKey(cleanId)

            // Mengambil daftar layanan aktif dari server (seperti primevids, flowcast, dll)
            val servicesListUrl = "$mainUrl/api/backendfetch?requestID=VideoProviderServices&secretKey=rive&proxyMode=undefined"
            val servicesResponse = app.get(servicesListUrl, headers = standardHeaders).text
            val parsedServices = tryParseJson<BackendServicesResponse>(servicesResponse)
            val activeServices = parsedServices?.data ?: listOf("primevids", "flowcast", "asiacloud")

            activeServices.forEach { service ->
                try {
                    val proxyMode = if (service == "primevids") "undefined" else "noProxy"
                    val finalApiUrl = if (isMovie) {
                        "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId&service=$service&secretKey=$secretKey&proxyMode=$proxyMode"
                    } else {
                        val season = cleanData.substringAfter("?season=").substringBefore("&")
                        val episode = cleanData.substringAfter("&episode=")
                        "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId&service=$service&secretKey=$secretKey&proxyMode=$proxyMode&season=$season&episode=$episode"
                    }

                    val response = app.get(finalApiUrl, headers = standardHeaders).text
                    val parsedData = tryParseJson<BackendFetchResponse>(response) ?: return@forEach
                    val sources = parsedData.data?.sources ?: return@forEach

                    parsedData.data.captions?.forEach { caption ->
                        val captionUrl = caption.file ?: return@forEach
                        val captionLabel = caption.label ?: "Subtitle"
                        subtitleCallback(SubtitleFile(captionLabel, captionUrl))
                    }

                    for (source in sources) {
                        val streamUrl = source.url ?: continue
                        val qualityName = source.quality?.uppercase() ?: "AUTO"
                        val sourceLabel = source.source ?: "RiveStream"
                        val displayName = "$sourceLabel - $qualityName"

                        if (streamUrl.contains(".m3u8") || source.format?.lowercase() == "hls") {
                            val link = ExtractorLink(
                                source = providerName,
                                name = displayName,
                                url = streamUrl,
                                referer = "$mainUrl/",
                                quality = getQualityFromName(qualityName),
                                isM3u8 = true,
                                headers = mapOf("Origin" to mainUrl)
                            )
                            callback(link)
                            linksFound++
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return linksFound > 0
    }
}

// Objek Data Class Penampung Respons JSON Peladen Next.js BFF
data class BackendServicesResponse(@JsonProperty("data") val data: List<String>?)
data class BackendFetchResponse( @JsonProperty("data") val data: BackendData?)
data class BackendData(
    @JsonProperty("sources") val sources: List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)
data class BackendSource(
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("format") val format: String?
)
data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file") val file: String?
)
