package com.RiveStream

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
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
            // Nembak ke tpead.net
            val response = app.get(url, referer = referer).text
            
            // Regex nyari innerHTML = "//tpe..."
            val baseRegex = Regex("""(?i)(?:document\.getElementById\('[^']+'\)\.)?innerHTML\s*=\s*['"](//[^'"]+)['"]""")
            val baseMatch = baseRegex.find(response) ?: return
            
            val baseUrl = baseMatch.groupValues[1]
            val startIndex = baseMatch.range.last
            val searchArea = response.substring(startIndex, (startIndex + 200).coerceAtMost(response.length))
            
            // Regex narik token via .substring() atau direct concat
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
                
                // Gunakan lambda block {} untuk parameter tambahan sesuai aturan ExtractorApi.kt
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Streamtape (Tpead Bypass)",
                        url = finalUrl
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
