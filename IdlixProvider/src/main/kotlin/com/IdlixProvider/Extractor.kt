package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, 
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("adixtream", "Jeniusplay Ekstraktor Memproses: $url")
            
            // 1. Ambil HTML dari Jeniusplay & TANGKAP COOKIE-NYA!
            val response = app.get(url, referer = referer)
            val htmlContent = response.text
            
            // 🔥 MERAMPOK COOKIE SESSION (Untuk disuapkan ke ExoPlayer)
            val cookies = response.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // Siapkan Header Khusus untuk ExoPlayer
            val playerHeaders = mapOf(
                "Referer" to url,
                "Cookie" to cookieString,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            )
            
            // 2. Ekstrak Subtitle
            val subtitleRegex = """var\s+playerjsSubtitle\s*=\s*["'](.*?)["']""".toRegex()
            subtitleRegex.find(htmlContent)?.groupValues?.get(1)?.let { subStr ->
                val tracks = subStr.split(",")
                for (track in tracks) {
                    val langMatch = """\[(.*?)\](.*)""".toRegex().find(track)
                    if (langMatch != null) {
                        val lang = getLanguage(langMatch.groupValues[1])
                        val subUrl = langMatch.groupValues[2]
                        subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                    }
                }
            }

            // 3. Bongkar Javascript Packer
            val unpackedText = getAndUnpack(htmlContent).replace("\\/", "/")
            
            // 4. Cari Link Video (Fokus ekstensi master.txt / .m3u8)
            val videoRegex = """(https?://[^"'\s]+(?:master\.txt|\.m3u8))""".toRegex()
            var videoUrl = videoRegex.find(unpackedText)?.groupValues?.get(1)

            // 5. Fallback ke API do=getVideo
            if (videoUrl.isNullOrEmpty()) {
                Log.d("adixtream", "Unpack JS gagal, mencoba fallback ke API do=getVideo...")
                val hashRegex = """([a-zA-Z0-9]{30,})""".toRegex()
                var hash = url.substringAfter("data=", "").substringBefore("&")
                
                if (hash.isBlank()) {
                    hash = hashRegex.find(url)?.groupValues?.get(1) ?: url.split("/").last()
                }
                
                val apiUrl = "$mainUrl/player/index.php?data=$hash&do=getVideo"
                val apiResponse = app.post(
                    url = apiUrl,
                    data = mapOf("hash" to hash, "r" to (referer ?: mainUrl)),
                    referer = url,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Cookie" to cookieString // Pastikan API fallback juga pakai Cookie
                    )
                ).parsedSafe<ResponseSource>()
                
                val rawVideoSource = apiResponse?.videoSource
                if (!rawVideoSource.isNullOrEmpty()) {
                    // Bypass ekstensi woff/txt dari Cloudflare
                    videoUrl = rawVideoSource.replace(".woff", ".m3u8").replace(".txt", ".m3u8")
                }
            }

            // 6. Lempar Link ke ExtractorLink dengan COOKIE INJECTOR
            if (!videoUrl.isNullOrEmpty()) {
                Log.d("adixtream", "Jeniusplay berhasil menemukan video: $videoUrl")
                
                // Ekstraktor M3U8 (Pakai playerHeaders yang sudah ada Cookienya)
                generateM3u8(name, videoUrl, url, headers = playerHeaders).forEach(callback)
                
                // Ekstraktor Direct
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (Direct)",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        // 🔥 SUNTIKKAN HEADER & COOKIE KE EXOPLAYER
                        this.headers = playerHeaders 
                    }
                )
            } else {
                Log.d("adixtream", "Jeniusplay gagal mendapat videoSource sama sekali.")
            }
        } catch (e: Exception) {
            Log.e("adixtream", "Jeniusplay Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            str.contains("english", true) -> "English"
            else -> str
        }
    }
}

// Data Class Fallback Jeniusplay
data class ResponseSource(
    @JsonProperty("videoSource") val videoSource: String = ""
)
