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

            // Jubah Gaib Anti-Cloudflare
            val safeHeaders = mapOf(
                "Origin" to actualOrigin,
                "Referer" to actualReferer,
                "User-Agent" to userAgent,
                "Accept" to "*/*",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
                "sec-ch-ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "cross-site"
            )

            // 1. Tukar Token di Majorplay
            val response = app.post(
                url = "$mainUrl/api/play",
                headers = safeHeaders,
                requestBody = requestBody
            ).parsedSafe<NewMajorplayResponse>() ?: return

            val videoUrl = response.url ?: return

            // 2. Subtitle ternyata aman, kita nyalakan kembali!
            response.subtitles?.forEach { sub ->
                val subUrl = sub.path ?: return@forEach
                val subLang = sub.label ?: sub.lang ?: "Unknown"
                subtitleCallback.invoke(
                    SubtitleFile(subLang, subUrl)
                )
            }

            // 3. KUNCI JAWABAN FINAL (Bypass Error 2004)
            // Kita ekstrak resolusinya secara MANUAL menggunakan M3u8Helper
            // Ini menjamin "Jubah Gaib" kita tertempel permanen di file video!
            val extractedLinks = M3u8Helper.generateM3u8(
                source = name,
                url = videoUrl,
                referer = actualReferer,
                headers = safeHeaders
            )

            // Lempar semua resolusi (360p, 720p, 1080p) ke Cloudstream
            if (extractedLinks.isNotEmpty()) {
                extractedLinks.forEach { link ->
                    callback.invoke(link)
                }
            } else {
                // Fallback (Jaga-jaga jika M3u8Helper gagal mengekstrak)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8 
                    ) {
                        this.referer = actualReferer
                        this.quality = Qualities.Unknown.value
                        this.headers = safeHeaders
                    }
                )
            }

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
