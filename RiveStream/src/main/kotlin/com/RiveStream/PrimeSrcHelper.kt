package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Base64

class PrimeSrcHelper {

    suspend fun invokePrimeSrc(
        data: String, 
        mainUrl: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?s=")
        val cleanId = cleanData.substringAfter("/").substringBefore("?")

        // 1. Tentukan jenis requestID berdasarkan kategori konten
        val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"

        // 2. Buat parameter secretKey dinamis: "0-" + ID lalu di-encode ke Base64
        val rawSecret = "0-$cleanId"
        val secretKey = Base64.encodeToString(rawSecret.toByteArray(), Base64.NO_WRAP)

        // 3. Susun URL API BackendFetch internal
        var apiUrl = "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId&service=primevids&secretKey=$secretKey&proxyMode=undefined"
        
        // Jikalau konten berupa Serial TV, tambahkan parameter season & episode bawaan
        if (!isMovie) {
            val season = cleanData.substringAfter("?s=").substringBefore("&")
            val episode = cleanData.substringAfter("&e=")
            apiUrl += "&season=$season&episode=$episode"
        }

        // 4. Pasang headers paspor agar server mengenali request berasal dari browser resmi
        val headers = mapOf(
            "Authority" to "www.rivestream.app",
            "Accept" to "application/json",
            "Referer" to "$mainUrl/watch?type=${if (isMovie) "movie" else "tv"}&id=$cleanId",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )

        var linksFound = 0

        try {
            // Tembak API secara langsung tanpa ampun (0,2 detik selesai!)
            val response = app.get(apiUrl, headers = headers).text
            val parsedData = tryParseJson<BackendFetchResponse>(response)
            val sources = parsedData?.data?.sources ?: return false

            for (source in sources) {
                val streamUrl = source.url ?: continue
                val qualityName = source.quality ?: "Auto"
                val sourceLabel = source.source ?: "RiveStream"

                // Tentukan nama tampilan di dalam menu kualitas pemutar media
                val displayName = "$sourceLabel - ${qualityName.uppercase()}"

                if (streamUrl.contains(".m3u8") || source.format == "hls") {
                    callback(newExtractorLink(
                        source = providerName,
                        name = displayName,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://www.rivestream.app/"
                        this.headers = mapOf(
                            "Origin" to "https://www.rivestream.app",
                            "Accept" to "*/*"
                        )
                    })
                    linksFound++
                } else {
                    // Fallback jika kedepannya server memberikan direct MP4 link
                    val isExtractorFound = loadExtractor(
                        url = streamUrl,
                        referer = "$mainUrl/",
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    if (!isExtractorFound) {
                        callback(newExtractorLink(
                            source = providerName,
                            name = displayName,
                            url = streamUrl
                        ) {
                            this.referer = "$mainUrl/"
                        })
                    }
                    linksFound++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return linksFound > 0
    }
}

// =========================================================================
// MODEL DATA CUSTOM UNTUK BACKENDFETCH API JSON
// =========================================================================

data class BackendFetchResponse(
    @JsonProperty("data") val data: BackendData?
)

data class BackendData(
    @JsonProperty("sources") val sources: List<BackendSource>?
)

data class BackendSource(
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("format") val format: String?
)
