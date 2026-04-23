package com.Adicinemax21

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
            val videoId = url.split("/").lastOrNull() ?: return
            
            val response = app.get(
                url = "$mainUrl/api/token/viewer?videoId=$videoId", 
                referer = url, 
                headers = mapOf("Origin" to mainUrl)
            ).parsedSafe<MajorplayResponse>()
            
            response?.subtitles?.forEach { sub ->
                val lang = sub.label ?: sub.lang ?: "Indonesian"
                val subUrl = sub.path ?: return@forEach
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }

            val videoUrl = response?.hlsUrl ?: response?.primaryUrl ?: return
            
            val streamHeaders = mapOf(
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*"
            )

            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = videoUrl,
                    referer = url,
                    headers = streamHeaders
                ).forEach { parsedLink ->
                    callback.invoke(parsedLink)
                }
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
            }
        } catch (e: Exception) { 
            Log.e("adixtream", "Majorplay Error: ${e.message}") 
        }
    }

    data class MajorplayResponse(
        @JsonProperty("hlsUrl") val hlsUrl: String? = null, 
        @JsonProperty("primaryUrl") val primaryUrl: String? = null, 
        @JsonProperty("subtitles") val subtitles: List<MajorSubtitle>? = null
    )
    
    data class MajorSubtitle(
        @JsonProperty("lang") val lang: String? = null, 
        @JsonProperty("label") val label: String? = null, 
        @JsonProperty("path") val path: String? = null
    )
}
