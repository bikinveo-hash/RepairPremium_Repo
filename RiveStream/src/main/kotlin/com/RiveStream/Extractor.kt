package com.RiveStream

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// =========================================================================
// 1. STREAMTAPE EXTRACTOR (TpeadExtractor)
// =========================================================================
class TpeadExtractor : ExtractorApi() {
    override val name = "Streamtape"
    override val mainUrl = "https://tpead.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // FIX 404: Tembak menggunakan identitas mirror player agar lolos dari kecurigaan bot
            val response = app.get(
                url, 
                referer = "https://streamta.site/",
                headers = mapOf(
                    "Origin" to "https://streamta.site",
                    "User-Agent" to com.lagradost.cloudstream3.USER_AGENT
                )
            ).text
            
            val baseRegex = Regex("""(?i)(?:document\.getElementById\('[^']+'\)\.)?innerHTML\s*=\s*['"](//[^'"]+)['"]""")
            val baseMatch = baseRegex.find(response) ?: return
            
            val baseUrl = baseMatch.groupValues[1]
            val startIndex = baseMatch.range.last
            val searchArea = response.substring(startIndex, (startIndex + 200).coerceAtMost(response.length))
            
            val tokenRegex = Regex("""\+\s*\(['"]([^'"]+)['"]\)\.substring\((\d+)\)""")
            val altTokenRegex = Regex("""\+\s*['"]([^'"]+)['"]""")
            
            var token = ""
            val tokenMatch = tokenRegex.find(searchArea)
            if (tokenMatch != null) {
                val rawToken = tokenMatch.groupValues[1]
                val cutIdx = tokenMatch.groupValues[2].toIntOrNull() ?: 0
                if (cutIdx < rawToken.length) {
                    token = rawToken.substring(cutIdx)
                }
            } else {
                val altMatch = altTokenRegex.find(searchArea)
                if (altMatch != null) {
                    token = altMatch.groupValues[1]
                }
            }
            
            if (token.isNotEmpty()) {
                val finalUrl = "https:$baseUrl$token&dl=1"
                
                // Pembungkusan menggunakan Lambda Block {} sesuai aturan arsitektur baru
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Streamtape (Tpead Bypass)",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://streamta.site/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to "https://streamta.site")
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// =========================================================================
// 2. VOE EXTRACTOR (VoeExtractor)
// =========================================================================
class VoeExtractor : ExtractorApi() {
    override val name = "VOE"
    override val mainUrl = "https://voe.un"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer).text
            
            // Strategi 1: Cari langsung variabel link video stream mentah
            val videoMatch = Regex("""["']file["']\s*:\s*["'](https?://[^"']+)["']""").find(response)
            var directLink = videoMatch?.groupValues?.get(1)

            // Strategi 2: Fallback jika link disembunyikan di dalam base64 script
            if (directLink == null) {
                val b64Match = Regex("""base64\s*,\s*([a-zA-Z0-9+/={}\s]+)""").find(response)
                b64Match?.groupValues?.get(1)?.let { b64Text ->
                    try {
                        val decoded = String(Base64.decode(b64Text.trim(), Base64.DEFAULT), Charsets.UTF_8)
                        val linkMatch = Regex("""(https?://[^\s"']+)""").find(decoded)
                        directLink = linkMatch?.groupValues?.get(1)
                    } catch (e: Exception) { 
                        e.printStackTrace() 
                    }
                }
            }

            directLink?.let { link ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = "VOE Engine",
                        url = link,
                        type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
