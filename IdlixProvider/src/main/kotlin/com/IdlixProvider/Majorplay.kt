package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.* import com.lagradost.cloudstream3.utils.*
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
        val claimToken = url.substringAfter("claim=").substringBefore("&")
        if (claimToken.isEmpty()) return

        val safeHeaders = mapOf(
            "Origin" to "https://z1.idlixku.com",
            "Referer" to "https://z1.idlixku.com/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )

        val textMediaType = "text/plain".toMediaTypeOrNull()
        val requestBodyData = mapOf("claim" to claimToken).toJson().toRequestBody(textMediaType)

        val response = app.post(
            url = "$mainUrl/api/play",
            headers = safeHeaders.plus("Content-Type" to "text/plain"),
            requestBody = requestBodyData
        ).parsedSafe<NewMajorplayResponse>() ?: return

        val masterConfigUrl = response.url ?: return
        
        // Pemuatan file subtitle resmi bawaan web secara tradisional
        val subtitles = response.subtitles
        if (subtitles != null) {
            for (sub in subtitles) {
                val subUrl = sub.path ?: continue
                val lang = sub.label ?: sub.lang ?: "Indo"
                subtitleCallback.invoke(
                    newSubtitleFile(lang, subUrl)
                )
            }
        }
        
        // TRIK FINISH: Menambahkan &.m3u8 di akhir agar lolos dari penyaringan Cloudstream
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
            }
        )
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
