package com.JuraganFilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class JuraganFilmExtractor : ExtractorApi() {
    override val name           = "JuraganFilm"
    override val mainUrl        = "https://tv44.juragan.film"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer"    to (referer ?: "$mainUrl/"),
            "Origin"     to mainUrl
        )

        callback(
            newExtractorLink(
                source = name,
                name   = "$name - Direct Link",
                url    = url,
                type   = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://tv44.juragan.film/"
                this.quality = Qualities.P1080.value
                this.headers = headers
            }
        )
    }
}
