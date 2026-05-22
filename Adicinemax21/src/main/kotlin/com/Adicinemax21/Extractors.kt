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
        [span_1](start_span)// Mengekstrak Claim Token yang dikirimkan dari Adicinemax21Extractor[span_1](end_span)
        [span_2](start_span)val claimToken = url.substringAfter("claim=").substringBefore("&")[span_2](end_span)
        [span_3](start_span)if (claimToken.isEmpty()) return[span_3](end_span)

        val safeHeaders = mapOf(
            [span_4](start_span)"Origin" to "https://z1.idlixku.com",[span_4](end_span)
            [span_5](start_span)"Referer" to "https://z1.idlixku.com/",[span_5](end_span)
            [span_6](start_span)"User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"[span_6](end_span)
        )

        try {
            [span_7](start_span)val textMediaType = "text/plain".toMediaTypeOrNull()[span_7](end_span)
            [span_8](start_span)val requestBodyData = mapOf("claim" to claimToken).toJson().toRequestBody(textMediaType)[span_8](end_span)

            [span_9](start_span)// Melakukan POST ke endpoint /api/play sesuai protokol Idlix terbaru[span_9](end_span)
            val responseText = app.post(
                [span_10](start_span)url = "$mainUrl/api/play",[span_10](end_span)
                [span_11](start_span)headers = safeHeaders.plus("Content-Type" to "text/plain"),[span_11](end_span)
                [span_12](start_span)requestBody = requestBodyData[span_12](end_span)
            [span_13](start_span)).text[span_13](end_span)
            
            [span_14](start_span)val response = AppUtils.parseJson<NewMajorplayResponse>(responseText)[span_14](end_span)
            [span_15](start_span)val masterConfigUrl = response.url ?: return[span_15](end_span)
            
            [span_16](start_span)// Memuat subtitle resmi dari respon Majorplay[span_16](end_span)
            response.subtitles?.forEach { sub ->
                [span_17](start_span)val lang = sub.label ?: sub.lang ?: "Indonesian"[span_17](end_span)
                [span_18](start_span)val subUrl = sub.path ?: return@forEach[span_18](end_span)
                subtitleCallback.invoke(
                    [span_19](start_span)newSubtitleFile(lang, subUrl)[span_19](end_span)
                )
            }

            [span_20](start_span)// Menambahkan suffix .m3u8 agar dikenali oleh core engine Cloudstream[span_20](end_span)
            [span_21](start_span)val finalPlayableUrl = "$masterConfigUrl&.m3u8"[span_21](end_span)
            
            callback.invoke(
                newExtractorLink(
                    [span_22](start_span)source = name,[span_22](end_span)
                    [span_23](start_span)name = "Majorplay Auto Quality",[span_23](end_span)
                    [span_24](start_span)url = finalPlayableUrl,[span_24](end_span)
                    [span_25](start_span)type = ExtractorLinkType.M3U8[span_25](end_span)
                ) {
                    [span_26](start_span)this.headers = safeHeaders[span_26](end_span)
                    [span_27](start_span)this.referer = "https://z1.idlixku.com/"[span_27](end_span)
                    [span_28](start_span)this.quality = Qualities.Unknown.value[span_28](end_span)
                }
            )
        } catch (e: Exception) { 
            Log.e("adixtream", "Majorplay Error: ${e.message}") 
        }
    }

    [span_29](start_span)@JsonIgnoreProperties(ignoreUnknown = true)[span_29](end_span)
    data class NewMajorplayResponse(
        [span_30](start_span)@JsonProperty("url") val url: String? = null,[span_30](end_span)
        @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? [span_31](start_span)= null,[span_31](end_span)
        @JsonProperty("label") val label: String? [span_32](start_span)= null[span_32](end_span)
    )
    
    [span_33](start_span)@JsonIgnoreProperties(ignoreUnknown = true)[span_33](end_span)
    data class NewMajorSubtitle(
        [span_34](start_span)@JsonProperty("lang") val lang: String? = null,[span_34](end_span)
        @JsonProperty("label") val label: String? [span_35](start_span)= null,[span_35](end_span)
        @JsonProperty("path") val path: String? [span_36](start_span)= null[span_36](end_span)
    )
}
