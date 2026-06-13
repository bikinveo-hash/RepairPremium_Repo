package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Base64

class PrimeSrcHelper {

    // -------------------------------------------------------------------------
    // ENGINE DE-PACKER USER-SCRIPT (PORTING LOGIKA VIOLENTMONKEY KE KOTLIN)
    // -------------------------------------------------------------------------
    private fun unpackDeanEdwardsPacker(packedCode: String): String {
        try {
            val pattern = Regex("""\}\s*\('\s*(.*?)\s*'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'\s*(.*?)\s*'""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(packedCode) ?: return ""
            
            val p = match.groupValues[1].replace("\\'", "'")
            val a = match.groupValues[2].toInt()
            val c = match.groupValues[3].toInt()
            val k = match.groupValues[4].split("|")
            
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            
            fun baseN(num: Int, b: Int): String {
                if (num == 0) return "0"
                var n = num
                var r = ""
                while (n > 0) {
                    r = chars[n % b] + r
                    n /= b
                }
                return r
            }
            
            var unpacked = p
            for (i in c - 1 downTo 0) {
                if (i < k.size && k[i].isNotEmpty()) {
                    val word = k[i]
                    val key = baseN(i, a)
                    unpacked = unpacked.replace(Regex("\\b$key\\b"), word)
                }
            }
            return unpacked
        } catch (e: Exception) {
            return ""
        }
    }

    suspend fun invokePrimeSrc(
        data: String, 
        mainUrl: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val cleanData = data.replace("$mainUrl/", "")
        val isMovie = !cleanData.contains("?season=")
        val cleanId = cleanData.substringBefore("?").substringAfterLast("/")
        var linksFound = 0

        // -------------------------------------------------------------------------
        // JALUR UTAMA: Gateway Server Embed PrimeSrc (Pola Rantai Log Jaringan Riil)
        // -------------------------------------------------------------------------
        try {
            val typeParam = if (isMovie) "movie" else "tv"
            var primeSrcApiUrl = "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=$typeParam"
            
            if (!isMovie) {
                val season = cleanData.substringAfter("?season=").substringBefore("&")
                val episode = cleanData.substringAfter("&episode=")
                primeSrcApiUrl += "&season=$season&episode=$episode"
            }

            val primeSrcHeaders = mapOf(
                "Referer" to "https://primesrc.me/embed/$typeParam?tmdb=$cleanId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*"
            )
            
            val primeSrcResponse = app.get(primeSrcApiUrl, headers = primeSrcHeaders).text
            val parsedPrimeSrc = tryParseJson<PrimeSrcServerResponse>(primeSrcResponse)

            parsedPrimeSrc?.servers?.forEach { server ->
                val proxyKey = server.key ?: return@forEach
                val serverName = server.name?.lowercase() ?: ""
                
                try {
                    // Handshake /spiderman & tukar token via /api/v1/l untuk menarik Link Player Asli
                    val spidermanUrl = "https://primesrc.me/spiderman?l=$proxyKey"
                    app.get(spidermanUrl, headers = primeSrcHeaders)

                    val resolverUrl = "https://primesrc.me/api/v1/l?key=$proxyKey"
                    val resolveResponse = app.get(resolverUrl, headers = primeSrcHeaders).text
                    val resolvedData = tryParseJson<PrimeSrcLinkResult>(resolveResponse)
                    val realEmbedUrl = resolvedData?.link ?: return@forEach

                    // LOGIKA HOOKING VIOLENTMONKEY: Cegah WebView macet pada Streamwish / Filemoon
                    if (serverName.contains("streamwish") || serverName.contains("filemoon") || serverName.contains("filelions")) {
                        val desktopHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Referer" to "https://primesrc.me/"
                        )
                        // Ambil raw source HTML murni tanpa merrender skrip iklan
                        val rawHtml = app.get(realEmbedUrl, headers = desktopHeaders).text
                        
                        if ("eval(function(p,a,c,k,e,d)" in rawHtml) {
                            val unpackedScript = unpackDeanEdwardsPacker(rawHtml)
                            // Ekstrak direct stream m3u8 dari kode biner hasil bongkaran
                            val m3u8Match = Regex("""https?://[^\s"'><]+?\.m3u8[^\s"'><]*""").find(unpackedScript)
                            val finalStreamUrl = m3u8Match?.value
                            
                            if (finalStreamUrl != null) {
                                callback(newExtractorLink(
                                    source = providerName,
                                    name = "${server.name ?: "Mirror"} - HIGH (Direct)",
                                    url = finalStreamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = 1080
                                    this.referer = realEmbedUrl
                                })
                                linksFound++
                                return@forEach // Sukses besar, lewati pemanggilan loadExtractor WebView!
                            }
                        }
                    }

                    // Tembak menggunakan extractor bawaan core untuk server aman (Streamtape, Voe, Mixdrop)
                    val isExtracted = loadExtractor(realEmbedUrl, subtitleCallback, callback)
                    if (isExtracted) linksFound++
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }

        return linksFound > 0
    }
}

// ===== DATA CLASSES MODEL =====
data class PrimeSrcServerResponse(@JsonProperty("servers") val servers: List<PrimeSrcServer>?)
data class PrimeSrcServer(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key") val key: String?
)
data class PrimeSrcLinkResult(
    @JsonProperty("link") val link: String?,
    @JsonProperty("host") val host: String? = null
)
