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
            
            // Samakan User-Agent secara global untuk Token dan ExoPlayer
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            // FIX 1: Mengirim request POST dengan raw TEXT (meniru jebakan browser asli)
            val response = app.post(
                url = "$mainUrl/api/play",
                headers = mapOf(
                    "Origin" to "https://z1.idlixku.com",
                    "Referer" to "https://z1.idlixku.com/",
                    "Accept" to "*/*",
                    "Content-Type" to "text/plain", // KUNCI UTAMA ANTI-BOT
                    "User-Agent" to userAgent
                ),
                text = "{\"claim\":\"$claimToken\"}" // Kirim sebagai string mentah, BUKAN json map
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

            // Mengambil link video
            val videoUrl = response?.url ?: return
            
            val streamHeaders = mapOf(
                "Origin" to "https://z1.idlixku.com",
                "Referer" to "https://z1.idlixku.com/",
                "User-Agent" to userAgent
            )

            // FIX 2: Tambahkan ekstensi .m3u8 palsu agar parser Cloudstream tidak kebingungan dengan .json
            val fixedUrl = if (videoUrl.contains("?")) "$videoUrl&type=.m3u8" else "$videoUrl?.m3u8"

            // Langsung lempar ke ExoPlayer sebagai M3U8 utuh
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixedUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://z1.idlixku.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = streamHeaders
                }
            )
            
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
