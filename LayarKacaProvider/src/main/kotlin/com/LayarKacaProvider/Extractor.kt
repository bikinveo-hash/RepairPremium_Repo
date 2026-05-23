package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.SubtitleFile
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://playeriframe.sbs/")
            val response = app.get(url, headers = headers, interceptor = WebViewResolver(Regex("(?i)m3u8")))
            if (response.url.contains("m3u8", true)) {
                callback.invoke(newExtractorLink(name, "TurboVIP HD", response.url, ExtractorLinkType.M3U8) { this.referer = mainUrl })
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}

open class F16Extractor : ExtractorApi() {
    override var name = "Cast"
    override var mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = url.substringAfter("/e/").substringBefore("?")
        val embedUrl = "$mainUrl/e/$videoId"
        val headers = mapOf("User-Agent" to USER_AGENT, "X-Embed-Origin" to "playeriframe.sbs", "X-Embed-Parent" to embedUrl, "Referer" to embedUrl)

        try {
            val attestData = mapOf("viewer_id" to "1993fdbe30bc4b35949faa647e5dc696", "device_id" to "e72a8c5bd3da49bea2797f79cf6a363b", "challenge_id" to "ZG1VcdAXnln4k3-9E9a4s4d_", "nonce" to "OafveDoyo4EL24yEVnZAK_3cUaOxGQaDScjvVHqMHYg", "signature" to "K2ALbVOD6cZd67WTWSsaciQIwo4ALrWb5YuuMFxTTW_z4f0GlmtQ68f9u5P_P-5Wfi9VsNJngRYN1sP_8sIDaA", "client" to mapOf("platform" to "Android"))
            val token = app.post("$mainUrl/api/videos/access/attest", json = attestData, headers = headers).parsedSafe<AttestToken>()?.token ?: return

            val pbRequest = mapOf("fingerprint" to mapOf("token" to token, "viewer_id" to "1993fdbe30bc4b35949faa647e5dc696", "device_id" to "e72a8c5bd3da49bea2797f79cf6a363b", "confidence" to 0.91))
            val playback = app.post("$mainUrl/api/videos/$videoId/embed/playback", json = pbRequest, headers = headers).parsedSafe<PlaybackOuter>()?.playback ?: return

            val decrypted = decryptAesGcm(playback)
            tryParseJson<StreamContainer>(decrypted)?.sources?.forEach { source ->
                callback.invoke(newExtractorLink("Cast VIP", "Cast ${source.label}", source.url, ExtractorLinkType.M3U8) {
                    this.quality = if (source.label == "1080p") Qualities.P1080.value else Qualities.P720.value
                    this.referer = mainUrl
                })
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun decryptAesGcm(data: PlaybackData): String {
        val masterKey = data.key_parts.map { Base64.decode(it, Base64.URL_SAFE) }.reduce { acc, bytes -> acc + bytes }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, Base64.decode(data.iv, Base64.URL_SAFE)))
        return String(cipher.doFinal(Base64.decode(data.payload, Base64.URL_SAFE)), Charsets.UTF_8)
    }
}

open class P2PExtractor : ExtractorApi() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = url.substringAfter("id=").substringBefore("&")
        val bridge = "https://playeriframe.sbs/"
        try {
            app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to bridge))
            val res = app.post("$mainUrl/api2.php?id=$id", headers = mapOf("Referer" to url, "Content-Type" to "application/x-www-form-urlencoded"), data = mapOf("r" to bridge, "d" to "cloud.hownetwork.xyz")).text
            val videoUrl = tryParseJson<HownetworkResponse>(res)?.let { it.file ?: it.link }
            if (!videoUrl.isNullOrBlank()) {
                callback.invoke(newExtractorLink("LK21 P2P", "P2P 480p", videoUrl, ExtractorLinkType.M3U8) { this.referer = mainUrl; this.quality = Qualities.P480.value })
            }
        } catch (e: Exception) { if (e !is kotlinx.coroutines.CancellationException) e.printStackTrace() }
    }
}

// DATA MODELS (TOP-LEVEL)
data class AttestToken(val token: String?)
data class PlaybackOuter(val playback: PlaybackData?)
data class PlaybackData(val iv: String, val payload: String, val key_parts: List<String>)
data class StreamContainer(val sources: List<StreamItem>?)
data class StreamItem(val url: String, val label: String)
data class HownetworkResponse(val file: String?, val link: String?)
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
