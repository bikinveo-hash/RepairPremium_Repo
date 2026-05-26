package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

// ============================================================================
// HANYA TURBOVIP EXTRACTOR (LK21)
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

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://playeriframe.sbs/"
            )

            val html = app.get("$mainUrl/t/$id", headers = headers).text

            // Dapatkan Master URL M3U8
            var masterUrl = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (masterUrl.isNullOrBlank()) {
                masterUrl = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }

            if (masterUrl.isNullOrBlank()) return null

            // CARA OTOMATIS: 
            // Biarkan `M3u8Helper` bawaan Cloudstream yang mendeteksi dan mengurai semua kualitas secara akurat
            M3u8Helper.generateM3u8(
                name,
                masterUrl,
                "$mainUrl/",
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )
            ).forEach { link ->
                sources.add(link)
            }
            
            // FALLBACK OTOMATIS: 
            // Jika Helper kosong, lempar satu link master mentah dan biarkan ExoPlayer memutar secara Adaptive (Auto)
            if (sources.isEmpty()) {
                sources.add(
                    newExtractorLink(
                        source = name,
                        name = "TurboVIP HD",
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                        )
                    }
                )
            }
            
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources.ifEmpty { null }
    }
}
