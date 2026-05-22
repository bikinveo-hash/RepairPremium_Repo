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
            "Accept" to "*/*",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
        )

        // Ubah string JSON menjadi RequestBody bertipe text/plain sesuai sistem webnya
        val reqBody = "{\"claim\":\"$claimToken\"}".toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())

        // Gunakan .text dan tryParseJson untuk menghindari error parsing yang ditelan secara diam-diam
        val playResText = app.post(
            url = "$mainUrl/api/play",
            headers = safeHeaders,
            requestBody = reqBody
        ).text

        val playRes = AppUtils.tryParseJson<NewMajorplayResponse>(playResText) ?: return

        // Ekstrak semua file subtitle dan berikan ke callback
        playRes.subtitles?.forEach { sub ->
            val lang = sub.label ?: sub.lang ?: "Unknown"
            val subUrl = sub.path ?: return@forEach
            subtitleCallback.invoke(SubtitleFile(lang, subUrl))
        }

        val configUrl = playRes.url ?: return
        
        // Memastikan Cloudstream mengenalinya sebagai format streaming tanpa gagal di ExoPlayer
        val streamUrl = if (configUrl.contains(".m3u8")) configUrl else "$configUrl&ext=.m3u8"

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Majorplay Premium",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = safeHeaders
            }
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorplayResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorSubtitle(
        @JsonProperty("lang") val lang: String? = null, 
        @JsonProperty("label") val label: String? = null, 
        @JsonProperty("path") val path: String? = null
    )
}
