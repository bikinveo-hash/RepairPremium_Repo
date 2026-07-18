package com.OppaDrama

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import android.util.Base64

/**
 * 1. EarnVids / Smoothpre Extractor
 * Alias backend extractor: smoothpre.com menggunakan arsitektur yang sama dengan vidhidepro.com.
 * Mewarisi langsung dari VidHidePro untuk efisiensi performa tanpa boilerplate.
 */
class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

/**
 * 2. BuzzServer Extractor
 * Pengekstrak hoster direct-download buzzheavier.com menggunakan skema manual redirect capture.
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
            val page = app.get(url)
            val qualityText = page.documentLarge.selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)

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
            }
        } catch (e: Exception) {
            Log.e("BuzzServer", "Failed to resolve $url: ${e.message}")
        }
    }
}

/**
 * 3. Emturbovid Extractor
 * Kebal dari Cross-Domain Header Strip via manual 301 redirection capture & DOM data-hash mapping.
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
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "$mainUrl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            val firstRequest = app.get(url, headers = headers, allowRedirects = false)
            var finalUrl = url

            if (firstRequest.code == 301 || firstRequest.code == 302) {
                val location = firstRequest.headers["Location"] ?: firstRequest.headers["location"]
                if (!location.isNullOrBlank()) {
                    finalUrl = location
                }
            }

            val html = app.get(finalUrl, headers = headers).text
            val document = Jsoup.parse(html)

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
                    nextLine.startsWith("/") -> "https://" + URI(masterUrl).host + nextLine
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

/**
 * 4. Abyss / Hydrax Extractor
 * Perbaikan Forensik Pemecah Proteksi Enkripsi Multi-Token & Perutean Cluster API Dinamis.
 */
class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyss.to"
    override val requiresReferer = true

    private data class AbyssSource(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("type") val type: String?
    )

    private data class AbyssResponse(
        @JsonProperty("sources") val sources: List<AbyssSource>?
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "http://45.11.57.192/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text
            
            val datasRaw = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            
            var slug = Regex("[?&]v=([^&#]+)").find(url)?.groupValues?.getOrNull(1)?.trim()
            var md5Id = ""
            var userId = ""

            if (!datasRaw.isNullOrBlank()) {
                // Perbaikan Mutlak: Menggunakan dekoder biner bawaan Android SDK asli
                val decodedDatas = String(Base64.decode(datasRaw, Base64.DEFAULT), Charsets.UTF_8)
                if (slug.isNullOrBlank()) {
                    slug = Regex("""\"slug\"\s*:\s*\"([^\"]+)\"""").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim()
                }
                md5Id = Regex("""\"md5_id\"\s*:\s*\"?(\d+)\"?"""").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim() ?: ""
                userId = Regex("""\"user_id\"\s*:\s*\"?(\d+)\"?"""").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }

            if (slug.isNullOrBlank()) {
                slug = Regex("""(?:v|slug)\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)?.trim()
            }
            if (userId.isNullOrBlank()) {
                userId = Regex("""userID\s*:\s*["']?(\d+)["']?""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }

            if (slug.isNullOrBlank()) return

            val host = URI(url).host
            val apiUrl = "https://$host/api/player/v2"

            val apiResponse = app.post(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                    "Referer" to url,
                    "Origin" to "https://$host",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf<String, String>(
                    "slug" to slug,
                    "md5_id" to md5Id,
                    "user_id" to userId
                )
            ).parsedSafe<AbyssResponse>()

            apiResponse?.sources?.forEach { source ->
                val videoUrl = source.file
                if (!videoUrl.isNullOrBlank()) {
                    val labelText = source.label ?: "Unknown"
                    val isM3u8 = videoUrl.contains(".m3u8")
                    
                    val qualityValue = when {
                        labelText.contains("1080") -> Qualities.P1080.value
                        labelText.contains("720") -> Qualities.P720.value
                        labelText.contains("480") -> Qualities.P480.value
                        labelText.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $labelText",
                            url = videoUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://$host/"
                            this.quality = qualityValue
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }
}

/**
 * 5. Minochinos / VidHide Obfuscated Extractor
 * Bypass proteksi kompresi Dean Edwards Packer skrip penyamaran stream menggunakan unpacking engine internal core.
 */
class MinochinosExtractor : ExtractorApi() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
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
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text
            val unpackedHtml = getAndUnpack(html)
            
            val streamUrl = Regex("""https?://[^\s"'`<>]+?\.m3u8[^\s"'`<>]*""").find(unpackedHtml)?.value
                ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(unpackedHtml)?.groupValues?.getOrNull(1)

            if (!streamUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
