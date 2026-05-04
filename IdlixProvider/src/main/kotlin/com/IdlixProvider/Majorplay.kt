package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
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
        try {
            val claimToken = url.substringAfter("claim=").substringBefore("&")
            if (claimToken.isEmpty() || !url.contains("claim=")) return
            
            // 1. IDENTITAS WEB ASLI (Dinamis dari Cloudstream)
            // IdlixProvider.kt akan mengirimkan https://z1.idlixku.com/ ke sini
            val actualReferer = referer ?: "https://z1.idlixku.com/"
            val actualOrigin = actualReferer.trimEnd('/') 
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

            // 2. PENUKARAN TIKET: WAJIB MENGGUNAKAN IDENTITAS IDLIX (BUKAN MAJORPLAY)
            val response = app.post(
                url = "$mainUrl/api/play",
                headers = mapOf(
                    "Origin" to actualOrigin,
                    "Referer" to actualReferer,
                    "Accept" to "*/*",
                    "User-Agent" to userAgent,
                    // Tambahan header Content-Type yang terbaca di log network-mu
                    "Content-Type" to "text/plain" 
                ),
                json = mapOf("claim" to claimToken) // Payload tetap menggunakan format Object JSON
            ).parsedSafe<NewMajorplayResponse>() ?: return

            val videoUrl = response.url ?: return
            
            // 3. HEADER PEMUTARAN VIDEO: JUGA MENGGUNAKAN IDENTITAS IDLIX
            val streamHeaders = mapOf(
                "Origin" to actualOrigin,
                "Referer" to actualReferer,
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

            // Melempar Master Playlist config.json ke ExoPlayer
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = actualReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = streamHeaders
                }
            )

        } catch (e: Exception) { 
            Log.e("adixtream", "Majorplay Error: ${e.message}") 
        }
    }

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
