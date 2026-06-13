package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import com.lagradost.nicehttp.Requests
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HostnameVerifier
import java.util.concurrent.TimeUnit

class PrimeSrcHelper {

    companion object {
        // -------------------------------------------------------------------------
        // UNASAFE REQUESTS INSTANCE: Kebal SSL Expired & Longgar Timeout Soket
        // -------------------------------------------------------------------------
        private val unsafeRequests: Requests by lazy {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("SSL").apply {
                init(null, trustAllCerts, java.security.SecureRandom())
            }
            
            // Konfigurasi timeout dinaikkan ke 15 detik untuk mematikan SocketTimeoutException
            val customOkhttp = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                .build()
                
            Requests(customOkhttp)
        }
    }

    private fun generateDynamicSecretKey(mediaId: String?): String {
        // Mengunci token master valid hasil pembuktian mutlak Termux lo (36f1fcfc)
        return "MzZmMWZjZjc="
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

        val standardHeaders = mapOf(
            "Authority" to "www.rivestream.app",
            "Accept" to "application/json",
            "Referer" to "$mainUrl/watch?type=${if (isMovie) "movie" else "tv"}&id=$cleanId",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        // -------------------------------------------------------------------------
        // JALUR UTAMA 1: Pemanenan API backendfetch Internal (Bypass Turnstile & WebView)
        // -------------------------------------------------------------------------
        try {
            val requestId = if (isMovie) "movieVideoProvider" else "tvVideoProvider"
            val secretKey = generateDynamicSecretKey(cleanId)

            val servicesListUrl = "$mainUrl/api/backendfetch?requestID=VideoProviderServices&secretKey=rive&proxyMode=noProxy"
            val servicesResponse = unsafeRequests.get(servicesListUrl, headers = standardHeaders).text
            val parsedServices = tryParseJson<BackendServicesResponse>(servicesResponse)
            val activeServices = parsedServices?.data ?: listOf("primevids", "flowcast", "asiacloud", "hindicast", "guru", "ophim")

            var baseApiUrl = "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId"

            activeServices.amap { service ->
                try {
                    val proxyMode = if (service == "primevids") "undefined" else "noProxy"
                    val finalApiUrl = if (isMovie) {
                        "$baseApiUrl&service=$service&secretKey=$secretKey&proxyMode=$proxyMode"
                    } else {
                        val season = cleanData.substringAfter("?season=").substringBefore("&")
                        val episode = cleanData.substringAfter("&episode=")
                        "$mainUrl/api/backendfetch?requestID=$requestId&id=$cleanId&service=$service&secretKey=$secretKey&proxyMode=$proxyMode&season=$season&episode=$episode"
                    }

                    val response = unsafeRequests.get(finalApiUrl, headers = standardHeaders).text
                    val parsedData = tryParseJson<BackendFetchResponse>(response) ?: return@amap
                    val sources = parsedData.data?.sources ?: return@amap

                    // PENYELAMAT COROUTINE: Perloopingan 'for' tradisional menjamin pemanggilan suspend fun legal
                    val captions = parsedData.data.captions
                    if (captions != null) {
                        for (caption in captions) {
                            val captionUrl = caption.file ?: continue
                            val captionLabel = caption.label ?: "External Subtitle"
                            subtitleCallback(newSubtitleFile(lang = captionLabel, url = captionUrl))
                        }
                    }

                    for (source in sources) {
                        val streamUrl = source.url ?: continue
                        val qualityName = source.quality?.toString()?.uppercase() ?: "AUTO"
                        val sourceLabel = source.source ?: "RiveStream"
                        val displayName = "$sourceLabel - $qualityName"

                        if (streamUrl.contains(".m3u8") || source.format?.lowercase() == "hls") {
                            // Inisialisasi tautan asinkronus ke variabel lokal sebelum dilempar ke callback non-suspend
                            val link = newExtractorLink(
                                source = providerName,
                                name = displayName,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = getQualityFromName(qualityName)
                                this.referer = "$mainUrl/"
                                this.headers = mapOf("Origin" to mainUrl, "Accept" to "*/*")
                            }
                            callback(link)
                            synchronized(this) { linksFound++ }
                        } else {
                            val targetReferer = if (service == "flowcast") "https://123movienow.cc/" else "$mainUrl/"
                            val isExtractorFound = loadExtractor(url = streamUrl, referer = targetReferer, subtitleCallback, callback)
                            
                            if (!isExtractorFound && !streamUrl.contains("/e/")) {
                                val link = newExtractorLink(source = providerName, name = displayName, url = streamUrl) {
                                    this.quality = getQualityFromName(qualityName)
                                    this.referer = targetReferer
                                }
                                callback(link)
                                synchronized(this) { linksFound++ }
                            }
                        }
                    }
                } catch (e: Exception) { 
                    e.printStackTrace() 
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }

        // -------------------------------------------------------------------------
        // JALUR CADANGAN 2: Eksplorasi Server Embed PrimeSrc (Hanya Jika Jalur 1 Kosong)
        // -------------------------------------------------------------------------
        if (linksFound == 0) {
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
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                )
                
                val primeSrcResponse = unsafeRequests.get(primeSrcApiUrl, headers = primeSrcHeaders).text
                val parsedPrimeSrc = tryParseJson<PrimeSrcServerResponse>(primeSrcResponse)

                val sortedServers = parsedPrimeSrc?.servers?.sortedByDescending { server ->
                    val name = server.name?.lowercase() ?: ""
                    name.contains("streamtape") || name.contains("voe") || name.contains("mixdrop")
                }

                sortedServers?.amap { server ->
                    val serverName = server.name?.lowercase() ?: return@amap
                    val serverKey = server.key ?: return@amap
                    
                    val embedUrl = when {
                        serverName.contains("streamtape") -> "https://streamtape.com/e/$serverKey"
                        serverName.contains("voe") -> "https://voe.sx/e/$serverKey"
                        serverName.contains("streamwish") -> "https://streamwish.to/e/$serverKey"
                        serverName.contains("filemoon") -> "https://filemoon.sx/e/$serverKey"
                        serverName.contains("dood") -> "https://dood.to/e/$serverKey"
                        serverName.contains("mixdrop") -> "https://mixdrop.co/e/$serverKey"
                        serverName.contains("filelions") -> "https://filelions.to/e/$serverKey"
                        else -> null
                    }

                    if (embedUrl != null) {
                        try {
                            loadExtractor(embedUrl, referer = "https://primesrc.me/", subtitleCallback, callback)
                        } catch (e: Exception) { 
                            e.printStackTrace() 
                        }
                    }
                }
            } catch (e: Exception) { 
                e.printStackTrace() 
            }
        }

        return linksFound > 0
    }
}

// ===== DATA CLASSES LAYOUT PARSER =====
data class BackendServicesResponse(@JsonProperty("data") val data: List<String>?)
data class BackendFetchResponse(@JsonProperty("data") val data: BackendData?)
data class BackendData(
    @JsonProperty("sources") val sources: List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)
data class BackendSource(
    @JsonProperty("quality") val quality: Any?, 
    @JsonProperty("url") val url: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("format") val format: String?
)
data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file") val file: String?
)
data class PrimeSrcServerResponse(@JsonProperty("servers") val servers: List<PrimeSrcServer>?)
data class PrimeSrcServer(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key") val key: String?
)
