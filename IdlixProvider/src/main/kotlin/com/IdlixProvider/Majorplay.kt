package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

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
            
            val actualOrigin = "https://z1.idlixku.com"
            val actualReferer = "https://z1.idlixku.com/"
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

            val rawJsonString = "{\"claim\":\"$claimToken\"}"
            val requestBody = rawJsonString.toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())

            val safeHeaders = mapOf(
                "Origin" to actualOrigin,
                "Referer" to actualReferer,
                "User-Agent" to userAgent,
                "Accept" to "*/*"
            )

            // Menukar Token
            val response = app.post(
                url = "$mainUrl/api/play",
                headers = safeHeaders,
                requestBody = requestBody
            ).parsedSafe<NewMajorplayResponse>() ?: return

            val videoUrl = response.url ?: return

            response.subtitles?.forEach { sub ->
                val subUrl = sub.path ?: return@forEach
                val subLang = sub.label ?: sub.lang ?: "Unknown"
                subtitleCallback.invoke(
                    SubtitleFile(subLang, subUrl)
                )
            }

            // ==========================================
            // KUNCI JAWABAN PALING FINAL
            // ==========================================
            // Kita HARUS pakai isM3u8 = false agar sistem "M3u8Helper" Cloudstream 
            // TIDAK ikut campur dan TIDAK crash melihat ekstensi .js atau .css.
            // Biarkan mesin ExoPlayer yang membedah file playlist ini secara otomatis!
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = actualReferer,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false, // JANGAN UBAH INI JADI TRUE!
                    headers = safeHeaders
                )
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
