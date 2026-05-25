package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class JuraganFilmExtractor : ExtractorApi() {
    override val name           = "JuraganFilm"
    // ✅ Fix #3: mainUrl harus match domain saja, bukan path /file/
    // CloudStream akan trigger extractor ini untuk semua URL dari domain ini
    override val mainUrl        = "https://tv44.juragan.film"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // ✅ Fix #1: Referer harus URL halaman asli, bukan mainUrl
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer"    to (referer ?: mainUrl),
            "Origin"     to mainUrl
        )

        val html = app.get(url, headers = headers).text

        // Cari m3u8 dari cloud.wth.my.id
        val m3u8Regex = Regex("""https://cloud\.wth\.my\.id/\?id=[^"'\s]+\.m3u8""")
        val match = m3u8Regex.find(html)

        if (match != null) {
            callback(
                newExtractorLink(
                    source = name,
                    name   = "$name - HLS",
                    url    = match.value,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "Origin"  to mainUrl,
                        "Referer" to url
                    )
                }
            )
            return
        }

        // Fallback: cari mp4 biasa
        val mp4Regex = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""")
        mp4Regex.find(html)?.let { mp4Match ->
            callback(
                newExtractorLink(
                    source = name,
                    name   = "$name - MP4",
                    url    = mp4Match.value,
                    type   = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}
