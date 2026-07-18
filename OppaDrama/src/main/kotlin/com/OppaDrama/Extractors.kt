package com.OppaDrama

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Alias extractor: smoothpre.com is the same backend as vidhidepro.com
 * (now branded "EarnVids"). Inheriting from the upstream [VidHidePro]
 * implementation gives us a working extractor with zero boilerplate, and the
 * custom `name` makes the source appear as "EarnVids" in the player UI.
 */
class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

/**
 * Buzzheavier.com direct-download host.
 *
 * Flow:
 *   1. GET the share URL once to read the file size from `div.max-w-2xl > span`.
 *   2. GET `<share-url>/download` WITHOUT following redirects to read the
 *      `hx-redirect` response header. The body is an empty HTML stub; the
 *      actual file URL is delivered through that header (htmx pattern).
 *   3. Emit an [ExtractorLink] pointing at the redirect target.
 *
 * Requires a Referer because the site rejects unauthenticated requests.
 */
class BuzzServer : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            // 1) Quality detection – the page renders the file size or quality
            //    hint inside the first <span> of `div.max-w-2xl`.
            val page = app.get(url)
            val qualityText = page.documentLarge
                .selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)

            // 2) Request the download endpoint with redirects DISABLED so we
            //    can capture the `hx-redirect` header instead of being taken
            //    to the file directly. If we followed the redirect the
            //    ExtractorLink would still be the share URL which is useless.
            val response = app.get(
                "$url/download",
                referer = url,
                allowRedirects = false,
            )
            val redirectUrl = response.headers["hx-redirect"]
                ?: response.headers["HX-Redirect"]
                ?: response.headers["location"]
                ?: response.headers["Location"]

            if (!redirectUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = redirectUrl,
                    ) {
                        this.quality = quality
                        this.referer = "$mainUrl/"
                    }
                )
            } else {
                Log.w(TAG, "No redirect URL found in headers for $url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while resolving $url: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BuzzServer"
    }
}

/**
 * Emturbovid.com m3u8 resolver.
 *
 * Flow:
 *   1. Load the embed page; the page exposes a master m3u8 URL inside an
 *      inline `<script>` block as `var urlPlay = '...'`.
 *   2. Fetch the master playlist; each `#EXT-X-STREAM-INF` line lists a
 *      variant and the next line is the variant URL (absolute or relative).
 *   3. Emit one [ExtractorLink] per resolution. If no variant is found we
 *      fall back to a single link pointing at the master m3u8 itself.
 *
 * Why a custom extractor instead of the built-in `StreamHLS`? Because the
 * inline-script URL is wrapped in a custom obfuscation that the generic
 * extractor does not look for; pulling `urlPlay` directly is the most stable
 * approach for this specific embed.
 */
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
            val ref = referer ?: "$mainUrl/"
            val headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
            )

            val page = app.get(url, referer = ref)
            val masterUrl = extractUrlPlay(page.documentLarge.outerHtml())
                ?: return

            val masterText = app.get(masterUrl, headers = headers).text
            val lines = masterText.lines()
            val out = mutableListOf<ExtractorLink>()

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue

                val height = Regex("RESOLUTION=\\d+x(\\d+)")
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (next.isBlank() || next.startsWith("#")) continue

                val variantUrl = when {
                    next.startsWith("//") -> "https:$next"
                    next.startsWith("/") -> mainUrl + next
                    next.startsWith("http") -> next
                    else -> "$mainUrl/$next"
                }

                out += newExtractorLink(
                    source = name,
                    name = "$name ${height ?: ""}p".trim(),
                    url = variantUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = headers
                    this.quality = height ?: Qualities.Unknown.value
                }
            }

            if (out.isEmpty()) {
                out += newExtractorLink(
                    source = name,
                    name = name,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            }

            out.forEach(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve $url: ${e.message}")
        }
    }

    /**
     * Pull `var urlPlay = '...'` out of an arbitrary HTML string. We do not
     * rely on a strict XPath because the script block occasionally contains
     * extra whitespace or trailing semicolons.
     */
    private fun extractUrlPlay(html: String): String? {
        val marker = "var urlPlay"
        val markerIdx = html.indexOf(marker)
        if (markerIdx < 0) return null
        val tail = html.substring(markerIdx)
        val firstQuote = tail.indexOf('\'')
        if (firstQuote < 0) return null
        val after = tail.substring(firstQuote + 1)
        val closingQuote = after.indexOf('\'')
        if (closingQuote < 0) return null
        val raw = after.substring(0, closingQuote).trim()
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> mainUrl + raw
            else -> raw
        }
    }

    companion object {
        private const val TAG = "Emturbovid"
        // Mirrors the constant from the upstream `USER_AGENT` import in
        // MainAPI.kt. Defined locally to keep this file standalone.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    }
}
