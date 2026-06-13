package com.RiveStream

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class TpeadExtractor : ExtractorApi() {
    override val name = "Streamtape"
    override val mainUrl = "https://streamtape.com" 
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Translasi ke domain bypass
            val bypassUrl = url.replace(Regex("(?i)streamtape\\.(com|to|net)"), "tpead.net")
            val finalTargetUrl = if (bypassUrl.contains("/v/")) bypassUrl.replace("/v/", "/e/") else bypassUrl
            val videoId = finalTargetUrl.split("/").lastOrNull() ?: return

            val response = app.get(finalTargetUrl, referer = referer).text
            
            // Tarik Base URL
            val baseRegex = Regex("""(?i)(?:document\.getElementById\('[^']+'\)\.)?innerHTML\s*=\s*['"](//[^'"]+)['"]""")
            val baseMatch = baseRegex.find(response) ?: return
            
            val startIndex = baseMatch.range.last
            val searchArea = response.substring(startIndex, (startIndex + 200).coerceAtMost(response.length))
            
            val tokenRegex = Regex("""\+\s*\(['"]([^'"]+)['"]\)\.substring\((\d+)\)""")
            val altTokenRegex = Regex("""\+\s*['"]([^'"]+)['"]""")
            
            var rawTokenData = ""
            
            val tokenMatch = tokenRegex.find(searchArea)
            if (tokenMatch != null) {
                val rawToken = tokenMatch.groupValues[1]
                val cutIdx = tokenMatch.groupValues[2].toIntOrNull() ?: 0
                if (cutIdx < rawToken.length) {
                    rawTokenData = rawToken.substring(cutIdx)
                }
            } else {
                val altMatch = altTokenRegex.find(searchArea)
                if (altMatch != null) {
                    rawTokenData = altMatch.groupValues[1]
                }
            }
            
            if (rawTokenData.isNotEmpty()) {
                // JURUS PAMUNGKAS: Ekstrak parameter penting dan rakit manual
                val expiresMatch = Regex("""expires=([^&]+)""").find(rawTokenData)
                val ipMatch = Regex("""ip=([^&]+)""").find(rawTokenData)
                val tokenMatchVal = Regex("""token=([^&]+)""").find(rawTokenData)

                if (expiresMatch != null && ipMatch != null && tokenMatchVal != null) {
                    val expires = expiresMatch.groupValues[1]
                    val ip = ipMatch.groupValues[1]
                    val token = tokenMatchVal.groupValues[1]

                    // Merakit URL streamtape yang sempurna tanpa mempedulikan huruf sampah
                    val cleanUrl = "https://tpead.net/get_video?id=$videoId&expires=$expires&ip=$ip&token=$token&dl=1"
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Streamtape (Tpead Bypass)",
                            url = cleanUrl
                        ) {
                            this.referer = "https://tpead.net/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
