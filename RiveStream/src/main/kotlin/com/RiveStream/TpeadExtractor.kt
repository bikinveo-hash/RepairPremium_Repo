package com.RiveStream

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class TpeadExtractor : ExtractorApi() {
    override val name = "Streamtape"
    // Ganti mainUrl menjadi standar Streamtape agar dideteksi Cloudstream otomatis
    override val mainUrl = "https://streamtape.com" 
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Translasi Otomatis: Ubah streamtape.com / streamtape.to menjadi domain bypass tpead.net
            val bypassUrl = url.replace(Regex("(?i)streamtape\\.(com|to|net)"), "tpead.net")
            
            // Validasi format url Embed (Wajib menggunakan /e/)
            val finalTargetUrl = if (bypassUrl.contains("/v/")) bypassUrl.replace("/v/", "/e/") else bypassUrl

            val response = app.get(finalTargetUrl, referer = referer).text
            
            // Regex mencari src video utama dari Tpead HTML
            val baseRegex = Regex("""(?i)(?:document\.getElementById\('[^']+'\)\.)?innerHTML\s*=\s*['"](//[^'"]+)['"]""")
            val baseMatch = baseRegex.find(response) ?: return
            
            val baseUrl = baseMatch.groupValues[1]
            val startIndex = baseMatch.range.last
            val searchArea = response.substring(startIndex, (startIndex + 200).coerceAtMost(response.length))
            
            // Regex untuk mengambil sisa token yang disambung menggunakan JavaScript String Concatenation
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
                
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Streamtape (Tpead Bypass)",
                        url = finalUrl
                    ) {
                        // Header referer sangat penting agar tidak ditendang oleh Streamtape
                        this.referer = "https://tpead.net/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
