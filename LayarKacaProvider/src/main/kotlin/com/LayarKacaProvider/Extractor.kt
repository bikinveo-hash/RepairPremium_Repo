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
            // Ekstrak ID dari URL
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty()) return null

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://playeriframe.sbs/"
            )

            val html = app.get("$mainUrl/t/$id", headers = headers).text

            // Ekstraksi URL Master M3U8
            var masterUrl = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (masterUrl.isNullOrBlank()) {
                masterUrl = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }
            if (masterUrl.isNullOrBlank()) return null

            // Fetch dan parse master playlist
            val masterContent = app.get(masterUrl, headers = mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )).text

            val qualityMap = mapOf(
                "1920x1080" to Qualities.P1080.value,
                "1280x720"  to Qualities.P720.value,
                "854x480"   to Qualities.P480.value,
                "640x360"   to Qualities.P360.value
            )

            // Parse setiap baris STREAM-INF
            val lines = masterContent.lines()
            for (i in lines.indices) {
                val line = lines[i]
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue

                val subUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                if (!subUrl.startsWith("http")) continue

                // CEK CDN sebelum di-return (Lewati CDN mati)
                val cdnHost = try { java.net.URI(subUrl).host } catch (e: Exception) { continue }
                val deadCdns = listOf("exznews.com", "cdn64.", "cdn32.")
                if (deadCdns.any { cdnHost.contains(it) }) {
                    println("TurboVIP: skip dead CDN $cdnHost")
                    continue
                }

                // Cek apakah CDN bisa diakses (Mencegah Error 2001 di player)
                val check = try {
                    app.head(subUrl, headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    ), timeout = 5L)
                } catch (e: Exception) { continue }

                if (!check.isSuccessful) continue

                val quality = qualityMap.entries
                    .firstOrNull { line.contains(it.key) }?.value
                    ?: Qualities.Unknown.value

                sources.add(
                    newExtractorLink(
                        source = name,
                        name = "TurboVIP ${Qualities.getStringByInt(quality)}",
                        url = subUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/"
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
