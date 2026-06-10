package com.RiveStream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class PrimeSrcHelper {
    
    suspend fun invokePrimeSrc(
        data: String,
        mainUrl: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?s=")
        val cleanId = cleanData.substringAfter("/").substringBefore("?")

        val embedUrl = if (isMovie) {
            "https://primesrc.me/embed/movie?tmdb=$cleanId"
        } else {
            val season = cleanData.substringAfter("?s=").substringBefore("&")
            val episode = cleanData.substringAfter("&e=")
            "https://primesrc.me/embed/tv?tmdb=$cleanId&season=$season&episode=$episode"
        }

        val serversUrl = if (isMovie) {
            "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=movie"
        } else {
            val season = cleanData.substringAfter("?s=").substringBefore("&")
            val episode = cleanData.substringAfter("&e=")
            "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=tv&season=$season&episode=$episode"
        }

        // STEP 1: Eksekusi WebView (Bypass Cloudflare)
        try {
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = Regex(".*primesrc\\.me/api/v1/s.*"),
                useOkhttp = false 
            )
            webViewResolver.resolveUsingWebView(url = embedUrl, referer = "$mainUrl/")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // STEP 2: Siapkan Headers
        val headers = mapOf(
            "User-Agent" to (com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent ?: USER_AGENT),
            "Referer" to embedUrl,
            "Origin" to "https://primesrc.me",
            "Accept" to "*/*",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )

        var linksFound = 0

        // STEP 3: Tembak API & Gunakan loadExtractor bawaan Cloudstream
        try {
            val response = app.get(serversUrl, headers = headers).text
            val parsed = tryParseJson<PrimeSrcResponse>(response) ?: return false
            val servers = parsed.servers ?: return false

            for (server in servers) {
                val internalKey = server.key ?: continue
                try {
                    val spidermanUrl = "https://primesrc.me/spiderman?l=$internalKey"
                    app.get(spidermanUrl, headers = headers)

                    val decryptUrl = "https://primesrc.me/api/v1/l?key=$internalKey"
                    val decryptResponse = app.get(decryptUrl, headers = headers).text
                    val linkData = tryParseJson<PrimeSrcLinkResponse>(decryptResponse) ?: continue
                    val realEmbedUrl = linkData.link ?: continue
                    val serverName = server.name ?: linkData.host ?: "Direct Link"

                    // Minta Cloudstream mengecek apakah ada extractor untuk URL ini
                    val isExtractorFound = loadExtractor(
                        url = realEmbedUrl,
                        referer = embedUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    // Jika Cloudstream tidak punya extractor-nya, kembalikan sebagai link mentah
                    if (!isExtractorFound) {
                        callback(
                            newExtractorLink(
                                source = providerName,
                                name = serverName,
                                url = realEmbedUrl
                            ) {
                                this.referer = embedUrl
                            }
                        )
                    }
                    linksFound++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return linksFound > 0
    }
}
