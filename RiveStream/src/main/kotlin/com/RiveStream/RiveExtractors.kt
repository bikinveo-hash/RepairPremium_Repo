package com.RiveStream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// ─────────────────────────────────────────────────────────────────────────────
// RiveVidara — https://vidara.so
// Flow: GET landing → POST /api/stream {filecode, device} → streaming_url M3U8
// ─────────────────────────────────────────────────────────────────────────────
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
                val jsonString = mapOf("filecode" to fileCode, "device" to "android").toJson()
                val requestBody = jsonString.toRequestBody("application/json".toMediaTypeOrNull())

                val apiResponse = app.post(
                    url = "$mainUrl/api/stream",
                    requestBody = requestBody,
                    headers = mapOf("User-Agent" to userAgent, "Referer" to url)
                ).text

                // ✅ FIX: Menggunakan Regex untuk mengekstrak streaming_url agar kebal dari Proguard Obfuscation & ClassLoader Mismatch
                val streamUrl = Regex("""(?i)"streaming_url"\s*:\s*"([^"]+)"""").find(apiResponse)?.groupValues?.get(1)

                if (!streamUrl.isNullOrEmpty()) {
                    callback(newExtractorLink(
                        source = this.name,
                        name = "$name - 1080p",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    })
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RiveVidsST — https://vids.st
// Flow: GET page → normalize \/ → regex `const url = "..."` → MP4
// ─────────────────────────────────────────────────────────────────────────────
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
            val html = app.get(url, headers = mapOf("User-Agent" to userAgent)).text
            val normalized = html.replace("\\/", "/")
            val mp4Regex = Regex("""const\s+url\s*=\s*["'](https?://[^"']+\.mp4)["']""")
            val streamUrl = mp4Regex.find(normalized)?.groupValues?.get(1)

            if (!streamUrl.isNullOrEmpty()) {
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - 1080p",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                })
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RiveSavefiles — https://savefiles.com
// Flow: POST /dl {op, file_code, auto, referer} → regex master.m3u8
// ─────────────────────────────────────────────────────────────────────────────
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

            val formBody = okhttp3.FormBody.Builder()
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
            val masterM3u8 = m3u8Regex.find(dlResponse)?.value

            if (!masterM3u8.isNullOrEmpty()) {
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - 720p",
                    url = masterM3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P720.value
                })
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RiveLizer — https://lizer123.site
// Flow: GET /getm3u8/{id} → redirect atau JSON → M3U8
// ─────────────────────────────────────────────────────────────────────────────
class RiveLizer : ExtractorApi() {
    override val name = "Lizer"
    override val mainUrl = "https://lizer123.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to (referer ?: "$mainUrl/"),
                    "Accept" to "*/*"
                ),
                allowRedirects = true
            )

            val finalUrl = response.url
            val body = response.text

            // Cek 1: apakah response URL sudah berupa M3U8 langsung (redirect)
            if (finalUrl.contains(".m3u8")) {
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - Auto",
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
                return
            }

            // Cek 2: body berisi M3U8 URL (JSON atau plain text)
            val m3u8Regex = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            val m3u8Url = m3u8Regex.find(body)?.value

            if (!m3u8Url.isNullOrEmpty()) {
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - Auto",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
                return
            }

            // Cek 3: body berisi MP4
            val mp4Regex = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
            val mp4Url = mp4Regex.find(body)?.value

            if (!mp4Url.isNullOrEmpty()) {
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - Auto",
                    url = mp4Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
            }

        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }
}
