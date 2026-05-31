package com.LayarKacaProvider

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Lk21TurboExtractor : ExtractorApi() {
    override var name      = "LK21 TurboVIP"
    override var mainUrl   = "https://emturbovid.com"
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

            val response = app.get("$mainUrl/t/$id", headers = baseHeaders)
            val html     = response.text

            var m3u8UrlRaw = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8UrlRaw.isNullOrBlank()) {
                m3u8UrlRaw = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }
            
            // Fix Kotlin Nullability
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
            // HYPER GOD MODE: Multi-Quality Extractor + Auto CDN Header Resolver
            // Mencegah Error 2001 (HTTP Cleartext) & Error 429 (Google Drive Hotlink)
            // ====================================================================
            try {
                val masterContent = app.get(m3u8Url, headers = baseHeaders).text
                val isMasterPlaylist = masterContent.contains("#EXT-X-STREAM-INF")

                if (isMasterPlaylist) {
                    val lines = masterContent.lines()
                    var currentQuality = Qualities.Unknown.value

                    for (i in lines.indices) {
                        val line = lines[i].trim()
                        
                        // Deteksi Resolusi Video (Misal: 854x480 -> 480p)
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                            if (resMatch != null) {
                                currentQuality = resMatch.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
                            }
                        } 
                        // Ambil tautan Sub-Playlist
                        else if (line.isNotEmpty() && !line.startsWith("#")) {
                            var subUrl = line
                            if (!subUrl.startsWith("http")) {
                                subUrl = "${m3u8Url.substringBeforeLast("/")}/$subUrl"
                            }

                            try {
                                val subContent = app.get(subUrl, headers = baseHeaders).text
                                
                                // Deteksi cerdas: CDN apa yang dipakai episode ini?
                                var exoHeaders = mapOf(
                                    "Origin" to "https://turbovidhls.com",
                                    "Referer" to "https://turbovidhls.com/",
                                    "User-Agent" to ua
                                )

                                if (subContent.contains("googleusercontent.com")) {
                                    // Google Drive Benci Origin. Hapus semuanya!
                                    exoHeaders = mapOf("User-Agent" to ua)
                                }

                                // Amankan tautan HTTP menjadi HTTPS
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

                                // Bungkus jadi Base64 Data URI agar tidak terdeteksi oleh Cronet
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
                    // Jika langsung file anak tunggal (.ts segment)
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
                // Fallback darurat
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
