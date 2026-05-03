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
            
            // Mengekstrak daftar subtitle menggunakan 'for' loop standar dan 'newSubtitleFile'
            val subs = response?.subtitles
            if (subs != null) {
                for (sub in subs) {
                    val lang = sub.label ?: sub.lang ?: "Indonesian"
                    val subUrl = sub.path ?: continue
                    subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                }
            }

            // Mengambil link video
            val videoUrl = response?.url ?: return
            
            val streamHeaders = mapOf(
                "Origin" to "https://z1.idlixku.com",
                "Referer" to "https://z1.idlixku.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*"
            )

            // Mengirimkan link stream ini agar bisa diputar di aplikasi Cloudstream
            // UPDATE: Pengecekan .json agar playlist m3u8 dari Guravia terekstrak dengan resolusi lengkap
            if (videoUrl.contains(".m3u8") || videoUrl.contains(".json")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = videoUrl,
                    referer = "https://z1.idlixku.com/",
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
