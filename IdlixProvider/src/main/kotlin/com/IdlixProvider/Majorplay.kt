package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val claimToken = url.substringAfter("claim=").substringBefore("&")
        if (claimToken.isEmpty()) return

        val safeHeaders = mapOf(
            "Origin" to "https://z1.idlixku.com",
            "Referer" to "https://z1.idlixku.com/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )

        val response = app.post(
            url = "$mainUrl/api/play",
            headers = safeHeaders,
            data = mapOf("claim" to claimToken)
        ).parsedSafe<NewMajorplayResponse>() ?: return

        val videoUrl = response.url ?: return
        
        // Ambil isi playlist
        val playlist = app.get(videoUrl, headers = safeHeaders).text
        
        // FILTER: Ambil hanya baris yang mengandung .m3u8 atau link video asli
        // Abaikan semua .js, .css, .jpg, .webp, .html
        val lines = playlist.split("\n")
        var extracted = false
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            
            // Hanya ambil link yang valid untuk streaming
            if (trimmed.contains(".m3u8") || trimmed.contains("chunk")) {
                val finalUrl = if (trimmed.startsWith("http")) trimmed else {
                    videoUrl.substringBeforeLast("/") + "/" + trimmed
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Majorplay Stream",
                        url = "$finalUrl&.m3u8", // Trik agar Cloudstream percaya ini M3U8
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = safeHeaders
                    }
                )
                extracted = true
            }
        }
        
        if (!extracted) {
             callback.invoke(
                newExtractorLink(source = name, name = "Fallback", url = videoUrl, type = ExtractorLinkType.M3U8) {
                    this.headers = safeHeaders
                }
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorplayResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorSubtitle(
        @JsonProperty("lang") val lang: String? = null, 
        @JsonProperty("path") val path: String? = null
    )
}
