package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
        // Mengambil hash video dari URL iframe
        val hash = if (url.contains("data=")) {
            url.substringAfter("data=").substringBefore("&")
        } else {
            url.split("/").last()
        }

        // [DIPERBAIKI] Deteksi domain dengan sangat aman (apapun path foldernya)
        val domain = if (url.startsWith("http")) {
            url.split("/").let { "${it[0]}//${it[2]}" }
        } else {
            mainUrl
        }

        val apiUrl = "$domain/player/index.php?data=$hash&do=getVideo"

        // Menembak API JeniusPlay persis seperti skrip Python yang sukses
        val response = app.post(
            url = apiUrl,
            headers = mapOf(
                "Origin" to domain,
                "Referer" to "$domain/", 
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36",
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded"
            ),
            data = mapOf(
                "hash" to hash,
                "r" to "https://tv12.idlixku.com/" // Referer asli web IDLIX
            )
        ).parsedSafe<JeniusResponse>()

        val finalUrl = response?.securedLink ?: response?.videoSource ?: return

        val isM3u8Url = response?.hls == true || finalUrl.contains(".m3u8")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                type = if (isM3u8Url) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$domain/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
