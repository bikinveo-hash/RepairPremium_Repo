package com.RiveStream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay

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

        // REGEX SAKTI: Ditambahkan "1shows" ke dalam daftar intersepsi radar WebView
        val playerRegex = Regex(".*(1shows|streamtape|voe|streamwish|filemoon|dood|mixdrop|streamplay|vinovo|vidmoly|vidara|savefiles|filelions|luluvdoo|streamruby|vidsst|mshare|uqload|fembed).*")

        var linksFound = 0

        try {
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = playerRegex,
                userAgent = null, 
                useOkhttp = false 
            )

            val (interceptedRequest, _) = webViewResolver.resolveUsingWebView(
                url = embedUrl,
                referer = "$mainUrl/"
            )

            val realEmbedUrl = interceptedRequest?.url?.toString()

            if (!realEmbedUrl.isNullOrBlank()) {
                
                // KUNCI UTAMA: Jika yang tertangkap adalah server 1shows, bypass direct ke ExoPlayer dengan headers
                if (realEmbedUrl.contains("1shows")) {
                    callback(newExtractorLink(
                        source = providerName,
                        name = "RiveStream CDN (Multi-Quality)",
                        url = realEmbedUrl,
                        type = ExtractorLinkType.M3U8 // Deklarasikan sebagai tipe M3U8 resmi
                    ) {
                        // Suntikkan headers persis dengan parameter curl yang kamu temukan
                        this.referer = "https://www.rivestream.app/"
                        this.headers = mapOf(
                            "Origin" to "https://www.rivestream.app",
                            "Accept" to "*/*"
                        )
                    })
                    linksFound++
                } else {
                    // Untuk server konvensional lainnya (Streamtape, Filemoon, Voe, dll.)
                    val isExtractorFound = loadExtractor(
                        url = realEmbedUrl,
                        referer = embedUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    if (!isExtractorFound) {
                        callback(newExtractorLink(
                            source = providerName,
                            name = "Mirror Video",
                            url = realEmbedUrl
                        ) {
                            this.referer = embedUrl
                        })
                    }
                    linksFound++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return linksFound > 0
    }
}
