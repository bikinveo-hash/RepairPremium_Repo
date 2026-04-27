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
            // Mengambil token 'claim' dari URL iframe 
            // (Asumsinya url berisi e2e.majorplay.net/play?claim=eyJ...)
            val claimToken = url.substringAfter("claim=").substringBefore("&")
            if (claimToken.isEmpty() || !url.contains("claim=")) return
            
            // Membuat body request POST persis seperti data curl buatanmu
            val requestBody = """{"claim":"$claimToken"}"""
            
            // Mengirim request POST ke API Majorplay yang baru
            val response = app.post(
                url = "$mainUrl/api/play",
                headers = mapOf(
                    "Origin" to "https://z1.idlixku.com",
                    "Referer" to "https://z1.idlixku.com/",
                    "Content-Type" to "text/plain", // Content-type yang mereka pakai sekarang
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                ),
                text = requestBody
            ).parsedSafe<NewMajorplayResponse>()
            
            // Mengekstrak daftar subtitle
            response?.subtitles?.forEach { sub ->
                val lang = sub.label ?: sub.lang ?: "Indonesian"
                val subUrl = sub.path ?: return@forEach
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }

            // Mengambil link video m3u8 (sekarang memakai variabel 'url')
            val videoUrl = response?.url ?: return
            
            val streamHeaders = mapOf(
                "Origin" to "https://z1.idlixku.com",
                "Referer" to "https://z1.idlixku.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*"
            )

            // Mengirimkan link stream ini agar bisa diputar di aplikasi Cloudstream
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = videoUrl,
                referer = "https://z1.idlixku.com/",
                headers = streamHeaders
            ).forEach { parsedLink ->
                callback.invoke(parsedLink)
            }
        } catch (e: Exception) { 
            Log.e("adixtream", "Majorplay Error: ${e.message}") 
        }
    }

    // Struktur Data (JSON) yang baru kita sesuaikan dengan tangkapan layar network milikmu
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
