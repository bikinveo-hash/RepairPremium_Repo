package com.LayarKacaProvider

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// =========================================================================
// DATA CLASSES UNTUK API (ANTI-MINIFY BUG)
// =========================================================================
data class HowNetworkResponse(
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("file") val file: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("title") val title: String?
)

data class CastChalResp(
    @JsonProperty("nonce") val nonce: String?, 
    @JsonProperty("challenge_id") val challenge_id: String?
)
data class CastAttestResp(
    @JsonProperty("token") val token: String?
)
data class CastPbResp(
    @JsonProperty("playback") val playback: CastPlaybackInfo?
)
data class CastPlaybackInfo(
    @JsonProperty("iv") val iv: String?, 
    @JsonProperty("payload") val payload: String?, 
    @JsonProperty("key_parts") val key_parts: List<String>?
)
data class CastDecrypted(
    @JsonProperty("sources") val sources: List<CastSource>?
)
data class CastSource(
    @JsonProperty("url") val url: String?, 
    @JsonProperty("label") val label: String?, 
    @JsonProperty("type") val type: String?
)

val mapper: ObjectMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// =========================================================================
// EXTRACTOR 1: TURBO VIP
// =========================================================================
open class Lk21TurboExtractor : ExtractorApi() {
    override var name      = "LK21 TurboVIP"
    override var mainUrl   = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty()) return null

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Referer"    to "https://playeriframe.sbs/"
            )

            val response = app.get("$mainUrl/t/$id", headers = headers)
            val html     = response.text

            var m3u8Url = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8Url.isNullOrBlank()) {
                m3u8Url = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }
            if (m3u8Url.isNullOrBlank()) return null

            val isMp4 = m3u8Url.endsWith(".mp4", ignoreCase = true)
            val type  = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8

            sources.add(
                newExtractorLink(
                    source = "LK21 TurboVIP",
                    name   = "TurboVIP HD",
                    url    = m3u8Url,
                    type   = type
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin"  to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }
}

// =========================================================================
// EXTRACTOR 2: HOW NETWORK (P2P)
// =========================================================================
open class HowNetworkExtractor : ExtractorApi() {
    override var name = "LK21 HowNetwork"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("id=").substringBefore("&")
            if (id.isEmpty()) return null

            val response = app.post(
                url = "$mainUrl/api2.php?id=$id",
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to url,
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                ),
                data = mapOf(
                    "r" to "https://playeriframe.sbs/",
                    "d" to "cloud.hownetwork.xyz"
                )
            ).text

            val parsedRes = try { mapper.readValue(response, HowNetworkResponse::class.java) } catch(e: Exception) { null }
            val m3u8Url = parsedRes?.file

            if (!m3u8Url.isNullOrBlank()) {
                sources.add(
                    newExtractorLink(
                        source = "LK21 HowNetwork",
                        name = "HowNetwork HD",
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }
}

// =========================================================================
// EXTRACTOR 3: CAST HD (Anti-Bot Bypass FileLion/Byse V19)
// =========================================================================
open class CastExtractor : ExtractorApi() {
    override var name = "CAST HD"
    override var mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = false

    private fun b64url(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun b64urlDecode(s: String): ByteArray {
        var standardized = s.replace("-", "+").replace("_", "/")
        val padding = 4 - (standardized.length % 4)
        if (padding != 4) {
            standardized += "=".repeat(padding)
        }
        return Base64.decode(standardized, Base64.DEFAULT)
    }
    private fun getRandomBytes(size: Int): ByteArray = ByteArray(size).apply { java.security.SecureRandom().nextBytes(this) }

    private fun derToRaw(der: ByteArray): ByteArray {
        var offset = 2
        val rLength = der[offset + 1].toInt()
        val rOffset = offset + 2
        offset += 2 + rLength
        val sLength = der[offset + 1].toInt()
        val sOffset = offset + 2
        
        val rStr = der.copyOfRange(rOffset, rOffset + rLength).dropWhile { it == 0.toByte() }.toByteArray()
        val sStr = der.copyOfRange(sOffset, sOffset + sLength).dropWhile { it == 0.toByte() }.toByteArray()
        
        val rPadded = ByteArray(32) { 0 }
        val sPadded = ByteArray(32) { 0 }
        System.arraycopy(rStr, 0, rPadded, 32 - rStr.size, rStr.size)
        System.arraycopy(sStr, 0, sPadded, 32 - sStr.size, sStr.size)
        
        return rPadded + sPadded
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        val videoId = url.substringAfterLast("/")
        
        val commonHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Origin" to mainUrl,
            "Referer" to url,
            "X-Embed-Origin" to "playeriframe.sbs",
            "X-Embed-Parent" to url,
            "X-Embed-Referer" to "https://playeriframe.sbs/"
        )

        try {
            val chalRes = app.post("$mainUrl/api/videos/access/challenge", headers = commonHeaders)
            val chalJson = try { mapper.readValue(chalRes.text, CastChalResp::class.java) } catch(e:Exception){ null } ?: return null
            val nonce = chalJson.nonce ?: return null
            val cid = chalJson.challenge_id ?: return null

            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(kp.private)
            sig.update(nonce.toByteArray(Charsets.UTF_8))
            val rawSignature = derToRaw(sig.sign())

            val pub = kp.public as ECPublicKey
            var xBytes = pub.w.affineX.toByteArray()
            var yBytes = pub.w.affineY.toByteArray()
            
            if (xBytes.size > 32) xBytes = xBytes.copyOfRange(xBytes.size - 32, xBytes.size)
            if (yBytes.size > 32) yBytes = yBytes.copyOfRange(yBytes.size - 32, yBytes.size)
            if (xBytes.size < 32) xBytes = ByteArray(32 - xBytes.size) { 0 } + xBytes
            if (yBytes.size < 32) yBytes = ByteArray(32 - yBytes.size) { 0 } + yBytes

            val attestPayloadStr = mapper.writeValueAsString(mapOf(
                "viewer_id" to b64url(getRandomBytes(16)),
                "device_id" to b64url(getRandomBytes(16)),
                "challenge_id" to cid,
                "nonce" to nonce,
                "signature" to b64url(rawSignature),
                "public_key" to mapOf(
                    "crv" to "P-256", "ext" to true, "key_ops" to listOf("verify"), "kty" to "EC",
                    "x" to b64url(xBytes), "y" to b64url(yBytes)
                ),
                "client" to mapOf("user_agent" to commonHeaders["User-Agent"]!!),
                "attributes" to mapOf("entropy" to "high")
            ))

            val attestRes = app.post("$mainUrl/api/videos/access/attest", headers = commonHeaders, requestBody = attestPayloadStr.toRequestBody("application/json".toMediaTypeOrNull()))
            val attestJson = try { mapper.readValue(attestRes.text, CastAttestResp::class.java) } catch(e:Exception){ null } ?: return null
            val token = attestJson.token ?: return null

            val pbPayloadStr = mapper.writeValueAsString(mapOf("fingerprint" to mapOf("token" to token)))
            val pbRes = app.post("$mainUrl/api/videos/$videoId/embed/playback", headers = commonHeaders, requestBody = pbPayloadStr.toRequestBody("application/json".toMediaTypeOrNull()))
            
            val pbResp = try { mapper.readValue(pbRes.text, CastPbResp::class.java)?.playback } catch(e:Exception){ null } ?: return null
            val iv = b64urlDecode(pbResp.iv ?: return null)
            val payload = b64urlDecode(pbResp.payload ?: return null)
            val keyParts = pbResp.key_parts ?: return null

            val keysToTest = mutableListOf<ByteArray>()
            val chunks16 = mutableListOf<ByteArray>()

            for (p in keyParts) {
                if (p.length == 32) keysToTest.add(p.toByteArray(Charsets.UTF_8))
                try {
                    val dec = b64urlDecode(p)
                    if (dec.size == 32) keysToTest.add(dec)
                    else if (dec.size == 16) chunks16.add(dec)
                } catch (e: Exception) {}
            }

            for (i in chunks16.indices) {
                for (j in chunks16.indices) {
                    if (i != j) keysToTest.add(chunks16[i] + chunks16[j])
                }
            }

            var realUrl: String? = null
            var qualityLabel = "HD"

            for (keyBytes in keysToTest) {
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(128, iv)
                    val secretKey = SecretKeySpec(keyBytes, "AES")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                    val decrypted = cipher.doFinal(payload)
                    
                    val jsonString = String(decrypted, Charsets.UTF_8)
                    val parsedData = mapper.readValue(jsonString, CastDecrypted::class.java)
                    
                    realUrl = parsedData?.sources?.firstOrNull()?.url
                    qualityLabel = parsedData?.sources?.firstOrNull()?.label ?: "HD"
                    
                    if (realUrl != null) break 
                } catch (e: Exception) {}
            }

            if (realUrl != null) {
                sources.add(
                    newExtractorLink(
                        source = "CAST HD",
                        name = "CAST $qualityLabel",
                        url = realUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        
                        // FIX SUPER PENTING: Melempar User-Agent aslinya ke ExoPlayer!
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "User-Agent" to commonHeaders["User-Agent"]!!
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }
}
