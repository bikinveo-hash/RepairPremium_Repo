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
        return try {
            val idStr = mediaId.trim()
            val tWord = SALT_ARRAY[(idStr.toLongWithDefault(0L) % SALT_ARRAY.size).toInt()]
            
            // STRATEGI: Sesuaikan indeks sisipan dengan kalkulasi runtime JavaScript terbaru
            val insertIdx = (idStr.length / 2).coerceAtMost(idStr.length)
            val combinedStr = idStr.substring(0, insertIdx) + tWord + idStr.substring(insertIdx)
            
            val hashedBytes = executeOuterHash(executeInnerHash(combinedStr)).toByteArray()
            Base64.encodeToString(hashedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            "MzZmMWZjZjc=" // Fallback token aman jika terjadi kegagalan parser
        }
    }

    private fun String.toLongWithDefault(default: Long): Long {
        return this.toLongOrNull() ?: default
    }

    private fun executeInnerHash(input: String): String {
        var t = 0L
        val mask32 = 0xFFFFFFFFL
        for (n in input.indices) {
            val r = input[n].code.toLong() and 0xFFL
            t = (r + (t shl 6) + (t shl 16) - t) and mask32
            val shiftAmt = n % 5
            val i = (((t shl shiftAmt) and mask32) or (t ushr (32 - shiftAmt))) and mask32
            val rotAmt = n % 7
            val rRot = (((r shl rotAmt) and 0xFFL) or (r ushr (8 - rotAmt))) and 0xFFL
            t = t xor (i xor rRot)
            t = (t + (((t ushr 11) xor (t shl 3)) and mask32)) and mask32
        }
        t = t xor (t ushr 15)
        t = (((t and 0xFFFFL) * 49842L) + ((((t ushr 16) * 49842L) and 0xFFFFL) shl 16)) and mask32
        t = t xor (t ushr 13)
        t = (((t and 0xFFFFL) * 40503L) + ((((t ushr 16) * 40503L) and 0xFFFFL) shl 16)) and mask32
        t = t xor (t ushr 16)
        return t.toString(16).padStart(8, '0')
    }

    private fun executeOuterHash(input: String): String {
        val mask32 = 0xFFFFFFFFL
        var n = 3735928559L xor input.length.toLong()
        for (e in input.indices) {
            val r = input[e].code.toLong() and 0xFFL
            val salt = (((131L * e.toLong() + 89L) xor ((r shl (e % 5)) and mask32)) and 0xFFL)
            n = (((n shl 7) and mask32) or (n ushr 25)) xor (r xor salt)
            val i = (n and 0xFFFFL) * 60205L
            val o = (((n ushr 16) * 60205L) shl 16) and mask32
            n = (i + o) and mask32
            n = n xor (n ushr 11)
        }
        n = n xor (n ushr 15)
        n = (((n and 0xFFFFL) * 49842L) + ((((n ushr 16) * 49842L) and 0xFFFFL) shl 16)) and mask32
        n = n xor (n ushr 13)
        n = (((n and 0xFFFFL) * 40503L) + ((((n ushr 16) * 40503L) and 0xFFFFL) shl 16)) and mask32
        n = n xor (n ushr 16)
        n = (((n and 0xFFFFL) * 10196L) + ((((n ushr 16) * 10196L) and 0xFFFFL) shl 16)) and mask32
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

        val standardHeaders = mapOf(
            "Authority" to "www.rivestream.app",
            "Accept" to "application/json",
            "Referer" to "$mainUrl/watch?type=${if (isMovie) "movie" else "tv"}&id=$cleanId",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        // JALUR 1: API Internal Backend Fetch RiveStream
        try {
            val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"
            val secretKey = generateDynamicSecretKey(cleanId)

            val servicesListUrl = "$mainUrl/api/backendfetch?requestID=VideoProviderServices&secretKey=rive&proxyMode=noProxy"
            val servicesResponse = app.get(servicesListUrl, headers = standardHeaders).text
            val parsedServices = tryParseJson<BackendServicesResponse>(servicesResponse)
            val activeServices = parsedServices?.data ?: listOf("primevids", "flowcast", "asiacloud", "hindicast")

            val baseApiUrl = "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId"

            activeServices.amap { service ->
                try {
                    val proxyMode = if (service == "primevids") "undefined" else "noProxy"
                    val finalApiUrl = if (isMovie) {
                        "$baseApiUrl&service=$service&secretKey=$secretKey&proxyMode=$proxyMode"
                    } else {
                        val season = cleanData.substringAfter("?season=").substringBefore("&")
                        val episode = cleanData.substringAfter("&episode=")
                        "$baseApiUrl&service=$service&secretKey=$secretKey&proxyMode=$proxyMode&season=$season&episode=$episode"
                    }

                    val response = app.get(finalApiUrl, headers = standardHeaders).text
                    val parsedData = tryParseJson<BackendFetchResponse>(response) ?: return@amap
                    val sources = parsedData.data?.sources ?: return@amap

                    parsedData.data.captions?.forEach { caption ->
                        val captionUrl = caption.file ?: return@forEach
                        subtitleCallback(newSubtitleFile(lang = caption.label ?: "External Subtitle", url = captionUrl))
                    }

                    for (source in sources) {
                        val streamUrl = source.url ?: continue
                        val qualityName = source.quality?.uppercase() ?: "AUTO"
                        val displayName = "${source.source ?: "RiveStream"} - $qualityName"

                        if (streamUrl.contains(".m3u8") || source.format?.lowercase() == "hls") {
                            callback(newExtractorLink(source = providerName, name = displayName, url = streamUrl, type = ExtractorLinkType.M3U8) {
                                this.quality = getQualityFromName(qualityName)
                                this.referer = "$mainUrl/"
                                this.headers = mapOf("Origin" to mainUrl, "Accept" to "*/*")
                            })
                            synchronized(this) { linksFound++ }
                        } else {
                            val targetReferer = if (service == "flowcast") "https://123movienow.cc/" else "$mainUrl/"
                            val isExtractorFound = loadExtractor(url = streamUrl, referer = targetReferer, subtitleCallback, callback)
                            
                            if (!isExtractorFound && !streamUrl.contains("/e/")) {
                                callback(newExtractorLink(source = providerName, name = displayName, url = streamUrl) {
                                    this.quality = getQualityFromName(qualityName)
                                    this.referer = targetReferer
                                })
                                synchronized(this) { linksFound++ }
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // JALUR 2: Server Embed PrimeSrc
        try {
            val typeParam = if (isMovie) "movie" else "tv"
            var primeSrcApiUrl = "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=$typeParam"
            if (!isMovie) {
                val season = cleanData.substringAfter("?season=").substringBefore("&")
                val episode = cleanData.substringAfter("&episode=")
                primeSrcApiUrl += "&season=$season&episode=$episode"
            }

            val primeSrcHeaders = mapOf(
                "Referer" to "https://primesrc.me/embed/$typeParam?tmdb=$cleanId",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            
            val primeSrcResponse = app.get(primeSrcApiUrl, headers = primeSrcHeaders).text
            val parsedPrimeSrc = tryParseJson<PrimeSrcServerResponse>(primeSrcResponse)

            parsedPrimeSrc?.servers?.sortedByDescending { it.name?.lowercase()?.contains("streamtape") ?: false }?.amap { server ->
                val serverName = server.name?.lowercase() ?: return@amap
                val serverKey = server.key ?: return@amap
                
                val embedUrl = when {
                    serverName.contains("streamtape") -> "https://tpead.net/e/$serverKey"
                    serverName.contains("voe") -> "https://voe.sx/e/$serverKey"
                    serverName.contains("streamwish") -> "https://streamwish.to/e/$serverKey"
                    serverName.contains("filemoon") -> "https://filemoon.sx/e/$serverKey"
                    else -> null
                }

                if (embedUrl != null) {
                    try {
                        if (embedUrl.contains("tpead.net")) {
                            TpeadExtractor().getUrl(embedUrl, "https://primesrc.me/", subtitleCallback, callback)
                            synchronized(this) { linksFound++ }
                        } else {
                            if (loadExtractor(embedUrl, referer = "https://primesrc.me/", subtitleCallback, callback)) {
                                synchronized(this) { linksFound++ }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return linksFound > 0
    }
}
