package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

// ============================================================================
// 1. TURBOVIP EXTRACTOR (Bypass Cloudflare) - SEMENTARA MASIH WEBVIEW
// ============================================================================
open class EmturbovidExtractor : ExtractorApi() {
    override var name = "TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://playeriframe.sbs/"
            )
            
            val interceptorRegex = Regex("(?i)m3u8")
            val response = app.get(url, headers = headers, interceptor = WebViewResolver(interceptorRegex))
            val videoUrl = response.url

            if (videoUrl.contains("m3u8", ignoreCase = true)) {
                sources.add(
                    newExtractorLink(
                        source = "TurboVIP",
                        name = "TurboVIP HD",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }
}

// ============================================================================
// 2. CAST / F16PX EXTRACTOR (PURE HTTP - BYPASS WEB CRYPTO)
// ============================================================================
open class F16Extractor : ExtractorApi() {
    override var name = "Cast"
    override var mainUrl = "https://weneverbeenfree.com" // Domain mirror terbaru
    override val requiresReferer = true

    // Data Sidik Jari (Fingerprint) hasil sniff
    private val deviceId = "e72a8c5bd3da49bea2797f79cf6a363b"
    private val viewerId = "1993fdbe30bc4b35949faa647e5dc696"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val videoId = url.substringAfter("/e/").substringBefore("?")
        val embedUrl = "$mainUrl/e/$videoId"
        val sources = mutableListOf<ExtractorLink>()

        val commonHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "X-Embed-Origin" to "playeriframe.sbs",
            "X-Embed-Parent" to embedUrl,
            "X-Embed-Referer" to "https://playeriframe.sbs/",
            "Origin" to mainUrl,
            "Referer" to embedUrl
        )

        try {
            // Langkah 1: Ambil Token via API Attest
            val attestData = mapOf(
                "viewer_id" to viewerId,
                "device_id" to deviceId,
                "challenge_id" to "ZG1VcdAXnln4k3-9E9a4s4d_",
                "nonce" to "OafveDoyo4EL24yEVnZAK_3cUaOxGQaDScjvVHqMHYg",
                "signature" to "K2ALbVOD6cZd67WTWSsaciQIwo4ALrWb5YuuMFxTTW_z4f0GlmtQ68f9u5P_P-5Wfi9VsNJngRYN1sP_8sIDaA",
                "client" to mapOf(
                    "user_agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                    "platform" to "Android",
                    "platform_version" to "13.0.0",
                    "model" to "CPH2235"
                ),
                "storage" to mapOf("cookie" to viewerId),
                "attributes" to mapOf("entropy" to "high")
            )

            val attestResponse = app.post("$mainUrl/api/videos/access/attest", json = attestData, headers = commonHeaders).text
            val token = tryParseJson<AttestToken>(attestResponse)?.token ?: return null

            // Langkah 2: Request Playback Data
            val playbackRequest = mapOf(
                "fingerprint" to mapOf(
                    "token" to token,
                    "viewer_id" to viewerId,
                    "device_id" to deviceId,
                    "confidence" to 0.91
                )
            )

            val playbackResponse = app.post("$mainUrl/api/videos/$videoId/embed/playback", json = playbackRequest, headers = commonHeaders).text
            val playback = tryParseJson<PlaybackOuter>(playbackResponse)?.playback ?: return null

            // Langkah 3: Dekripsi AES-256-GCM
            val decryptedJson = decryptAesGcm(playback)
            val streamData = tryParseJson<StreamContainer>(decryptedJson)

            streamData?.sources?.forEach { source ->
                sources.add(
                    newExtractorLink(
                        source = "Cast VIP",
                        name = "Cast ${source.label}",
                        url = source.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = when (source.label) {
                            "1080p" -> Qualities.P1080.value
                            "720p" -> Qualities.P720.value
                            else -> Qualities.Unknown.value
                        }
                        this.referer = mainUrl
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }

    private fun decryptAesGcm(data: PlaybackData): String {
        // Logic 'Un' di JS: Gabungkan key_parts menjadi Master Key 32 bytes
        val masterKey = data.key_parts
            .map { Base64.decode(it, Base64.URL_SAFE) }
            .reduce { acc, bytes -> acc + bytes }

        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val encryptedPayload = Base64.decode(data.payload, Base64.URL_SAFE)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Tag length di WebCrypto biasanya 128 bit (16 bytes)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), spec)
        
        return String(cipher.doFinal(encryptedPayload), Charsets.UTF_8)
    }

    // Data Classes untuk JSON Parsing
    data class AttestToken(val token: String?)
    data class PlaybackOuter(val playback: PlaybackData?)
    data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
    data class StreamContainer(val sources: List<StreamItem>?)
    data class StreamItem(val url: String, val label: String)
}

// ============================================================================
[span_0](start_span)// 3. P2P EXTRACTOR (TIDAK DIOTAK-ATIK)[span_0](end_span)
// ============================================================================
open class P2PExtractor : ExtractorApi() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false
    
    data class HownetworkResponse(
        val file: String?, 
        val link: String?, 
        val label: String?,
        val type: String?,
        val title: String?
    )

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val id = url.substringAfter("id=").substringBefore("&")
        val apiUrl = "$mainUrl/api2.php?id=$id"
        val bridgeUrl = "https://playeriframe.sbs/"
        
        val initHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Referer" to bridgeUrl,
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "iframe",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        val apiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Referer" to url,
            "Origin" to mainUrl,
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        
        val formBody = mapOf(
            "r" to bridgeUrl,
            "d" to "cloud.hownetwork.xyz"
        )
        
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            app.get(url, headers = initHeaders)
            val response = app.post(apiUrl, headers = apiHeaders, data = formBody).text
            val json = tryParseJson<HownetworkResponse>(response)
            val videoUrl = json?.file ?: json?.link
   
            if (!videoUrl.isNullOrBlank()) {
                sources.add(
                    newExtractorLink(
                        source = "LK21 P2P", 
                        name = "P2P Player (480p)", 
                        url = videoUrl, 
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P480.value
                    }
                )
            }
        } catch (e: Exception) { 
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace() 
        }
        return sources
    }
}
