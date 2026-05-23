package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.delay

// ============================================================================
// 1. TURBOVIP EXTRACTOR
// ============================================================================
open class EmturbovidExtractor : ExtractorApi() {
    override var name = "TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            delay(500) // Jeda 0.5s agar tidak langsung ter-cancel proses lain
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://playeriframe.sbs/"
            )
            val interceptorRegex = Regex("(?i)m3u8")
            val response = app.get(url, headers = headers, interceptor = WebViewResolver(interceptorRegex))
            val videoUrl = response.url

            if (videoUrl.contains("m3u8", ignoreCase = true)) {
                sources.add(newExtractorLink("TurboVIP", "TurboVIP HD", videoUrl, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        return sources
    }
}

// ============================================================================
// 2. CAST / F16PX EXTRACTOR - Diberi Prioritas Delay lebih sedikit
// ============================================================================
open class F16Extractor : ExtractorApi() {
    override var name = "Cast"
    override var mainUrl = "https://f16px.com"
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
                sources.add(newExtractorLink("Cast", "Cast HD", videoUrl, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        return sources
    }
}

// ============================================================================
// 3. P2P EXTRACTOR - Diberi Delay 1 detik biar Cast punya waktu muncul
// ============================================================================
open class P2PExtractor : ExtractorApi() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false
    
    data class HownetworkResponse(val file: String?, val link: String?)

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        delay(1000) // P2P mengalah 1 detik agar Cast/TurboVIP muncul duluan
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
        val sources = mutableListOf<ExtractorLink>()
        
        try {
            app.get(url, headers = initHeaders)
            val response = app.post(apiUrl, headers = apiHeaders, data = formBody).text
            val json = tryParseJson<HownetworkResponse>(response)
            val videoUrl = json?.file ?: json?.link
   
            if (!videoUrl.isNullOrBlank()) {
                sources.add(newExtractorLink("LK21 P2P", "P2P Player (480p)", videoUrl, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = Qualities.P480.value
                })
            }
        } catch (e: Exception) { 
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
        return sources
    }
}
