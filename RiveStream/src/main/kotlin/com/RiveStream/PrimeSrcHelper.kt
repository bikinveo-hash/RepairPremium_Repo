package com.RiveStream

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

        // Amankan rute embed dapur hulu asli agar bebas dari error 404
        val embedUrl = if (isMovie) {
            "https://primesrc.me/embed/movie?tmdb=$cleanId"
        } else {
            val season = cleanData.substringAfter("?s=").substringBefore("&")
            val episode = cleanData.substringAfter("&e=")
            "https://primesrc.me/embed/tv?tmdb=$cleanId&season=$season&episode=$episode"
        }

        // RADAR INTERCEPTION: Menangkap momen m3u8 matang dari cdn utama atau mirror alternatif
        val playerRegex = Regex(".*(1shows|cdn\\.1shows\\.app|streamtape|voe|streamwish|filemoon|dood|mixdrop).*")

        // =========================================================================
        // JAVASCRIPT BYPASS & AUTOMATION SAKTI (GABUNGAN CORE ENGINE)
        // =========================================================================
        val bypassScript = """
            (function() {
                // 1. Jinakkan Sensor Ukuran 'disable-devtool' dengan memalsukan dimensi viewport
                Object.defineProperty(window, 'innerWidth', { get: function() { return 1920; } });
                Object.defineProperty(window, 'innerHeight', { get: function() { return 1080; } });
                Object.defineProperty(window, 'outerWidth', { get: function() { return 1920; } });
                Object.defineProperty(window, 'outerHeight', { get: function() { return 1080; } });
                if (document.documentElement) {
                    Object.defineProperty(document.documentElement, 'clientWidth', { get: function() { return 1920; } });
                    Object.defineProperty(document.documentElement, 'clientHeight', { get: function() { return 1080; } });
                }

                // 2. Samarkan identitas Headless Browser agar Cloudflare Turnstile Auto-Verify
                Object.defineProperty(navigator, 'webdriver', { get: function() { return undefined; } });
                Object.defineProperty(navigator, 'languages', { get: function() { return ['id-ID', 'id', 'en-US', 'en']; } });

                // 3. Kebalkan window.close dan lambatkan interval pemblokiran sensor
                window.close = function() { console.log('Bypassed anti-devtool window.close()'); };
                var originalSetInterval = window.setInterval;
                window.setInterval = function(fn, delay) {
                    if (delay === 500 || delay === 400 || delay === 600) {
                        return originalSetInterval(fn, 9999999);
                    }
                    return originalSetInterval(fn, delay);
                };

                // 4. AUTOMATED CLICKER: Simulasikan klik pada poster splash screen secara instan
                //    agar fungsi jabat tangan 'spiderman' dan 'getLink' terpicu otomatis!
                var clickTracker = originalSetInterval(function() {
                    var splashButton = document.querySelector('.splash') || document.querySelector('[class*="splash"]');
                    if (splashButton) {
                        splashButton.click();
                        clearInterval(clickTracker); // Hentikan tracker jika sudah sukses diklik
                    }
                }, 150);
            })();
        """.trimIndent()

        var linksFound = 0

        try {
            // Gunakan User-Agent murni sistem agar TLS Fingerprint sinkron di gerbang Cloudflare
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = playerRegex,
                userAgent = null, 
                useOkhttp = false,
                script = bypassScript // Eksekusi ramuan skrip bypass otomatis kita
            )

            val (interceptedRequest, _) = webViewResolver.resolveUsingWebView(
                url = embedUrl, 
                referer = "$mainUrl/"
            )

            val realEmbedUrl = interceptedRequest?.url?.toString()

            if (!realEmbedUrl.isNullOrBlank()) {
                // Jika berkas m3u8 dari server utama cdn 1shows berhasil terjaring radar
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
                    // Skenario cadangan jikalau dialirkan ke host konvensional (Filemoon, Voe, dll.)
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
