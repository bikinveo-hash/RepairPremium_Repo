package com.OppaDrama

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "$mainUrl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            // SOLUSI BYPASS: Matikan auto-redirect agar OkHttp tidak menjatuhkan header di rute lintas domain[span_5](start_span)[span_5](end_span)
            val firstRequest = app.get(url, headers = headers, allowRedirects = false)
            var finalUrl = url

            if (firstRequest.code == 301 || firstRequest.code == 302) {
                val location = firstRequest.headers["Location"] ?: firstRequest.headers["location"]
                if (!location.isNullOrBlank()) {
                    finalUrl = location
                }
            }

            // Tembak URL rute asli dengan membawa paket pelolos yang tetap utuh 100%
            val html = app.get(finalUrl, headers = headers).text
            val document = Jsoup.parse(html)

            // Ekstraksi data m3u8 menggunakan taktik pengaman berlapis (DOM hash & Javascript Fallback)
            val masterUrl = document.select("div#video_player").attr("data-hash").trim().takeIf { it.isNotBlank() }
                ?: Regex("""var\s+urlPlay\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)?.trim()

            if (masterUrl.isNullOrBlank()) return

            val streamHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )

            val masterText = app.get(masterUrl, headers = streamHeaders).text
            val lines = masterText.lines()
            var variantsFound = false

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue

                val height = Regex("RESOLUTION=\\d+x(\\d+)")
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                val nextLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (nextLine.isBlank() || nextLine.startsWith("#")) continue

                val variantUrl = when {
                    nextLine.startsWith("//") -> "https:$nextLine"
                    nextLine.startsWith("/") -> "https://" + io.ktor.http.Url(masterUrl).host + nextLine
                    nextLine.startsWith("http") -> nextLine
                    else -> masterUrl.substringBeforeLast("/") + "/" + nextLine
                }

                variantsFound = true
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ${height ?: ""}p".trim(),
                        url = variantUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = streamHeaders
                        this.quality = height ?: Qualities.Unknown.value
                    }
                )
            }

            if (!variantsFound) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
