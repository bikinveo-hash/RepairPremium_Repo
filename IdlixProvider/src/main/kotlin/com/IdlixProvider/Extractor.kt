package com.lagradost.cloudstream3.plugins // Sesuaikan package

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class IdlixExtractor : ExtractorApi() {
    override val name = "JeniusPlay" // Nama ini yang akan muncul di daftar putar
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    // Data class untuk menangkap balasan JSON JeniusPlay
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
        // Ambil hash video dari ujung URL (misal: .../video/5062fa095d...)
        val hash = url.split("/").last()
        val originReferer = referer ?: "https://tv12.idlixku.com/"
        val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"

        // Eksekusi POST Request
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

        // Ambil securedLink (m3u8). Kalau kosong, pakai videoSource (txt)
        val finalUrl = response?.securedLink ?: response?.videoSource ?: return

        // Kirim ke Cloudstream
        callback.invoke(
            ExtractorLink(
                name = this.name,
                source = this.name,
                url = finalUrl,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                isM3u8 = response.hls == true || finalUrl.contains(".m3u8")
            )
        )
    }
}
