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
        
        // RADAR INTERCEPTION: Menangkap momen matang saat m3u8 dari cdn 1shows mulai dialirkan
        val playerRegex = Regex(".*(1shows|cdn\\.1shows\\.app|streamtape|voe|streamwish|filemoon|dood|mixdrop).*")

        // SKRIP PENYAMARAN: Menyabotase sensor detektor DisableDevtool dari dalam memori WebView
        val bypassScript = """
            (function() {
                // 1. Jinakkan Sensor Ukuran (Detektor Size) dengan memalsukan dimensi viewport headless
                Object.defineProperty(window, 'innerWidth', { get: function() { return window.outerWidth; } });
                Object.defineProperty(window, 'innerHeight', { get: function() { return window.outerHeight; } });
                
                // 2. Kunci detektor devicePixelRatio agar perhitungan rasio selalu netral
                if (window.screen) {
                    Object.defineProperty(window.screen, 'deviceXDPI', { get: function() { return undefined; } });
                }

                // 3. Kebalkan fungsi window.close agar WebView tidak bisa dimatikan paksa
                window.close = function() { console.log('Bypassed anti-devtool window.close()'); };

                // 4. Sabotase putaran interval deteksi (membuat jeda deteksi menjadi 2,7 jam sekali)
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
            // Konfigurasi WebViewResolver menggunakan User-Agent murni sistem dan menyuntikkan skrip bypass
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = playerRegex,
                userAgent = null, 
                useOkhttp = false,
                script = bypassScript // Skrip penjinak disuntikkan secara aman di sini
            )

            // WebView memproses halaman utama film secara natural tanpa memicu alarm proteksi
            val (interceptedRequest, _) = webViewResolver.resolveUsingWebView(
                url = data, 
                referer = "$mainUrl/"
            )

            val realEmbedUrl = interceptedRequest?.url?.toString()

            if (!realEmbedUrl.isNullOrBlank()) {
                // Eksekusi jika berkas m3u8 dari server utama cdn 1shows berhasil terjaring radar
                if (realEmbedUrl.contains("1shows")) {
                    callback(newExtractorLink(
                        source = providerName,
                        name = "RiveStream CDN (HLS Multi-Quality)",
                        url = realEmbedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // Amankan paspor akses streaming sesuai dengan hasil pengujian curl kamu
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
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    if (!isExtractorFound) {
                        callback(newExtractorLink(
                            source = providerName,
                            name = "Mirror Alternate",
                            url = realEmbedUrl
                        ) {
                            this.referer = data
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
