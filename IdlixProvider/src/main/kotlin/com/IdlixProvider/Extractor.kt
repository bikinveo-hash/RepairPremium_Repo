package com.lagradost.cloudstream3.plugins // Sesuaikan packagenya

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class IdlixExtractor : ExtractorApi() {
    override val name = "JeniusPlay"
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    data class JeniusResponse(
        @JsonProperty("securedLink") val securedLink: String?,
        @JsonProperty("videoSource") val videoSource: String?,
        @JsonProperty("hls") val hls: Boolean?
    )

    override suspend fun getUrl(
        url: String, 
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hash = url.split("/").last()
        val originReferer = referer ?: "https://tv12.idlixku.com/"
        val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"

        val response = app.post(
            url = apiUrl,
            headers = mapOf(
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*"
            ),
            data = mapOf(
                "hash" to hash,
                "r" to originReferer
            ),
            referer = originReferer
        ).parsedSafe<JeniusResponse>()

        val finalUrl = response?.securedLink ?: response?.videoSource ?: return

        // [DIPERBAIKI] Menggunakan pemanggilan aman (?.) 
        val isM3u8Url = response?.hls == true || finalUrl.contains(".m3u8")

        // [DIPERBAIKI] Menggunakan parameter 'type' menggantikan 'isM3u8' yang deprecated
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                type = if (isM3u8Url) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        )
    }
}
