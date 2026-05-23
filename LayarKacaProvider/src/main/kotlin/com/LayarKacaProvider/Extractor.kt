package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver

// ============================================================================
// 1. TURBOVIP EXTRACTOR (Bypass Cloudflare) - Logika Asli Tidak Diubah
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
            // Aturan Wajib CloudStream: Loloskan CancellationException
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources
    }
}

// ============================================================================
// 2. CAST / F16PX EXTRACTOR (Bypass WebCrypto) - Logika Asli Tidak Diubah
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
                sources.add(
                    newExtractorLink(
                        source = "Cast",
                        name = "Cast HD",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            // Aturan Wajib CloudStream: Loloskan CancellationException
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources
    }
}

// ============================================================================
// 3. P2P EXTRACTOR (HASIL REKONSTRUKSI KALIBRASI TOTAL HOWNETWORK)
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
            // Aturan Wajib CloudStream: Loloskan CancellationException
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace() 
        }
        return sources
    }
}
