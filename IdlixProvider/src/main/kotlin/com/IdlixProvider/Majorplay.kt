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
            // Mengambil token 'claim' dari URL iframe palsu yang dikirim IdlixProvider
            val claimToken = url.substringAfter("claim=").substringBefore("&")
            if (claimToken.isEmpty() || !url.contains("claim=")) return
            
            // Mengirim request POST dengan parameter 'json'
            val response = app.post(
                url = "$mainUrl/api/play",
                headers = mapOf(
                    "Origin" to "https://z1.idlixku.com",
                    "Referer" to "https://z1.idlixku.com/",
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                ),
                json = mapOf("claim" to claimToken)
            ).parsedSafe<NewMajorplayResponse>()
            
            // Mengekstrak daftar subtitle
            val subs = response?.subtitles
            if (subs != null) {
                for (sub in subs) {
                    val lang = sub.label ?: sub.lang ?: "Indonesian"
                    val subUrl = sub.path ?: continue
                    subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                }
            }

            // Mengambil link video m3u8 atau JSON dari API Majorplay
            val videoUrl = response?.url ?: return
            
            // FIX: Kembalikan Origin dan Accept agar lolos dari blokir 403 (CORS) Pintarverse/Guravia
            val streamHeaders = mapOf(
                "Origin" to "https://z1.idlixku.com",
                "Referer" to "https://z1.idlixku.com/",
                "Accept" to "*/*",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            )

            // Deteksi URL dari CDN yang menyamar sebagai .json (guravia / pintarverse)
            val isM3u8 = videoUrl.contains(".m3u8") || videoUrl.contains(".json") || videoUrl.contains("guravia") || videoUrl.contains("pintarverse")

            if (isM3u8) {
                // Lempar ke ExoPlayer sebagai M3U8 murni dengan header lengkap
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://z1.idlixku.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://z1.idlixku.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
            }
        } catch (e: Exception) { 
            Log.e("adixtream", "Majorplay Error: ${e.message}") 
        }
    }

    // Struktur Data (JSON)
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
