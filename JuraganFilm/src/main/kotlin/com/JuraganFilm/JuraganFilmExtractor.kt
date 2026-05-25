package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class JuraganFilmExtractor : ExtractorApi() {
    override val name = "JuraganFilm File"
    override val mainUrl = "https://tv44.juragan.film/file/"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: mainUrl).text
        
        // Cari link m3u8 dari cloud.wth.my.id (bisa dalam petik tunggal/ganda)
        val m3u8Regex = Regex("""https://cloud\.wth\.my\.id/\?id=[^"'\s]+\.m3u8""")
        val match = m3u8Regex.find(html)
        
        if (match != null) {
            val m3u8Url = match.value
            callback(
                newExtractorLink(
                    source = name,
                    name = "JuraganFilm - HLS",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                }
            )
        }
        
        // Fallback: cari link mp4 dari fallback JSON
        val fallbackRegex = Regex("""const FALLBACK_JSON_URL\s*=\s*"([^"]+)""")
        val fallbackMatch = fallbackRegex.find(html)
        if (fallbackMatch != null && match == null) {
            // Tidak ada m3u8, mungkin perlu ambil dari JSON fallback
            // Tapi untuk sekarang kita prioritaskan m3u8
        }
    }
}
