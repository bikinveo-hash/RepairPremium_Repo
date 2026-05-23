package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================================
// DATA CLASSES (digunakan oleh F16Extractor)
// ============================================================================
data class ChallengeResponse(val challenge_id: String, val nonce: String, val viewer_hint: String)
data class AttestResponse(val token: String, val viewer_id: String, val device_id: String)
data class PlaybackOuter(val playback: PlaybackData)
data class PlaybackData(
    val iv: String,
    val payload: String,
    val key_parts: List<String>
)
data class StreamContainer(val sources: List<StreamItem>)
data class StreamItem(val url: String, val label: String)

// ============================================================================
// 1. TURBOVIP EXTRACTOR (Parsing HTML - TERBUKTI di Termux & Python)
// ============================================================================
open class EmturbovidExtractor : ExtractorApi() {
    override var name = "TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty()) return null

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://playeriframe.sbs/"
            )

            val response = app.get("$mainUrl/t/$id", headers = headers)
            val html = response.text

            // Ekstraksi URL M3U8 dari data-hash atau urlPlay
            var m3u8Url = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8Url.isNullOrBlank()) {
                m3u8Url = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }

            if (m3u8Url.isNullOrBlank()) {
                println("EmturbovidExtractor: Gagal mengekstrak URL M3U8")
                return null
            }

            sources.add(
                newExtractorLink(
                    source = "TurboVIP",
                    name = "TurboVIP HD",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources
    }
}

// ============================================================================
// 2. P2P EXTRACTOR (TIDAK DIUBAH - Masih berfungsi)
// ============================================================================
data class HownetworkResponse(val file: String?, val link: String?)

open class P2PExtractor : ExtractorApi() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        val id = url.substringAfter("id=").substringBefore("&")
        val apiUrl = "$mainUrl/api2.php?id=$id"
        val bridgeUrl = "https://playeriframe.sbs/"
        
        val initHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Referer" to bridgeUrl
        )

        val apiHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Referer" to url,
            "Origin" to mainUrl,
            "Content-Type" to "application/x-www-form-urlencoded"
        )
        
        val formBody = mapOf("r" to bridgeUrl, "d" to "cloud.hownetwork.xyz")
        
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

// ============================================================================
// 3. F16 / CAST EXTRACTOR (ECDSA Attestation + AES-GCM Decryption)
// ============================================================================
open class F16Extractor : ExtractorApi() {
    override var name = "Cast"
    override var mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = true

    private val viewerId = "1993fdbe30bc4b35949faa647e5dc696"
    private val deviceId = "e72a8c5bd3da49bea2797f79cf6a363b"

    // Fungsi konversi DER ke raw (64 byte) - TERBUKTI DI PYTHON
    private fun derToRaw(derSig: ByteArray): ByteArray {
        if (derSig[0] != 0x30.toByte()) throw IllegalArgumentException("Invalid DER signature")
        var offset = 2
        val totalLen = derSig[offset].toInt() and 0xFF; offset++
        require(derSig[offset] == 0x02.toByte()); offset++
        val rLen = derSig[offset].toInt() and 0xFF; offset++
        val r = derSig.copyOfRange(offset, offset + rLen); offset += rLen
        require(derSig[offset] == 0x02.toByte()); offset++
        val sLen = derSig[offset].toInt() and 0xFF; offset++
        val s = derSig.copyOfRange(offset, offset + sLen)

        fun normalize(v: ByteArray): ByteArray {
            return when {
                v.size > 32 -> v.copyOfRange(v.size - 32, v.size)
                v.size < 32 -> ByteArray(32 - v.size) + v
                else -> v
            }
        }
        return normalize(r) + normalize(s)
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        val videoId = url.substringAfter("/e/").substringBefore("?")
        val embedUrl = "$mainUrl/e/$videoId"

        val commonHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "X-Embed-Origin" to "playeriframe.sbs",
            "X-Embed-Parent" to embedUrl,
            "X-Embed-Referer" to "https://playeriframe.sbs/",
            "Origin" to mainUrl,
            "Referer" to embedUrl,
            "Cookie" to "byse_viewer_id=$viewerId; byse_device_id=$deviceId"
        )

