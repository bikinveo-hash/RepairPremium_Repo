package com.LayarKacaProvider

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

val customUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
val magicReferer = "https://playeriframe.sbs/"

// ============================================================================
// 1. P2P EXTRACTOR (VIA API POST)
// ============================================================================
open class P2PExtractor : ExtractorApi() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false
    
    data class HownetworkResponse(val file: String?, val link: String?, val label: String?)

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val id = url.substringAfter("id=").substringBefore("&")
        val apiUrl = "$mainUrl/api2.php?id=$id"
        
        val headers = mapOf(
            "User-Agent" to customUserAgent,
            "Referer" to magicReferer,
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )
        val formBody = mapOf("r" to magicReferer, "d" to "cloud.hownetwork.xyz")
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            val response = app.post(apiUrl, headers = headers, data = formBody).text
            val json = tryParseJson<HownetworkResponse>(response)
            val videoUrl = json?.file ?: json?.link
   
            if (!videoUrl.isNullOrBlank()) {
                sources.add(
                    newExtractorLink(
                        source = name,
                        name = "P2P HD",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = magicReferer
                        this.quality = Qualities.Unknown.value
                        
                        // FIX 403: ExoPlayer WAJIB pakai Origin dan Referer yang benar
                        this.headers = mapOf(
                            "User-Agent" to customUserAgent,
                            "Origin" to mainUrl,
                            "Referer" to magicReferer
                        )
                    }
                )
            }
        } catch (e: Exception) { Log.e("P2P", e.message.toString()) }
        return sources
    }
}

// ============================================================================
// 2. TURBOVIP EXTRACTOR (VIA REGEX HTML DARI DATA CURL MU!)
// ============================================================================
open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            // Kita GET halaman aslinya persis seperti cURL yang kamu temukan
            val id = url.substringAfterLast("/")
            val targetUrl = "$mainUrl/t/$id"
            
            val response = app.get(targetUrl, headers = mapOf(
                "User-Agent" to customUserAgent,
                "Referer" to magicReferer
            )).text
            
            // Tangkap URL m3u8 yang bocor di HTML
            val m3u8Regex = Regex("""(https?://[^"'\s]*?\.m3u8[^"'\s]*)""")
            val m3u8Url = m3u8Regex.find(response)?.groupValues?.get(1)
            
            if (!m3u8Url.isNullOrBlank()) {
                sources.add(newExtractorLink(name, "Turbovip HD", m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "" // Turbovid tidak pakai referer
                    this.quality = Qualities.Unknown.value
                    
                    // KUNCI DARI CURL KAMU: Wajib pakai Origin!
                    this.headers = mapOf(
                        "User-Agent" to customUserAgent,
                        "Origin" to mainUrl
                    )
                })
            }
        } catch (e: Exception) { Log.e("Turbovid", e.message.toString()) }
        return sources
    }
}

// ============================================================================
// 3. CAST / F16 EXTRACTOR (VIA API JWT)
// ============================================================================
open class F16Extractor : ExtractorApi() {
    override var name = "F16"
    override var mainUrl = "https://f16px.com"
    override val requiresReferer = false

    data class F16Playback(val playback: PlaybackData?)
    data class PlaybackData(val iv: String?, val payload: String?, val key_parts: List<String>?)
    data class DecryptedSource(val url: String?, val label: String?)
    data class DecryptedResponse(val sources: List<DecryptedSource>?)

    private fun String.fixBase64(): String {
        var s = this.replace("-", "+").replace("_", "/")
        while (s.length % 4 != 0) s += "="
        return s
    }

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val videoId = url.substringAfter("/e/").substringBefore("?")
            val apiUrl = "$mainUrl/api/videos/$videoId/embed/playback"
            val pageUrl = "$mainUrl/e/$videoId"
            
            val viewerId = randomHex(32) 
            val deviceId = randomHex(32)
            val timestamp = System.currentTimeMillis() / 1000
            
            val jwtHeader = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" 
            val jwtPayload = """{"viewer_id":"$viewerId","device_id":"$deviceId","confidence":0.91,"iat":$timestamp,"exp":${timestamp + 600}}"""
            val jwtPayloadEncoded = Base64.encodeToString(jwtPayload.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val jwtSignature = randomHex(43)
            val token = "$jwtHeader.$jwtPayloadEncoded.$jwtSignature"

            val headers = mapOf(
                "User-Agent" to customUserAgent,
                "Referer" to pageUrl,
                "Origin" to mainUrl,
                "Content-Type" to "application/json",
                "Cookie" to "byse_viewer_id=$viewerId; byse_device_id=$deviceId",
                "x-embed-origin" to "playeriframe.sbs",
                "x-embed-parent" to pageUrl,
                "x-embed-referer" to magicReferer
            )

            val jsonPayload = mapOf("fingerprint" to mapOf("token" to token, "viewer_id" to viewerId, "device_id" to deviceId, "confidence" to 0.91))
            val responseText = app.post(apiUrl, headers = headers, json = jsonPayload).text
            val json = tryParseJson<F16Playback>(responseText)
            val pb = json?.playback

            if (pb != null && pb.payload != null && pb.iv != null && !pb.key_parts.isNullOrEmpty()) {
                val part1 = Base64.decode(pb.key_parts[0].fixBase64(), Base64.URL_SAFE)
                val part2 = Base64.decode(pb.key_parts[1].fixBase64(), Base64.URL_SAFE)
                val combinedKey = part1 + part2 

                val decryptedJson = decryptAesGcm(pb.payload, combinedKey, pb.iv)
                if (decryptedJson != null) {
                    val result = tryParseJson<DecryptedResponse>(decryptedJson)
                    result?.sources?.forEach { source ->
                        val streamUrl = source.url
                        if (!streamUrl.isNullOrBlank()) {
                            sources.add(newExtractorLink("CAST", "CAST HD", streamUrl, ExtractorLinkType.M3U8) {
                                this.referer = "" 
                                this.quality = getQualityFromName(source.label)
                                this.headers = mapOf("User-Agent" to customUserAgent, "Origin" to mainUrl)
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e("F16Extractor", "Error: ${e.message}") }
        return sources
    }

    private fun decryptAesGcm(encryptedBase64: String, keyBytes: ByteArray, ivBase64: String): String? {
        return try {
            val iv = Base64.decode(ivBase64.fixBase64(), Base64.URL_SAFE)
            val cipherText = Base64.decode(encryptedBase64.fixBase64(), Base64.URL_SAFE)
            val spec = GCMParameterSpec(128, iv)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}

// ============================================================================
// 4. HYDRAX EXTRACTOR (VIA REGEX HTML)
// ============================================================================
open class HydraxExtractor : ExtractorApi() {
    override var name = "Hydrax"
    override var mainUrl = "https://hydrax.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val response = app.get(url, headers = mapOf(
                "User-Agent" to customUserAgent,
                "Referer" to magicReferer
            )).text
            
            // Tangkap URL m3u8 yang bocor di HTML
            val m3u8Regex = Regex("""(https?://[^"'\s]*?\.m3u8[^"'\s]*)""")
            val m3u8Url = m3u8Regex.find(response)?.groupValues?.get(1)
            
            if (!m3u8Url.isNullOrBlank()) {
                sources.add(newExtractorLink(name, "Hydrax HD", m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to customUserAgent,
                        "Origin" to mainUrl
                    )
                })
            }
        } catch (e: Exception) { Log.e("Hydrax", e.message.toString()) }
        return sources
    }
}
