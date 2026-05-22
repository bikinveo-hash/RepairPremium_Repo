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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val claimToken = url.substringAfter("claim=").substringBefore("&")
        if (claimToken.isEmpty()) return

        val safeHeaders = mapOf(
            "Origin" to "https://z1.idlixku.com",
            "Referer" to "https://z1.idlixku.com/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )

        // Harus text/plain persis seperti script Bash
        val reqBody = "{\"claim\":\"$claimToken\"}".toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())
        
        val playResText = app.post(
            url = "$mainUrl/api/play", 
            headers = safeHeaders, 
            requestBody = reqBody
        ).text
        
        val playRes = AppUtils.tryParseJson<NewMajorplayResponse>(playResText) ?: return

        playRes.subtitles?.forEach { sub ->
            subtitleCallback.invoke(SubtitleFile(sub.label ?: sub.lang ?: "Unknown", sub.path ?: return@forEach))
        }

        val configUrl = playRes.url ?: return
        val playlistContent = app.get(configUrl, headers = safeHeaders).text
        
        playlistContent.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val finalUrl = if (trimmed.startsWith("http")) trimmed else "$mainUrl$trimmed"
                callback.invoke(newExtractorLink(name, "Majorplay Stream", "$finalUrl&.m3u8", ExtractorLinkType.M3U8) { this.headers = safeHeaders })
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorplayResponse(@JsonProperty("url") val url: String? = null, @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorSubtitle(@JsonProperty("lang") val lang: String? = null, @JsonProperty("label") val label: String? = null, @JsonProperty("path") val path: String? = null)
}
