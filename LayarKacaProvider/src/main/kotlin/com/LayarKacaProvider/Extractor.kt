package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Lk21TurboExtractor : ExtractorApi() {
    override var name      = "LK21 TurboVIP"
    // Update domain selaras dengan temuan iframe asli di HTML
    override var mainUrl   = "https://emturbovid.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty()) return null

            val baseHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Referer"    to "https://playeriframe.sbs/"
            )

            val response = app.get("$mainUrl/t/$id", headers = baseHeaders)
            val html     = response.text

            // Cari m3u8 URL — coba data-hash dulu, fallback ke urlPlay
            var m3u8Url = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8Url.isNullOrBlank()) {
                m3u8Url = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }
            if (m3u8Url.isNullOrBlank()) return null

            val isMp4 = m3u8Url.endsWith(".mp4", ignoreCase = true)
            val type  = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8

            var finalPlayableUrl = m3u8Url

            // ====================================================================
            // GOD MODE SNIFFER: Bypass Cronet & Cleartext HTTP secara native
            // ====================================================================
            if (type == ExtractorLinkType.M3U8) {
                try {
                    var finalM3u8Url = m3u8Url
                    var finalM3u8Content = app.get(finalM3u8Url, headers = baseHeaders).text
                    
                    var depth = 0
                    while (finalM3u8Content.contains(".m3u8") && depth < 3) {
                        val nextPath = finalM3u8Content.lines().firstOrNull { 
                            it.trim().isNotEmpty() && !it.trim().startsWith("#") && it.trim().contains(".m3u8") 
                        }?.trim()
                        
                        if (nextPath != null) {
                            finalM3u8Url = if (nextPath.startsWith("http")) nextPath else "${finalM3u8Url.substringBeforeLast("/")}/$nextPath"
                            finalM3u8Content = app.get(finalM3u8Url, headers = baseHeaders).text
                        } else {
                            break
                        }
                        depth++
                    }

                    val baseUrl = finalM3u8Url.substringBeforeLast("/")
                    val rewrittenLines = finalM3u8Content.lines().map { line ->
                        val trimmed = line.trim()
                        when {
                            trimmed.startsWith("#") || trimmed.isEmpty() -> trimmed
                            trimmed.startsWith("http://") -> trimmed.replace("http://", "https://")
                            trimmed.startsWith("https://") -> trimmed
                            else -> "$baseUrl/$trimmed"
                        }
                    }
                    
                    val secureM3u8 = rewrittenLines.joinToString("\n")
                    finalPlayableUrl = "data:application/x-mpegURL;base64," + android.util.Base64.encodeToString(secureM3u8.toByteArray(), android.util.Base64.NO_WRAP)
                } catch (e: Exception) {
                    finalPlayableUrl = m3u8Url
                }
            }

            sources.add(
                newExtractorLink(
                    source = "LK21 TurboVIP",
                    name   = "TurboVIP HD",
                    url    = finalPlayableUrl,
                    type   = type
                ) {
                    this.referer = "" // Dikosongkan agar bebas 429 dari CDN Google
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin"  to mainUrl,
                        "User-Agent" to baseHeaders["User-Agent"]!!
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
