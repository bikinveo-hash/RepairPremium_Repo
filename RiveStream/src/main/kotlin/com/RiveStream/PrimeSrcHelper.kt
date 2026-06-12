package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

object PrimeSrcHelper {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    suspend fun getPrimeSrcLinks(
        cleanId: String,
        isMovie: Boolean,
        cleanData: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val primeSrcHeaders = mapOf(
            "Referer" to "https://primesrc.me/embed/${if (isMovie) "movie" else "tv"}?tmdb=$cleanId",
            "User-Agent" to USER_AGENT
        )

        var responseText = ""
        
        // FIX UNTUK SERIAL: Membuka Sumbatan Otomatis Kueri Dinamis TV Shows & Series
        try {
            val apiUrl = if (isMovie) {
                "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=movie"
            } else {
                val season = cleanData.substringAfter("?season=").substringBefore("&")
                val episode = cleanData.substringAfter("&episode=")
                "https://primesrc.me/api/v1/s?tmdb=$cleanId&type=tv&season=$season&episode=$episode"
            }
            responseText = app.get(apiUrl, headers = primeSrcHeaders).text
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // AMAN: Mekanisme Kamunflase Laba-laba Tetap Aktif Sebagai Jalur Alternatif
        if (responseText.isBlank() || !responseText.contains("servers")) {
            try {
                val fallbackUrl = "https://primesrc.me/spiderman?l=dLkjO"
                responseText = app.get(fallbackUrl, headers = primeSrcHeaders).text
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val parsedResponse = tryParseJson<PrimeSrcServerResponse>(responseText) ?: return
        
        parsedResponse.servers?.forEach { server ->
            val serverKey = server.key ?: return@forEach
            
            try {
                // Eksekusi Tukar Token Menjadi URL Embed Hulu Murni (Mendukung Semua Server Cadangan)
                val tokenExchangeUrl = "https://primesrc.me/api/v1/l?key=$serverKey"
                val exchangeResponse = app.get(tokenExchangeUrl, headers = primeSrcHeaders).text
                val resolvedData = tryParseJson<PrimeSrcExchangeResult>(exchangeResponse)
                
                val realEmbedUrl = resolvedData?.link ?: return@forEach

                // FIX SUBTITLE INDONESIA: Suntikan Otomatis Subtitle Terjemahan Menggunakan Pola Prediktif VOE
                if (realEmbedUrl.contains("voe.sx") || realEmbedUrl.contains("jeanprofessorcentral.com")) {
                    val activeHost = realEmbedUrl.substringAfter("://").substringBefore("/")
                    val fileCode = realEmbedUrl.substringAfter("/e/").substringBefore("?").trim()
                    
                    val indonesianSubtitleUrl = "https://$activeHost/vtt/${fileCode}_id.srt"
                    
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = "Indonesian",
                            url = indonesianSubtitleUrl
                        )
                    )
                }

                // Kirim URL Hasil Resolusi ke Mesin Extractor Bawaan Cloudstream (Mendukung Semua Hoster)
                loadExtractor(realEmbedUrl, subtitleCallback, callback)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

// ================= MODEL DATA PARSING (PRIME_SRC) =================
data class PrimeSrcServerResponse(
    @JsonProperty("servers") val servers: List<PrimeSrcServerItem>?
)

data class PrimeSrcServerItem(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key") val key: String?
)

data class PrimeSrcExchangeResult(
    @JsonProperty("link") val link: String?,
    @JsonProperty("host") val host: String?
)
