package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

        // Paksa User-Agent menjadi identitas Chrome Android tulen
        val browserUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.113 Mobile Safari/537.36"

        // STEP 1: Jalankan WebViewResolver untuk memicu lolosnya tantangan Cloudflare (cf_clearance)
        try {
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = Regex(".*primesrc\\.me/api/v1/s.*"),
                userAgent = browserUa,
                useOkhttp = false
            )
            webViewResolver.resolveUsingWebView(url = embedUrl, referer = "$mainUrl/")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Pastikan sinkronisasi Cookie telah selesai
        android.webkit.CookieManager.getInstance().flush()
        var primeCookies = android.webkit.CookieManager.getInstance().getCookie("https://primesrc.me") ?: ""
        var attempt = 0

        // Tunggu hingga cf_clearance masuk memori (Maksimal 5 detik)
        while (!primeCookies.contains("cf_clearance") && attempt < 5) {
            delay(1000)
            android.webkit.CookieManager.getInstance().flush()
            primeCookies = android.webkit.CookieManager.getInstance().getCookie("https://primesrc.me") ?: ""
            attempt++
        }

        // Jika Bypass gagal (cookie tidak dapat), hentikan agar aplikasi tidak Timeout/Hang
        if (!primeCookies.contains("cf_clearance")) {
            return false 
        }

        // STEP 2: Headers — samakan User-Agent persis dengan sesi WebView tadi
        val headers = mapOf(
            "User-Agent" to browserUa,
            "Referer" to embedUrl,
            "Accept" to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "Cookie" to primeCookies
        )

        var linksFound = 0

        // STEP 3: Ambil daftar server & urai tautan menggunakan Extractor bawaan Cloudstream
        try {
            val response = app.get(serversUrl, headers = headers).text
            val parsed = tryParseJson<PrimeSrcResponse>(response) ?: return false
            val servers = parsed.servers ?: return false

            for (server in servers) {
                val internalKey = server.key ?: continue
                try {
                    val spidermanUrl = "https://primesrc.me/spiderman?l=$internalKey"
                    app.get(spidermanUrl, headers = headers)

                    val decryptUrl = "https://primesrc.me/api/v1/l?key=$internalKey"
                    val decryptResponse = app.get(decryptUrl, headers = headers).text
                    val linkData = tryParseJson<PrimeSrcLinkResponse>(decryptResponse) ?: continue
                    val realEmbedUrl = linkData.link ?: continue
                    val serverName = server.name ?: linkData.host ?: "Direct Link"

                    val isExtractorFound = loadExtractor(
                        url = realEmbedUrl,
                        referer = embedUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    if (!isExtractorFound) {
                        callback(newExtractorLink(
                            source = providerName,
                            name = serverName,
                            url = realEmbedUrl
                        ) {
                            this.referer = embedUrl
                        })
                    }
                    linksFound++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return linksFound > 0
    }
}

// =========================================================================
// DATA CLASSES KHUSUS PRIMESRC API
// Pastikan bagian di bawah ini tidak terhapus saat melakukan copy-paste!
// =========================================================================

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
