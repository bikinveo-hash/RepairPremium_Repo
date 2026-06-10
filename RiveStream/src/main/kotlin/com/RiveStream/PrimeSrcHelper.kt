package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

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

        // STEP 1: Jalankan WebViewResolver untuk memicu lolosnya tantangan Cloudflare (cf_clearance)
        try {
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = Regex(".*primesrc\\.me/api/v1/s.*"),
                useOkhttp = false // WAJIB false agar WebView menyelesaikan sendiri proteksi CF
            )
            webViewResolver.resolveUsingWebView(url = embedUrl, referer = "$mainUrl/")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // =========================================================================
        // EKSTRAK COOKIES DARI WEBVIEW
        // Ini rahasia utama agar OkHttp tidak terkena Silent Drop / Timeout dari Cloudflare
        // =========================================================================
        val primeCookies = android.webkit.CookieManager.getInstance().getCookie("https://primesrc.me") ?: ""

        // STEP 2: Susun Headers persis seperti analisis DevTools milikmu
        val headers = mapOf(
            "User-Agent" to (com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent ?: USER_AGENT),
            "Referer" to embedUrl,
            "Accept" to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7", // Mengikuti log analisis browser kamu
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "Cookie" to primeCookies // Menyisipkan token cf_clearance yang valid
        )

        var linksFound = 0

        // STEP 3: Tembak API Server List menggunakan OkHttp yang sudah dipasangi Cookie lolos tantangan
        try {
            val response = app.get(serversUrl, headers = headers).text
            val parsed = tryParseJson<PrimeSrcResponse>(response) ?: return false
            val servers = parsed.servers ?: return false

            for (server in servers) {
                val internalKey = server.key ?: continue
                try {
                    // Eksekusi spiderman endpoint
                    val spidermanUrl = "https://primesrc.me/spiderman?l=$internalKey"
                    app.get(spidermanUrl, headers = headers)

                    // Jalankan dekripsi key untuk mengambil link embed video asli
                    val decryptUrl = "https://primesrc.me/api/v1/l?key=$internalKey"
                    val decryptResponse = app.get(decryptUrl, headers = headers).text
                    val linkData = tryParseJson<PrimeSrcLinkResponse>(decryptResponse) ?: continue
                    val realEmbedUrl = linkData.link ?: continue
                    val serverName = server.name ?: linkData.host ?: "Direct Link"

                    // DELEGASIKAN KE EXTRACTOR CORE (Streamtape, Voe, Filemoon, dll.)
                    val isExtractorFound = loadExtractor(
                        url = realEmbedUrl,
                        referer = embedUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )

                    // Jika Cloudstream core tidak punya script extractor-nya, kirim sebagai link direct mentah
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

// ===== DATA CLASSES PRIMESRC API =====

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
