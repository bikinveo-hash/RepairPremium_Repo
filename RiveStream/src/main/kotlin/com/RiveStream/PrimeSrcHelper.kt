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
        val isMovie = !cleanData.contains("?season=")
        val cleanId = cleanData.substringBefore("?").substringAfterLast("/")
        val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"

        // Enkripsi secretKey Dinamis Base64
        val rawSecret = "0-$cleanId"
        val secretKey = Base64.encodeToString(rawSecret.toByteArray(), Base64.NO_WRAP)

        // -------------------------------------------------------------------------
        // STRATEGI 1: Pemanenan Jalur Multi-Service dari API Internal RiveStream
        // -------------------------------------------------------------------------
        var baseApiUrl = "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId"
        if (!isMovie) {
            val season = cleanData.substringAfter("?season=").substringBefore("&")
            val episode = cleanData.substringAfter("&episode=")
            baseApiUrl += "&season=$season&episode=$episode"
        }

        // Daftar seluruh layanan aktif yang disisir simultan
        val activeServices = listOf("primevids", "flowcast", "asiacloud")
        var linksFound = 0

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

                // Pemuatan takarir otomatis dari payload objek jika tersedia (Seperti di FlowCast)
                parsedData.data.captions?.forEach { caption ->
                    val captionUrl = caption.file ?: return@forEach
                    val captionLabel = caption.label ?: "External Subtitle"
                    subtitleCallback(SubtitleFile(lang = captionLabel, url = captionUrl))
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
                            this.referer = "https://www.rivestream.app/"
                            this.headers = mapOf(
                                "Origin" to "https://www.rivestream.app",
                                "Accept" to "*/*"
                            )
                        })
                        linksFound++
                    } else {
                        // Fallback jika berupa tautan direct MP4 video
                        val targetReferer = if (service == "flowcast") "https://123movienow.cc/" else "$mainUrl/"
                        val isExtractorFound = loadExtractor(url = streamUrl, referer = targetReferer, subtitleCallback, callback)
                        if (!isExtractorFound) {
                            callback(newExtractorLink(source = providerName, name = displayName, url = streamUrl) {
                                this.referer = targetReferer
                                if (service == "flowcast") this.headers = mapOf("Origin" to "https://123movienow.cc")
                            })
                        }
                        linksFound++
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // -------------------------------------------------------------------------
        // STRATEGI 2: Ekstraksi Otomatis Jalur Server Embed via Gateway PrimeSrc
        // -------------------------------------------------------------------------
        if (isMovie) { // Difokuskan penuh untuk mematangkan Movie terlebih dahulu
            try {
                val primeSrcApiUrl = "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=movie"
                val primeSrcHeaders = mapOf(
                    "Referer" to "https://primesrc.me/embed/movie?tmdb=$cleanId",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )
                val primeSrcResponse = app.get(primeSrcApiUrl, headers = primeSrcHeaders).text
                val parsedPrimeSrc = tryParseJson<PrimeSrcServerResponse>(primeSrcResponse)

                parsedPrimeSrc?.servers?.forEach { server ->
                    val serverName = server.name?.lowercase() ?: return@forEach
                    val serverKey = server.key ?: return@forEach
                    
                    // Rumus pembuatan URL Embed murni, mengabaikan halaman penuh iklan non-embed
                    val embedUrl = when {
                        serverName.contains("streamtape") -> "https://streamtape.com/e/$serverKey"
                        serverName.contains("voe") -> "https://voe.sx/e/$serverKey"
                        serverName.contains("streamwish") -> "https://streamwish.to/e/$serverKey"
                        serverName.contains("filemoon") -> "https://filemoon.sx/e/$serverKey"
                        serverName.contains("dood") -> "https://dood.to/e/$serverKey"
                        serverName.contains("mixdrop") -> "https://mixdrop.co/e/$serverKey"
                        serverName.contains("filelions") -> "https://filelions.to/e/$serverKey"
                        serverName.contains("vidnest") -> "https://vidnest.to/e/$serverKey"
                        serverName.contains("vidvast") -> "https://vidvast.to/e/$serverKey"
                        else -> null
                    }

                    if (embedUrl != null) {
                        // Dilempar ke core extractor bawaan Cloudstream (Mendukung bypass otomatis)
                        val isExtracted = loadExtractor(embedUrl, referer = "https://primesrc.me/", subtitleCallback, callback)
                        if (isExtracted) linksFound++
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        return linksFound > 0
    }
}

// =========================================================================
// MODEL DATA CUSTOM PENAHAN PAYLOAD JSON 
// =========================================================================
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
