package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
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
        try {
            val claimToken = url.substringAfter("claim=").substringBefore("&")
            if (claimToken.isEmpty() || !url.contains("claim=")) return
            
            // 1. WAJIB menggunakan User-Agent panjang agar tidak dikira bot oleh CDN
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            
            // 2. WAJIB menggunakan Root Domain sebagai Referer. 
            // Jika menggunakan link episode full, server Guravia akan memberikan Error 403.
            val rootDomain = "https://z1.idlixku.com/"

            val response = app.post(
                url = "$mainUrl/api/play",
                headers = mapOf(
                    "Origin" to "https://z1.idlixku.com",
                    "Referer" to rootDomain,
                    "Accept" to "*/*",
                    "User-Agent" to userAgent
                ),
                json = mapOf("claim" to claimToken)
            ).parsedSafe<NewMajorplayResponse>() ?: return

            val videoUrl = response.url ?: return
            
            // 3. Header murni yang akan dipakai ExoPlayer untuk menyedot kepingan videonya
            val streamHeaders = mapOf(
                "Origin" to "https://z1.idlixku.com",
                "Referer" to rootDomain,
                "User-Agent" to userAgent,
                "Accept" to "*/*"
            )

            response.subtitles?.forEach { sub ->
                val subUrl = sub.path ?: return@forEach
                val subLang = sub.label ?: sub.lang ?: "Unknown"
                subtitleCallback.invoke(
                    SubtitleFile(subLang, subUrl)
                )
            }

            // 4. WAJIB menggunakan ExtractorLinkType.VIDEO agar tidak crash di M3u8Helper.
            // Jangan khawatir, ExoPlayer sangat pintar dan akan otomatis mengenalinya sebagai HLS (M3U8).
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = rootDomain
                    this.quality = Qualities.Unknown.value
                    this.headers = streamHeaders
                }
            )

        } catch (e: Exception) { 
            Log.e("adixtream", "Majorplay Error: ${e.message}") 
        }
    }

    // Struktur Data API
    data class NewMajorplayResponse(
        @JsonProperty("code") val code: String? = null,
        @JsonProperty("url") val url: String? = null, 
        @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null
    )
    
    data class NewMajorSubtitle(
        @JsonProperty("lang") val lang: String? = null, 
        @JsonProperty("label") val label: String? = null, 
        @JsonProperty("path") val path: String? = null
    )
}
