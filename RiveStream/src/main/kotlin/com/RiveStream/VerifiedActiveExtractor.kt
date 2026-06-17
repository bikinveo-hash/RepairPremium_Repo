package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.FormBody

class VerifiedActiveExtractor {

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

    suspend fun extractVidara(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Step 1: Validasi Halaman Embed Hulu
            val landingResponse = app.get(
                url = embedUrl,
                headers = mapOf("User-Agent" to userAgent)
            )
            val html = landingResponse.text

            if (html.contains("/api/stream")) {
                val fileCode = embedUrl.substringAfter("/e/").substringBefore("?")
                val baseDomain = embedUrl.substringBefore("/e/")
                
                // Step 2: Handshake API POST JSON
                val jsonPayload = VidaraPayload(filecode = fileCode, device = "android")
                val apiResponse = app.post(
                    url = "$baseDomain/api/stream",
                    json = jsonPayload,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to embedUrl,
                        "Content-Type" to "application/json"
                    )
                ).text

                val parsed = tryParseJson<VidaraResponse>(apiResponse)
                val streamUrl = parsed?.streamingUrl

                if (!streamUrl.isNullOrEmpty()) {
                    // PERBAIKAN: Properti referer dan quality wajib ditaruh di dalam block { }
                    callback(
                        newExtractorLink(
                            source = "Vidara",
                            name = "Vidara - 1080p",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$baseDomain/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun extractVidsST(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Step 1: Unduh Landing Page Raw Document
            val response = app.get(
                url = embedUrl,
                headers = mapOf("User-Agent" to userAgent)
            ).text

            // Step 2: Normalisasi Karakter Escape DOM Pre-Cleaning
            val normalizedHtml = response.replace("\\/", "/")

            // Step 3: Pemanenan Tautan Menggunakan Pola Pencarian Regex Kaku VidsST
            val mp4Regex = Regex("""const\s+url\s*=\s*["'](https?://[^"']+\.mp4)["']""")
            val streamUrl = mp4Regex.find(normalizedHtml)?.groupValues?.get(1)

            if (!streamUrl.isNullOrEmpty()) {
                val baseDomain = embedUrl.substringBefore("/e/")
                // PERBAIKAN: Properti referer dan quality wajib ditaruh di dalam block { }
                callback(
                    newExtractorLink(
                        source = "VidsST",
                        name = "VidsST - 1080p",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun extractSavefiles(embedUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Step 1: Isolasi Kode Berkas Dari Alamat Path Iframe
            val fileCode = embedUrl.substringAfter("/e/").substringBefore("?")
            val baseDomain = embedUrl.substringBefore("/e/")

            // Step 2: Pembangunan Payload Form Tradisional Urlencoded
            val formBody = FormBody.Builder()
                .add("op", "embed")
                .add("file_code", fileCode)
                .add("auto", "1")
                .add("referer", referer)
                .build()

            // Step 3: Ambil Respons Pemutar Asli Dari Endpoint /dl
            val dlResponse = app.post(
                url = "$baseDomain/dl",
                requestBody = formBody,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to embedUrl,
                    "Origin" to baseDomain,
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            ).text

            // Step 4: Saring Alamat Master Playlist Menggunakan Regex Bukti Lapangan
            val m3u8Regex = Regex("""https?://[^\s"'<>]+master\.m3u8[^\s"'<>]*""")
            val masterM3u8Url = m3u8Regex.find(dlResponse)?.value

            if (!masterM3u8Url.isNullOrEmpty()) {
                // PERBAIKAN: Properti referer dan quality wajib ditaruh di dalam block { }
                callback(
                    newExtractorLink(
                        source = "Savefiles",
                        name = "Savefiles - 720p",
                        url = masterM3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.P720.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ===== DATA CLASSES INTERNAL HOSTER =====
    data class VidaraPayload(
        @JsonProperty("filecode") val filecode: String,
        @JsonProperty("device") val device: String
    )

    data class VidaraResponse(
        @JsonProperty("streaming_url") val streamingUrl: String?
    )
}
