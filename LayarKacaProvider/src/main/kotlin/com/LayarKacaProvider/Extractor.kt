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

// Data class untuk F16
data class ChallengeResponse(val challenge_id: String, val nonce: String, val viewer_hint: String)
data class AttestResponse(val token: String, val viewer_id: String, val device_id: String)
data class PlaybackOuter(val playback: PlaybackData)
data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
data class StreamContainer(val sources: List<StreamItem>)
data class StreamItem(val url: String, val label: String)
data class HownetworkResponse(val file: String?, val link: String?)

// ============================================================================
// 1. TURBOVIP EXTRACTOR (nama unik: Lk21TurboExtractor)
// ============================================================================
open class Lk21TurboExtractor : ExtractorApi() {
    override var name = "LK21 TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty()) return null
            val html = app.get("$mainUrl/t/$id", headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://playeriframe.sbs/"
            )).text

            var m3u8 = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) m3u8 = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank()) return null

            sources.add(newExtractorLink("LK21 TurboVIP", "TurboVIP HD", m3u8, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
            })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources
    }
}

// ============================================================================
// 2. P2P EXTRACTOR (tetap)
// ============================================================================
open class P2PExtractor : ExtractorApi() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        val id = url.substringAfter("id=").substringBefore("&")
        val bridgeUrl = "https://playeriframe.sbs/"
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36")
        
        try {
            app.get(url, headers = headers + ("Referer" to bridgeUrl))
            val resp = app.post("$mainUrl/api2.php?id=$id", headers = headers + (
                "Referer" to url) + ("Origin" to mainUrl) + ("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("r" to bridgeUrl, "d" to "cloud.hownetwork.xyz")).text
            val json = tryParseJson<HownetworkResponse>(resp)
            val video = json?.file ?: json?.link ?: return null
            sources.add(newExtractorLink("LK21 P2P", "P2P Player (480p)", video, ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.P480.value
            })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources
    }
}

// ============================================================================
// 3. CAST EXTRACTOR (nama unik: Lk21CastExtractor)
// ============================================================================
open class Lk21CastExtractor : ExtractorApi() {
    override var name = "LK21 Cast"
    override var mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = true

    private val viewerId = "1993fdbe30bc4b35949faa647e5dc696"
    private val deviceId = "e72a8c5bd3da49bea2797f79cf6a363b"

    private fun derToRaw(der: ByteArray): ByteArray {
        require(der[0] == 0x30.toByte())
        var off = 2
        val tLen = der[off].toInt() and 0xFF; off++
        require(der[off] == 0x02.toByte()); off++
        val rLen = der[off].toInt() and 0xFF; off++
        val r = der.copyOfRange(off, off + rLen); off += rLen
        require(der[off] == 0x02.toByte()); off++
        val sLen = der[off].toInt() and 0xFF; off++
        val s = der.copyOfRange(off, off + sLen)
        fun norm(v: ByteArray) = when {
            v.size > 32 -> v.copyOfRange(v.size - 32, v.size)
            v.size < 32 -> ByteArray(32 - v.size) + v
            else -> v
        }
        return norm(r) + norm(s)
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        val videoId = url.substringAfter("/e/").substringBefore("?")
        val embed = "$mainUrl/e/$videoId"
        val hdr = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "X-Embed-Origin" to "playeriframe.sbs", "X-Embed-Parent" to embed,
            "X-Embed-Referer" to "https://playeriframe.sbs/", "Origin" to mainUrl,
            "Referer" to embed, "Cookie" to "byse_viewer_id=$viewerId; byse_device_id=$deviceId"
        )
        try {
            val ch = tryParseJson<ChallengeResponse>(app.post("$mainUrl/api/videos/access/challenge", headers = hdr).text) ?: return sources
            val (key, jwk) = withContext(Dispatchers.IO) {
                val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.genKeyPair()
                val pub = kp.public as java.security.interfaces.ECPublicKey
                val x = Base64.encodeToString(pub.w.affineX.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val y = Base64.encodeToString(pub.w.affineY.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                kp.private to mapOf("crv" to "P-256", "ext" to true, "key_ops" to listOf("verify"), "kty" to "EC", "x" to x, "y" to y)
            }
            val sig = withContext(Dispatchers.IO) {
                val s = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update("${ch.challenge_id}${ch.nonce}$viewerId$deviceId".toByteArray()) }
                Base64.encodeToString(derToRaw(s.sign()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            }
            val tok = tryParseJson<AttestResponse>(app.post("$mainUrl/api/videos/access/attest", json = mapOf(
                "viewer_id" to viewerId, "device_id" to deviceId, "challenge_id" to ch.challenge_id, "nonce" to ch.nonce,
                "signature" to sig, "public_key" to jwk,
                "client" to mapOf( /* ... data client lengkap ... */ ),
                "storage" to mapOf("cookie" to viewerId, "local_storage" to viewerId, "indexed_db" to "$viewerId:$deviceId", "cache_storage" to "$viewerId:$deviceId"),
                "attributes" to mapOf("entropy" to "high")
            ), headers = hdr).text)?.token ?: return sources

            val pb = tryParseJson<PlaybackOuter>(app.post("$mainUrl/api/videos/$videoId/embed/playback", json = mapOf(
                "fingerprint" to mapOf("token" to tok, "viewer_id" to viewerId, "device_id" to deviceId, "confidence" to 0.93)
            ), headers = hdr).text)?.playback ?: return sources

            val dec = withContext(Dispatchers.IO) {
                val keyMat = pb.key_parts.map { Base64.decode(it, Base64.URL_SAFE) }.reduce { a, b -> a + b }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(keyMat, "AES"), GCMParameterSpec(128, Base64.decode(pb.iv, Base64.URL_SAFE)))
                }
                cipher.doFinal(Base64.decode(pb.payload, Base64.URL_SAFE))
            }
            val streams = tryParseJson<StreamContainer>(String(dec, Charsets.UTF_8))?.sources ?: return sources
            streams.forEach {
                sources.add(newExtractorLink("LK21 Cast", "Cast ${it.label}", it.url, ExtractorLinkType.M3U8) {
                    this.quality = when (it.label) { "1080p" -> Qualities.P1080.value; "720p" -> Qualities.P720.value; else -> Qualities.Unknown.value }
                    this.referer = mainUrl
                })
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources
    }
}
