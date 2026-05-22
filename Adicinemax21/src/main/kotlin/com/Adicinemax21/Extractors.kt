package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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
        // Mengambil Claim Token dari URL yang dihasilkan oleh Adicinemax21Extractor
        val claimToken = url.substringAfter("claim=").substringBefore("&")
        if (claimToken.isEmpty()) return

        val safeHeaders = mapOf(
            "Origin" to "https://z1.idlixku.com",
            "Referer" to "https://z1.idlixku.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        )

        try {
            val textMediaType = "text/plain".toMediaTypeOrNull()
            val requestBodyData = mapOf("claim" to claimToken).toJson().toRequestBody(textMediaType)

            // POST claim token ke API Majorplay sesuai IdlixProvider baru
            val responseText = app.post(
                url = "$mainUrl/api/play",
                headers = safeHeaders.plus("Content-Type" to "text/plain"),
                requestBody = requestBodyData
            ).text
            
            val response = AppUtils.parseJson<NewMajorplayResponse>(responseText)
            val masterConfigUrl = response.url ?: return
            
            // Ekstraksi subtitle resmi
            response.subtitles?.forEach { sub ->
                val lang = sub.label ?: sub.lang ?: "Indonesian"
                val subUrl = sub.path ?: return@forEach
                subtitleCallback.invoke(
                    newSubtitleFile(lang, subUrl)
                )
            }

            // Menambahkan suffix .m3u8 agar dikenali oleh core engine Cloudstream sebagai manifest HLS
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
                    this.quality = Qualities.Unknown.value
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