        try {
            // 1. Challenge
            val challengeRes = app.post("$mainUrl/api/videos/access/challenge", headers = commonHeaders).text
            val challenge = tryParseJson<ChallengeResponse>(challengeRes)
            if (challenge == null) {
                println("Cast: Gagal parse challenge")
                return sources
            }

            // 2. Generate keypair & signature di IO thread (hindari blocking)
            val attestPayload = withContext(Dispatchers.IO) {
                val keyPair = KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.genKeyPair()
                val pub = keyPair.public as java.security.interfaces.ECPublicKey
                val priv = keyPair.private

                val x = Base64.encodeToString(pub.w.affineX.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val y = Base64.encodeToString(pub.w.affineY.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val jwk = mapOf("crv" to "P-256", "ext" to true, "key_ops" to listOf("verify"), "kty" to "EC", "x" to x, "y" to y)

                val dataToSign = "${challenge.challenge_id}${challenge.nonce}$viewerId$deviceId"
                val sig = Signature.getInstance("SHA256withECDSA").apply {
                    initSign(priv)
                    update(dataToSign.toByteArray())
                }.let { derToRaw(it.sign()) }
                val signature = Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

                mapOf(
                    "viewer_id" to viewerId,
                    "device_id" to deviceId,
                    "challenge_id" to challenge.challenge_id,
                    "nonce" to challenge.nonce,
                    "signature" to signature,
                    "public_key" to jwk,
                    "client" to mapOf(
                        "user_agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                        "platform" to "Android",
                        "platform_version" to "13.0.0",
                        "model" to "CPH2235",
                        "ua_full_version" to "137.0.7337.0",
                        "brand_full_versions" to listOf(
                            mapOf("brand" to "Chromium", "version" to "137.0.7337.0"),
                            mapOf("brand" to "Not/A)Brand", "version" to "24.0.0.0")
                        ),
                        "pixel_ratio" to 3,
                        "screen_width" to 360,
                        "screen_height" to 800,
                        "color_depth" to 24,
                        "languages" to listOf("id-ID", "id", "en-US", "en"),
                        "timezone" to "Asia/Jayapura",
                        "hardware_concurrency" to 8,
                        "device_memory" to 8,
                        "touch_points" to 5,
                        "webgl_vendor" to "Google Inc. (Qualcomm)",
                        "webgl_renderer" to "ANGLE (Qualcomm, Adreno (TM) 618, OpenGL ES 3.2)",
                        "canvas_hash" to "NqM1SjPxaPA42KL3TDIfbzaTumFLWcJOzn0TJvJ1xcA",
                        "audio_hash" to "_VRYiH6_cygtD14eUnkys7AF3r7zCf769syVkS3GVGU",
                        "pointer_type" to "coarse,touch",
                        "extra" to mapOf(
                            "vendor" to "Google Inc.",
                            "appVersion" to "5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                        )
                    ),
                    "storage" to mapOf(
                        "cookie" to viewerId,
                        "local_storage" to viewerId,
                        "indexed_db" to "$viewerId:$deviceId",
                        "cache_storage" to "$viewerId:$deviceId"
                    ),
                    "attributes" to mapOf("entropy" to "high")
                )
            }

            // 3. Attest
            val attestRes = app.post("$mainUrl/api/videos/access/attest", json = attestPayload, headers = commonHeaders).text
            val token = tryParseJson<AttestResponse>(attestRes)?.token
            if (token == null) {
                println("Cast: Gagal attest")
                return sources
            }

            // 4. Playback
            val playbackPayload = mapOf(
                "fingerprint" to mapOf(
                    "token" to token,
                    "viewer_id" to viewerId,
                    "device_id" to deviceId,
                    "confidence" to 0.93
                )
            )
            val playbackRes = app.post("$mainUrl/api/videos/$videoId/embed/playback", json = playbackPayload, headers = commonHeaders).text
            val playback = tryParseJson<PlaybackOuter>(playbackRes)?.playback
            if (playback == null) {
                println("Cast: Gagal playback")
                return sources
            }

            // 5. Dekripsi (AES-256-GCM) - TERBUKTI DI PYTHON
            val decrypted = withContext(Dispatchers.IO) {
                val keyMaterial = playback.key_parts
                    .map { Base64.decode(it, Base64.URL_SAFE) }
                    .reduce { acc, bytes -> acc + bytes }
                val iv = Base64.decode(playback.iv, Base64.URL_SAFE)
                val payloadWithTag = Base64.decode(playback.payload, Base64.URL_SAFE)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyMaterial, "AES"), GCMParameterSpec(128, iv))
                cipher.doFinal(payloadWithTag) // Langsung gabung ciphertext+tag
            }
            val streamData = tryParseJson<StreamContainer>(String(decrypted, Charsets.UTF_8))
            if (streamData == null) {
                println("Cast: Gagal parse stream")
                return sources
            }

            // 6. Tambahkan sumber
            streamData.sources.forEach { source ->
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources
    }
}
