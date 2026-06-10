package com.RiveStream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class PrimeSrcHelper {

    suspend fun invokePrimeSrc(
        data: String, // Berisi URL tujuan asli (contoh: https://www.rivestream.app/movie/XXXXXX)
        mainUrl: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // REGEX RADAR: Menangkap momen krusial saat cdn hulu atau host video pihak ketiga mulai dimuat
        val playerRegex = Regex(".*(1shows|cdn\\.1shows\\.app|streamtape|voe|streamwish|filemoon|dood|mixdrop|streamplay|vinovo|vidmoly).*")

        // SKRIP JINAK: Disuntikkan ke dalam WebView untuk menetralisir 'disable-devtools' agar tidak membekukan mesin JS
        val bypassAntiDebugScript = """
            Object.defineProperty(window, 'devtools', { get: function() { return { isOpen: false }; } });
            Function.prototype.constructor = function(str) { 
                if (str === 'debugger') return function(){}; 
                return Function(str); 
            };
        """.trimIndent()

        var linksFound = 0

        try {
            // Jalankan WebView langsung mengarah ke halaman frontend utama seperti isi DevTools kamu
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = playerRegex,
                userAgent = null, // Menggunakan User-Agent bawaan mesin Chromium HP agar Turnstile lolos
                useOkhttp = false,
                script = bypassAntiDebugScript // Menyuntikkan penjinak anti-debug
            )

            // WebView memproses halaman secara natural di latar belakang
            val (interceptedRequest, _) = webViewResolver.resolveUsingWebView(
                url = data, // KUNCI: Muat langsung rivestream.app/movie/... bukan embed primesrc
                referer = "$mainUrl/"
            )

            val realEmbedUrl = interceptedRequest?.url?.toString()

            if (!realEmbedUrl.isNullOrBlank()) {
                // KUNCI UTAMA: Jika cdn utama '1shows' yang berhasil kita tangkap lewat radar
                if (realEmbedUrl.contains("1shows")) {
                    callback(newExtractorLink(
                        source = providerName,
                        name = "RiveStream CDN",
                        url = realEmbedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // Pasang paspor header persis seperti parameter perintah 'curl' suksesmu
                        this.referer = "https://www.rivestream.app/"
                        this.headers = mapOf(
                            "Origin" to "https://www.rivestream.app",
                            "Accept" to "*/*"
                        )
                    })
                    linksFound++
                } else {
                    // Jika yang tertangkap adalah cermin server alternatif (Streamtape, Voe, Filemoon, dll.)
                    val isExtractorFound = loadExtractor(
                        url = realEmbedUrl,
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    if (!isExtractorFound) {
                        callback(newExtractorLink(
                            source = providerName,
                            name = "Mirror Video",
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
