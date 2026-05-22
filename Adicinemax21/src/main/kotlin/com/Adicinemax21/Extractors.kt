package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

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
            // Menangkap token klaim dari URL yang dikirim oleh provider utama
            val claimToken = url.substringAfter("claim=").substringBefore("&")
            if (claimToken.isEmpty()) return

            val safeHeaders = mapOf(
                "Origin" to "https://z1.idlixku.com",
                "Referer" to "https://z1.idlixku.com/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            )

            // Mengirim payload POST dengan tipe text/plain yang ketat
            val textMediaType = "text/plain".toMediaTypeOrNull()
            val requestBodyData = mapOf("claim" to claimToken).toJson().toRequestBody(textMediaType)

            val responseText = app.post(
                url = "$mainUrl/api/play",
                headers = safeHeaders.plus("Content-Type" to "text/plain"),
                requestBody = requestBodyData
            ).text
            
            val response = AppUtils.parseJson<NewMajorplayResponse>(responseText)
            val masterConfigUrl = response.url ?: return
            
            // Parsing subtitle bawaan Majorplay
            response.subtitles?.forEach { sub ->
                val lang = sub.label ?: sub.lang ?: "Indonesian"
                val subUrl = sub.path ?: return@forEach
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }

            // Trik & .m3u8 agar lolos saringan filter Cloudstream Core
            val finalPlayableUrl = "$masterConfigUrl&.m3u8"
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Majorplay Auto Quality",
                    url = finalPlayableUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = safeHeaders
                    this.referer = "https://z1.idlixku.com/"
                    this.quality = Qualities.Unknown.value // Wajib diinisialisasi
                }
            )
        } catch (e: Exception) {
            Log.e("adixtream", "Majorplay Error: ${e.message}")
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorplayResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null,
        @JsonProperty("label") val label: String? = null
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorSubtitle(
        @JsonProperty("lang") val lang: String? = null, 
        @JsonProperty("label") val label: String? = null, 
        @JsonProperty("path") val path: String? = null
    )
}
