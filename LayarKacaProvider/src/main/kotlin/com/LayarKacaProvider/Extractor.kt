package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Lk21TurboExtractor : ExtractorApi() {
    override var name      = "LK21 TurboVIP"
    // Kembali pakai turbovidhls karena Inspector membuktikan ini domain yang valid
    override var mainUrl   = "https://turbovidhls.com" 
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty()) return null

            val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
            val baseHeaders = mapOf(
                "User-Agent" to ua,
                "Referer"    to "https://playeriframe.sbs/"
            )

            val targetUrl = "$mainUrl/t/$id"
            var html = ""
            
            // 1. TEMBUS CLOUDFLARE: Jika diblokir (403/503), gunakan WebView siluman!
            var response = runCatching { app.get(targetUrl, headers = baseHeaders) }.getOrNull()
            
            if (response == null || response.code != 200 || response.text.contains("Just a moment") || response.text.contains("cloudflare")) {
                val webViewResolver = WebViewResolver(
                    interceptUrl = Regex(".*turbovidhls.*|.*emturbovid.*"),
                    useOkhttp = false
                )
                webViewResolver.resolveUsingWebView(targetUrl, headers = baseHeaders)
                
                // Coba ambil lagi setelah Cookie didapatkan
                response = runCatching { app.get(targetUrl, headers = baseHeaders) }.getOrNull()
                html = response?.text ?: ""
            } else {
                html = response.text
            }

            var m3u8UrlRaw = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8UrlRaw.isNullOrBlank()) {
                m3u8UrlRaw = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }
            
            val m3u8Url = m3u8UrlRaw?.takeIf { it.isNotBlank() } ?: return null

            if (m3u8Url.endsWith(".mp4", ignoreCase = true)) {
                sources.add(
                    newExtractorLink("LK21 TurboVIP", "TurboVIP HD", m3u8Url, ExtractorLinkType.VIDEO) {
                        this.headers = mapOf("Origin" to mainUrl, "User-Agent" to ua)
                    }
                )
                return sources
            }

            // ====================================================================
            // 2. HTTP CLEARTEXT BYPASS: Konversi ke HTTPS lalu bungkus via Data URI
            // ====================================================================
            try {
                // Tembus proteksi CDN M3U8
                var masterContent = ""
                val m3u8Res = runCatching { app.get(m3u8Url, headers = baseHeaders) }.getOrNull()
                
                if (m3u8Res == null || m3u8Res.code != 200 || m3u8Res.text.contains("cloudflare")) {
                    val webViewResolver = WebViewResolver(
                        interceptUrl = Regex(".*\\.m3u8.*"),
                        useOkhttp = false
                    )
                    webViewResolver.resolveUsingWebView(m3u8Url, headers = baseHeaders)
                    masterContent = runCatching { app.get(m3u8Url, headers = baseHeaders).text }.getOrNull() ?: ""
                } else {
                    masterContent = m3u8Res.text
                }

                if (masterContent.isBlank()) throw Exception("M3U8 Kosong")

                val isMasterPlaylist = masterContent.contains("#EXT-X-STREAM-INF")

                if (isMasterPlaylist) {
                    val lines = masterContent.lines()
                    var currentQuality = Qualities.Unknown.value

                    for (i in lines.indices) {
                        val line = lines[i].trim()
                        
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                            if (resMatch != null) {
                                currentQuality = resMatch.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
                            }
                        } 
                        else if (line.isNotEmpty() && !line.startsWith("#")) {
                            var subUrl = line
                            if (!subUrl.startsWith("http")) {
                                subUrl = "${m3u8Url.substringBeforeLast("/")}/$subUrl"
                            }

                            try {
                                val subContent = app.get(subUrl, headers = baseHeaders).text
                                
                                var exoHeaders = mapOf(
                                    "Origin" to "https://turbovidhls.com",
                                    "Referer" to "https://turbovidhls.com/",
                                    "User-Agent" to ua
                                )

                                if (subContent.contains("googleusercontent.com")) {
                                    // Haram pakai Referer di GDrive
                                    exoHeaders = mapOf("User-Agent" to ua)
                                }

                                val baseUrl = subUrl.substringBeforeLast("/")
                                val rewritten = subContent.lines().map { t ->
                                    val tr = t.trim()
                                    when {
                                        tr.isEmpty() || tr.startsWith("#") -> tr
                                        tr.startsWith("http://") -> tr.replace("http://", "https://")
                                        tr.startsWith("https://") -> tr
                                        else -> "$baseUrl/$tr"
                                    }
                                }.joinToString("\n")

                                val b64 = "data:application/x-mpegURL;base64," + android.util.Base64.encodeToString(rewritten.toByteArray(), android.util.Base64.NO_WRAP)

                                sources.add(
                                    newExtractorLink(
                                        source = "LK21 TurboVIP",
                                        name = "TurboVIP ${if (currentQuality == Qualities.Unknown.value) "HD" else "${currentQuality}p"}",
                                        url = b64,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "" 
                                        this.quality = currentQuality
                                        this.headers = exoHeaders
                                    }
                                )
                            } catch (e: Exception) {}
                        }
                    }
                } else {
                    // Jika tidak ada variasi resolusi (Single M3U8)
                    var exoHeaders = mapOf(
                        "Origin" to "https://turbovidhls.com",
                        "Referer" to "https://turbovidhls.com/",
                        "User-Agent" to ua
                    )

                    if (masterContent.contains("googleusercontent.com")) {
                        exoHeaders = mapOf("User-Agent" to ua)
                    }

                    val baseUrl = m3u8Url.substringBeforeLast("/")
                    val rewritten = masterContent.lines().map { t ->
                        val tr = t.trim()
                        when {
                            tr.isEmpty() || tr.startsWith("#") -> tr
                            tr.startsWith("http://") -> tr.replace("http://", "https://")
                            tr.startsWith("https://") -> tr
                            else -> "$baseUrl/$tr"
                        }
                    }.joinToString("\n")

                    val b64 = "data:application/x-mpegURL;base64," + android.util.Base64.encodeToString(rewritten.toByteArray(), android.util.Base64.NO_WRAP)

                    sources.add(
                        newExtractorLink("LK21 TurboVIP", "TurboVIP HD", b64, ExtractorLinkType.M3U8) {
                            this.referer = ""
                            this.quality = Qualities.Unknown.value
                            this.headers = exoHeaders
                        }
                    )
                }
            } catch (e: Exception) {
                // Fallback paling akhir jika modifikasi Base64 gagal
                sources.add(
                    newExtractorLink("LK21 TurboVIP", "TurboVIP HD", m3u8Url, ExtractorLinkType.M3U8) {
                        this.headers = mapOf("Origin" to "https://turbovidhls.com", "User-Agent" to ua)
                    }
                )
            }

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
        }
        return sources
    }
}
