package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Base64

class PrimeSrcHelper {

    companion object {
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

    private fun generateDynamicSecretKey(mediaId: String?): String {
        if (mediaId == null) return "rive"
        try {
            val idStr = mediaId.trim()
            val tWord: String
            val insertIdx: Int

            val idNum = idStr.toLongOrNull()
            if (idNum != null) {
                tWord = SALT_ARRAY[(idNum % SALT_ARRAY.size).toInt()]
                insertIdx = ((idNum % idStr.length).toInt()) / 2
            } else {
                val charSum = idStr.fold(0) { sum, char -> sum + char.code }
                tWord = SALT_ARRAY[charSum % SALT_ARRAY.size]
                insertIdx = (charSum % idStr.length) / 2
            }

            val combinedStr = idStr.substring(0, insertIdx) + tWord + idStr.substring(insertIdx)
            val innerHashResult = executeInnerHash(combinedStr)
            val finalHexResult = executeOuterHash(innerHashResult)
            
            return Base64.encodeToString(finalHexResult.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return "topSecret"
        }
    }

    private fun executeInnerHash(input: String): String {
        var t = 0L
        val mask32 = 0xFFFFFFFFL
        for (n in input.indices) {
            val r = input[n].code.toLong() and 0xFFL
            t = (r + (t sh l 6) + (t sh l 16) - t) and mask32
            val shiftAmt = (n % 5)
            val i = (((t sh l shiftAmt) and mask32) or (t ushr (32 - shiftAmt))) and mask32
            val rotAmt = (n % 7)
            val rRot = (((r sh l rotAmt) and 0xFFL) or (r ushr (8 - rotAmt))) and 0xFFL
            t = t xor (i xor rRot)
            t = (t + (((t ushr 11) xor (t sh l 3)) and mask32)) and mask32
        }
        t = t xor (t ushr 15)
        t = (((t and 0xFFFFL) * 49842L) + ((((t ushr 16) * 49842L) and 0xFFFFL) sh l 16)) and mask32
        t = t xor (t ushr 13)
        t = (((t and 0xFFFFL) * 40503L) + ((((t ushr 16) * 40503L) and 0xFFFFL) sh l 16)) and mask32
        t = t xor (t ushr 16)
        return t.toString(16).padStart(8, '0')
    }

    private fun executeOuterHash(input: String): String {
        val mask32 = 0xFFFFFFFFL
        var n = 3735928559L xor input.length.toLong()
        for (e in input.indices) {
            val r = input[e].code.toLong() and 0xFFL
            val salt = (((131L * e.toLong() + 89L) xor ((r sh l (e % 5)) and mask32)) and 0xFFL)
            n = (((n sh l 7) and mask32) or (n ushr 25)) xor (r xor salt)
            val i = (n and 0xFFFFL) * 60205L
            val o = (((n ushr 16) * 60205L) sh l 16) and mask32
            n = (i + o) and mask32
            n = n xor (n ushr 11)
        }
        n = n xor (n ushr 15)
        n = (((n and 0xFFFFL) * 49842L) + ((((n ushr 16) * 49842L) and 0xFFFFL) sh l 16)) and mask32
        n = n xor (n ushr 13)
        n = (((n and 0xFFFFL) * 40503L) + ((((n ushr 16) * 40503L) and 0xFFFFL) sh l 16)) and mask32
        n = n xor (n ushr 16)
        n = (((n and 0xFFFFL) * 10196L) + ((((n ushr 16) * 10196L) and 0xFFFFL) sh l 16)) and mask32
        n = n xor (n ushr 15)
        return n.toString(16).padStart(8, '0')
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

        // -------------------------------------------------------------------------
        // JALUR 1: Gateway Server Embed PrimeSrc (Sesuai Urutan Log Kurl Riil)
        // -------------------------------------------------------------------------
        try {
            val typeParam = if (isMovie) "movie" else "tv"
            val primeSrcApiUrl = "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=$typeParam"
            val primeSrcHeaders = mapOf(
                "Referer" to "https://primesrc.me/embed/$typeParam?tmdb=$cleanId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            )
            
            val primeSrcResponse = app.get(primeSrcApiUrl, headers = primeSrcHeaders).text
            val parsedPrimeSrc = tryParseJson<PrimeSrcServerResponse>(primeSrcResponse)

            // KRUSIAL: Urutkan server agar Streamtape & Voe berjalan di depan, 
            // sementara Streamwish yang hobi membuat WebView hang ditaruh paling belakang!
            val sortedServers = parsedPrimeSrc?.servers?.sortedByDescending { server ->
                val name = server.name?.lowercase() ?: ""
                name.contains("streamtape") || name.contains("voe") || name.contains("mixdrop")
            }

            sortedServers?.forEach { server ->
                val serverName = server.name?.lowercase() ?: return@forEach
                val serverKey = server.key ?: return@forEach
                
                val embedUrl = when {
                    serverName.contains("streamtape") -> "https://streamtape.com/e/$serverKey"
                    serverName.contains("voe") -> "https://voe.sx/e/$serverKey"
                    serverName.contains("streamwish") -> "https://streamwish.to/e/$serverKey"
                    serverName.contains("filemoon") -> "https://filemoon.sx/e/$serverKey"
                    serverName.contains("dood") -> "https://dood.to/e/$serverKey"
                    serverName.contains("mixdrop") -> "https://mixdrop.co/e/$serverKey"
                    serverName.contains("filelions") -> "https://filelions.to/e/$serverKey"
                    else -> null
                }

                if (embedUrl != null) {
                    // Eksekusi pemanenan link video melalui extractor core
                    val isExtracted = loadExtractor(embedUrl, referer = "https://primesrc.me/", subtitleCallback, callback)
                    if (isExtracted) linksFound++
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // -------------------------------------------------------------------------
        // JALUR 2: Ekstraksi Backendfetch Internal (RiveStream Multi-Service)
        // -------------------------------------------------------------------------
        if (linksFound == 0) {
            try {
                val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"
                val secretKey = generateDynamicSecretKey(cleanId)

                var baseApiUrl = "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId"
                if (!isMovie) {
                    val season = cleanData.substringAfter("?season=").substringBefore("&")
                    val episode = cleanData.substringAfter("&episode=")
                    baseApiUrl += "&season=$season&episode=$episode"
                }

                val activeServices = listOf("primevids", "flowcast", "asiacloud")
                val standardHeaders = mapOf(
                    "Authority" to "www.rivestream.app",
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/watch?type=${if (isMovie) "movie" else "tv"}&id=$cleanId",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )

                for (service in activeServices) {
                    try {
                        val proxyMode = if (service == "primevids") "undefined" else "noProxy"
                        val finalApiUrl = "$baseApiUrl&service=$service&secretKey=$secretKey&proxyMode=$proxyMode"

                        val response = app.get(finalApiUrl, headers = standardHeaders).text
                        val parsedData = tryParseJson<BackendFetchResponse>(response) ?: continue
                        val sources = parsedData.data?.sources ?: continue

                        parsedData.data.captions?.forEach { caption ->
                            val captionUrl = caption.file ?: return@forEach
                            val captionLabel = caption.label ?: "External Subtitle"
                            subtitleCallback(newSubtitleFile(lang = captionLabel, url = captionUrl))
                        }

                        for (source in sources) {
                            val streamUrl = source.url ?: continue
                            val qualityName = source.quality?.toString()?.uppercase() ?: "AUTO"
                            val sourceLabel = source.source ?: "RiveStream"
                            val displayName = "$sourceLabel - $qualityName"

                            if (streamUrl.contains(".m3u8") || source.format?.lowercase() == "hls") {
                                callback(newExtractorLink(
                                    source = providerName,
                                    name = displayName,
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = getQualityFromName(qualityName)
                                    this.referer = "https://www.rivestream.app/"
                                    this.headers = mapOf("Origin" to "https://www.rivestream.app", "Accept" to "*/*")
                                })
                                linksFound++
                            } else {
                                val targetReferer = if (service == "flowcast") "https://123movienow.cc/" else "$mainUrl/"
                                val isExtractorFound = loadExtractor(url = streamUrl, referer = targetReferer, subtitleCallback, callback)
                                
                                // PERBAIKAN UTAMA: Blok fallback yang merusak halaman HTML dirubah agar aman dan seng bikin crash player lagi
                                if (!isExtractorFound && !streamUrl.contains("/e/")) {
                                    callback(newExtractorLink(source = providerName, name = displayName, url = streamUrl) {
                                        this.quality = getQualityFromName(qualityName)
                                        this.referer = targetReferer
                                    })
                                    linksFound++
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        return linksFound > 0
    }
}

// ===== DATA CLASSES MODEL =====
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
data class PrimeSrcServerResponse(@JsonProperty("servers") val servers: List<PrimeSrcServer>?)
data class PrimeSrcServer(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key") val key: String?
)
