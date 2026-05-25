package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

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
        // URL: https://tv44.juragan.film/file/?id=...
        // Buka halaman, cari link m3u8 dari cloud.wth.my.id
        val doc = app.get(url, referer = referer ?: mainUrl).document
        // Cari script yang mengandung cloud.wth.my.id
        val scripts = doc.select("script")
        val m3u8Regex = Regex("""https://cloud\.wth\.my\.id/\?id=[^"']+\.m3u8""")
        for (script in scripts) {
            val match = m3u8Regex.find(script.html())
            if (match != null) {
                val m3u8Url = match.value
                callback(
                    newExtractorLink(
                        source = name,
                        name = "JuraganFilm - Server 1",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }
        }
        // Fallback: cari dalam HTML keseluruhan
        val html = doc.html()
        val m3u8Match = m3u8Regex.find(html)
        if (m3u8Match != null) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "JuraganFilm - Server 1",
                    url = m3u8Match.value,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.P720.value
                }
            )
        }
    }
}
