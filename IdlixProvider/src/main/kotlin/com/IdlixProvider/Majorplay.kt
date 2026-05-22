package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    // =========================================================================
    // SEPAKAT DENGAN BLUEPRINT EXTRACTORAPI & MAINAPI SECARA MUTLAK
    // =========================================================================
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

        // Memaksa pengiriman dengan tipe Media text/plain terenkripsi melalui parameter RequestBody
        val textMediaType = "text/plain".toMediaTypeOrNull()
        val requestBodyData = mapOf("claim" to claimToken).toJson().toRequestBody(textMediaType)

        val response = app.post(
            url = "$mainUrl/api/play",
            headers = safeHeaders,
            requestBody = requestBodyData
        ).parsedSafe<NewMajorplayResponse>() ?: return

        val masterConfigUrl = response.url ?: return
        
        // AMBIL JALUR SUBTITLE: Diproduksi lewat metode pembuatan internal 'newSubtitleFile' yang sah
        response.subtitles?.forEach { sub ->
            val subUrl = sub.path ?: return@forEach
            val lang = sub.label ?: sub.lang ?: "Indo"
            subtitleCallback.invoke(
                newSubtitleFile(lang, subUrl)
            )
        }
        
        // PENGIRIMAN STREAMING: Menyisipkan konfigurasi header dan referer di dalam blok inisialisasi lambda
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Majorplay Auto Quality",
                url = masterConfigUrl,
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
