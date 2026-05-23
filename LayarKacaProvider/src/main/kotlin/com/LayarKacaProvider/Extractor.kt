package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
            // Ekstrak ID dari URL: https://turbovidhls.com/t/6a10996443060
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
                println("Lk21TurboExtractor: Gagal mengekstrak URL M3U8")
                return null
            }

            sources.add(
                newExtractorLink(
                    source = "LK21 TurboVIP",
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
