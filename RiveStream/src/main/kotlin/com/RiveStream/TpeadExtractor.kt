package com.RiveStream

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
            // FIX: Tembak menggunakan identitas mirror player agar lolos dari kecurigaan bot 404
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
                
                // FIX: Pembungkusan menggunakan Lambda Block {} sesuai arsitektur baru
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
