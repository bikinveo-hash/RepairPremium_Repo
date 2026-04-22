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
            // Mengambil ID Video dari akhir URL iframe
            val videoId = url.split("/").lastOrNull() ?: return
            
            // Menembak API Token Majorplay
            val response = app.get(
                url = "$mainUrl/api/token/viewer?videoId=$videoId", 
                referer = url, 
                headers = mapOf("Origin" to mainUrl)
            ).parsedSafe<MajorplayResponse>()
            
            // Mengirimkan semua subtitle yang ditemukan ke aplikasi
            response?.subtitles?.forEach { sub ->
                val lang = sub.label ?: sub.lang ?: "Indonesian"
                val subUrl = sub.path ?: return@forEach
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }

            // Mengambil link video utama
            val videoUrl = response?.hlsUrl ?: response?.primaryUrl ?: return
            
            // Mengirimkan link video M3U8 ke ExoPlayer Cloudstream
            callback.invoke(
                ExtractorLink(
                    source = name, 
                    name = name, 
                    url = videoUrl, 
                    referer = url, 
                    quality = Qualities.Unknown.value, 
                    type = ExtractorLinkType.M3U8
                )
            )
        } catch (e: Exception) { 
            Log.e("adixtream", "Majorplay Error: ${e.message}") 
        }
    }

    // --- Struktur Data JSON dari Majorplay ---
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
