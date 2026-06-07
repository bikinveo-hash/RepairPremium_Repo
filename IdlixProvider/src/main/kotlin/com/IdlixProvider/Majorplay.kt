package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
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

        // FIX #8: Gunakan referer dari parameter jika tersedia, fallback ke idlixku
        val effectiveReferer = if (!referer.isNullOrBlank()) referer else "https://z2.idlixku.com/"
        val effectiveOrigin = effectiveReferer.trimEnd('/')
            .let { runCatching { java.net.URI(it).let { u -> "${u.scheme}://${u.host}" } }.getOrDefault("https://z2.idlixku.com") }

        val safeHeaders = mapOf(
            "Origin" to effectiveOrigin,
            "Referer" to effectiveReferer,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )

        // FIX #2: Gunakan application/json karena body-nya adalah JSON object
        val jsonMediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
        val requestBodyData = mapOf("claim" to claimToken).toJson()
            .toRequestBody(jsonMediaType)

        val responseText = app.post(
            url = "$mainUrl/api/play",
            // FIX #2: Header Content-Type sekarang konsisten dengan body (JSON)
            headers = safeHeaders,
            requestBody = requestBodyData
        ).text

        val response = AppUtils.parseJson<NewMajorplayResponse>(responseText)

        val masterConfigUrl = response.url ?: return

        // Handle subtitles
        response.subtitles?.forEach { sub ->
            val subUrl = sub.path ?: return@forEach
            val lang = sub.label ?: sub.lang ?: "Indo"
            subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
        }

        // FIX #3: Hapus trick "&.m3u8" yang tidak standar dan bisa merusak URL.
        // ExtractorLinkType.M3U8 sudah cukup untuk memberi tahu player bahwa ini M3U8.
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Majorplay Auto Quality",
                url = masterConfigUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = safeHeaders
                this.referer = effectiveReferer
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// FIX #5: Data class dipindah ke luar class agar lebih sesuai konvensi Cloudstream
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
