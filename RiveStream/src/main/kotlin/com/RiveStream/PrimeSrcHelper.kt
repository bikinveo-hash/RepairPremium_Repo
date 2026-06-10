package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

        // RADAR INTERCEPTION: Menangkap momen matang saat m3u8 dari cdn 1shows mulai dialirkan
        val playerRegex = Regex(".*(1shows|cdn\\.1shows\\.app|streamtape|voe|streamwish|filemoon|dood|mixdrop).*")

        // SKRIP PENYAMARAN: Menyabotase seluruh detektor internal milik library 'disable-devtool'
        val bypassScript = """
            (function() {
                // 1. Jinakkan Sensor Ukuran (Detektor Size) dengan memalsukan dimensi viewport headless
                Object.defineProperty(window, 'innerWidth', { get: function() { return window.outerWidth; } });
                Object.defineProperty(window, 'innerHeight', { get: function() { return window.outerHeight; } });
                
                // 2. Kunci detektor devicePixelRatio agar perhitungan rasio selalu netral
                if (window.screen) {
                    Object.defineProperty(window.screen, 'deviceXDPI', { get: function() { return undefined; } });
                }

                // 3. Kebalkan fungsi window.close agar WebView tidak bisa dimatikan paksa oleh skrip hulu
                window.close = function() { console.log('Bypassed anti-devtool window.close()'); };

                // 4. Sabotase putaran interval deteksi (Memaksa loop bawaan 500ms miliknya melambat total)
                var originalSetInterval = window.setInterval;
                window.setInterval = function(fn, delay) {
                    if (delay === 500 || delay === 400 || delay === 600) {
                        return originalSetInterval(fn, 9999999);
                    }
                    return originalSetInterval(fn, delay);
                };
            })();
        """.trimIndent()

        var linksFound = 0

        try {
            // Gunakan User-Agent asli bawaan Chromium HP agar Turnstile Cloudflare tidak curiga
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = playerRegex,
                userAgent = null, 
                useOkhttp = false,
                script = bypassScript
            )

            // KUNCI PERBAIKAN: Ubah target url dari 'data' menjadi 'embedUrl' agar bebas dari 404!
            val (interceptedRequest, _) = webViewResolver.resolveUsingWebView(
                url = embedUrl, 
                referer = "$mainUrl/"
            )

            val realEmbedUrl = interceptedRequest?.url?.toString()

            if (!realEmbedUrl.isNullOrBlank()) {
                // Jika berkas m3u8 dari server utama cdn 1shows berhasil terjaring radar pencegatan
                if (realEmbedUrl.contains("1shows")) {
                    callback(newExtractorLink(
                        source = providerName,
                        name = "RiveStream CDN (HLS Multi-Quality)",
                        url = realEmbedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://www.rivestream.app/"
                        this.headers = mapOf(
                            "Origin" to "https://www.rivestream.app",
                            "Accept" to "*/*"
                        )
                    })
                    linksFound++
                } else {
                    // Eksekusi cadangan jika hulu mengalihkan player ke host alternatif (Voe, Filemoon, dll.)
                    val isExtractorFound = loadExtractor(
                        url = realEmbedUrl,
                        referer = embedUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    if (!isExtractorFound) {
                        callback(newExtractorLink(
                            source = providerName,
                            name = "Mirror Alternate",
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

// Data Class pendukung parsing model data
data class PrimeSrcResponse(
    @JsonProperty("servers") val servers: List<PrimeSrcServer>?
)

data class PrimeSrcServer(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key") val key: String?
)

data class PrimeSrcLinkResponse(
    @JsonProperty("link") val link: String?,
    @JsonProperty("host") val host: String?
)
