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
        
        // Pembersihan & Deteksi Kategori Konten
        val isMovie = !data.contains("?season=")
        val cleanId = data.substringAfter("/movie/").substringAfter("/tv/").substringBefore("?")
        val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"

        // Enkripsi secretKey Dinamis Base64
        val rawSecret = "0-$cleanId"
        val secretKey = Base64.encodeToString(rawSecret.toByteArray(), Base64.NO_WRAP)

        // Base URL untuk request internal
        var baseApiUrl = "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId"
        if (!isMovie) {
            val season = data.substringAfter("?season=").substringBefore("&")
            val episode = data.substringAfter("&episode=")
            baseApiUrl += "&season=$season&episode=$episode"
        }

        // Daftar layanan aktif yang kita sisir sekaligus agar link keluar melimpah!
        val activeServices = listOf("primevids", "flowcast")
        var linksFound = 0

        for (service in activeServices) {
            try {
                // Penyesuaian parameter proxyMode berdasarkan tipe layanan
                val proxyMode = if (service == "primevids") "undefined" else "noProxy"
                val finalApiUrl = "$baseApiUrl&service=$service&secretKey=$secretKey&proxyMode=$proxyMode"

                val headers = mapOf(
                    "Authority" to "www.rivestream.app",
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/watch?type=${if (isMovie) "movie" else "tv"}&id=$cleanId",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )

                val response = app.get(finalApiUrl, headers = headers).text
                val parsedData = tryParseJson<BackendFetchResponse>(response) ?: continue
                val sources = parsedData.data?.sources ?: continue

                // Ambil takarir otomatis jika dilampirkan di dalam payload respon (Seperti di FlowCast)
                parsedData.data.captions?.forEach { caption ->
                    val captionUrl = caption.file ?: return@forEach
                    val captionLabel = caption.label ?: "External Subtitle"
                    subtitleCallback(
                        SubtitleFile(
                            lang = captionLabel,
                            url = captionUrl
                        )
                    )
                }

                // Iterasi tautan video stream
                for (source in sources) {
                    val streamUrl = source.url ?: continue
                    // Mengubah Any? aman menjadi string (Bypass Bug Mismatch Int/String)
                    val qualityName = source.quality?.toString()?.uppercase() ?: "AUTO"
                    val sourceLabel = source.source ?: "RiveStream"
                    val displayName = "$sourceLabel - $qualityName"

                    // Kondisi penanganan berkas manifest (.m3u8 / HLS)
                    if (streamUrl.contains(".m3u8") || source.format?.lowercase() == "hls") {
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
                        // Kondisi penanganan berkas Direct MP4 (Gunakan referer pengaman khusus)
                        val targetReferer = if (service == "flowcast") "https://123movienow.cc/" else "$mainUrl/"
                        val isExtractorFound = loadExtractor(
                            url = streamUrl,
                            referer = targetReferer,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                        if (!isExtractorFound) {
                            callback(newExtractorLink(
                                source = providerName,
                                name = displayName,
                                url = streamUrl
                            ) {
                                this.referer = targetReferer
                                if (service == "flowcast") {
                                    this.headers = mapOf("Origin" to "https://123movienow.cc")
                                }
                            })
                        }
                        linksFound++
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return linksFound > 0
    }
}

// =========================================================================
// MODEL DATA CUSTOM REVISI UNTUK BACKENDFETCH API JSON
// =========================================================================
data class BackendFetchResponse(@JsonProperty("data") val data: BackendData?)

data class BackendData(
    @JsonProperty("sources") val sources: List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)

data class BackendSource(
    @JsonProperty("quality") val quality: Any?, // Diubah jadi Any? agar Jackson tidak crash!
    @JsonProperty("url") val url: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("format") val format: String?
)

data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file") val file: String?
)
