package com.RiveStream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.SubtitleFile
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class RiveVidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            val landingResponse = app.get(url, headers = mapOf("User-Agent" to userAgent)).text

            if (landingResponse.contains("/api/stream")) {
                val fileCode = url.substringAfter("/e/").substringBefore("?")
                
                // Perbaikan: Merakit RequestBody JSON kaku sesuai regulasi asli MainAPI.kt
                val jsonString = mapOf("filecode" to fileCode, "device" to "android").toJson()
                val requestBody = jsonString.toRequestBody("application/json".toMediaTypeOrNull())

                val apiResponse = app.post(
                    url = "$mainUrl/api/stream",
                    requestBody = requestBody,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url
                    )
                ).text

                val parsed = tryParseJson<VidaraResponse>(apiResponse)
                val streamUrl = parsed?.streamingUrl

                if (!streamUrl.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name - 1080p",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class VidaraResponse(
        @JsonProperty("streaming_url") val streamingUrl: String?
    )
}

class RiveVidsST : ExtractorApi() {
    override val name = "VidsST"
    override val mainUrl = "https://vids.st"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            val response = app.get(url, headers = mapOf("User-Agent" to userAgent)).text
            val normalizedHtml = response.replace("\\/", "/")

            val mp4Regex = Regex("""const\s+url\s*=\s*["'](https?://[^"']+\.mp4)["']""")
            val streamUrl = mp4Regex.find(normalizedHtml)?.groupValues?.get(1)

            if (!streamUrl.isNullOrEmpty()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - 1080p",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class RiveSavefiles : ExtractorApi() {
    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            val fileCode = url.substringAfter("/e/").substringBefore("?")
            val actualReferer = referer ?: "https://primesrc.me/"

            val formBody = FormBody.Builder()
                .add("op", "embed")
                .add("file_code", fileCode)
                .add("auto", "1")
                .add("referer", actualReferer)
                .build()

            val dlResponse = app.post(
                url = "$mainUrl/dl",
                requestBody = formBody,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            ).text

            val m3u8Regex = Regex("""https?://[^\s"'<>]+master\.m3u8[^\s"'<>]*""")
            val masterM3u8Url = m3u8Regex.find(dlResponse)?.value

            if (!masterM3u8Url.isNullOrEmpty()) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name - 720p",
                        url = masterM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P720.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
