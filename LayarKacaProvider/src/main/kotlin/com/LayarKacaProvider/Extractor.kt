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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Data class untuk API
data class ChallengeResponse(val challenge_id: String, val nonce: String, val viewer_hint: String)
data class AttestRequest(
    val viewer_id: String,
    val device_id: String,
    val challenge_id: String,
    val nonce: String,
    val signature: String,
    val public_key: Map<String, Any>,
    val client: Map<String, Any>,
    val storage: Map<String, String>,
    val attributes: Map<String, String>
)
data class AttestResponse(val token: String, val viewer_id: String, val device_id: String)
data class PlaybackOuter(val playback: PlaybackData)
data class PlaybackData(
    val iv: String,
    val payload: String,
    val key_parts: List<String>
)
data class StreamContainer(val sources: List<StreamItem>)
data class StreamItem(val url: String, val label: String, val quality: String? = null)

open class F16Extractor : ExtractorApi() {
    override var name = "Cast"
    override var mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = true

    // Data statis dari browser (bisa di-hardcode)
    private val viewerId = "1993fdbe30bc4b35949faa647e5dc696"
    private val deviceId = "e72a8c5bd3da49bea2797f79cf6a363b"
    private val cookie = "$viewerId;$deviceId" // Cookie: byse_viewer_id=...; byse_device_id=...

    private val clientData = mapOf(
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
    )
    private val storageData = mapOf(
        "cookie" to viewerId,
        "local_storage" to viewerId,
        "indexed_db" to "$viewerId:$deviceId",
        "cache_storage" to "$viewerId:$deviceId"
    )
    private val attributesData = mapOf("entropy" to "high")

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        val videoId = url.substringAfter("/e/").substringBefore("?")
        val embedUrl = "$mainUrl/e/$videoId"

        val commonHeaders = mapOf(
            "User-Agent" to clientData["user_agent"].toString(),
            "X-Embed-Origin" to "playeriframe.sbs",
            "X-Embed-Parent" to embedUrl,
            "X-Embed-Referer" to "https://playeriframe.sbs/",
            "Origin" to mainUrl,
            "Referer" to embedUrl,
            "Cookie" to "byse_viewer_id=$viewerId; byse_device_id=$deviceId"
        )

        try {
            // 1. Dapatkan challenge
            val challengeRes = app.post("$mainUrl/api/videos/access/challenge", headers = commonHeaders).text
            val challenge = tryParseJson<ChallengeResponse>(challengeRes)
                ?: return sources.also { println("F16Extractor: Gagal parse challenge") }

            // 2. Generate ECDSA key pair
            val (publicKeyJwk, privateKey) = generateEcdsaKeyPair()

            // 3. Buat signature
            val dataToSign = "${challenge.challenge_id}${challenge.nonce}${viewerId}${deviceId}"
            val signature = signData(dataToSign.toByteArray(), privateKey)

            // 4. Attest
            val attestPayload = mapOf(
                "viewer_id" to viewerId,
                "device_id" to deviceId,
                "challenge_id" to challenge.challenge_id,
                "nonce" to challenge.nonce,
                "signature" to signature,
                "public_key" to publicKeyJwk,
                "client" to clientData,
                "storage" to storageData,
                "attributes" to attributesData
            )
            val attestRes = app.post("$mainUrl/api/videos/access/attest", json = attestPayload, headers = commonHeaders).text
            val attestData = tryParseJson<AttestResponse>(attestRes)
                ?: return sources.also { println("F16Extractor: Gagal attest") }

            // 5. Playback request
            val playbackPayload = mapOf(
                "fingerprint" to mapOf(
                    "token" to attestData.token,
                    "viewer_id" to viewerId,
                    "device_id" to deviceId,
                    "confidence" to 0.93
                )
            )
            val playbackRes = app.post("$mainUrl/api/videos/$videoId/embed/playback", json = playbackPayload, headers = commonHeaders).text
            val playback = tryParseJson<PlaybackOuter>(playbackRes)?.playback
                ?: return sources.also { println("F16Extractor: Gagal playback") }

            // 6. Dekripsi (seperti di skrip Termux)
            val decrypted = decryptAesGcm(playback)
            val streamData = tryParseJson<StreamContainer>(decrypted)
                ?: return sources.also { println("F16Extractor: Gagal parse stream") }

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

    private fun generateEcdsaKeyPair(): Pair<Map<String, Any>, java.security.PrivateKey> {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1") // P-256
        keyPairGenerator.initialize(ecSpec)
        val keyPair = keyPairGenerator.genKeyPair()
        val publicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val privateKey = keyPair.private

        // Konversi ke JWK
        val w = publicKey.w
        val x = Base64.encodeToString(w.affineX.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val y = Base64.encodeToString(w.affineY.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val jwk = mapOf<String, Any>(
            "crv" to "P-256",
            "ext" to true,
            "key_ops" to listOf("verify"),
            "kty" to "EC",
            "x" to x,
            "y" to y
        )
        return jwk to privateKey
    }

    private fun signData(data: ByteArray, privateKey: java.security.PrivateKey): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        val sig = signature.sign()
        return Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun decryptAesGcm(data: PlaybackData): String {
        val keyMaterial = data.key_parts
            .map { Base64.decode(it, Base64.URL_SAFE) }
            .reduce { acc, bytes -> acc + bytes }
        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val payloadWithTag = Base64.decode(data.payload, Base64.URL_SAFE)

        // Pisahkan tag (16 byte terakhir)
        val tag = payloadWithTag.copyOfRange(payloadWithTag.size - 16, payloadWithTag.size)
        val ciphertext = payloadWithTag.copyOfRange(0, payloadWithTag.size - 16)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyMaterial, "AES"), spec)
        return String(cipher.doFinal(ciphertext + tag), Charsets.UTF_8) // di sini kita gabungkan lagi karena doFinal butuh ciphertext + tag
    }
}
