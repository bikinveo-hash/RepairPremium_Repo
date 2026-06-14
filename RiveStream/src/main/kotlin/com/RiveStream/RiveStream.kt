package com.RiveStream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.app

class RiveStreamProvider : MainAPI() {
    override var name = "RiveStream"
    override var mainUrl = "https://www.rivestream.app"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    // Mewajibkan engine untuk mengaktifkan sesi sinkronisasi Cookie lintas domain via WebView
    override val usesWebView = true

    override suspend fun search(query: String): List<SearchResponse> {
        // Mengembalikan list kosong untuk inisialisasi pencarian dasbor lokal
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Kerangka dasar pembungkus visual judul tontonan di aplikasi
        return MovieLoadResponse(
            name = "Project Hail Mary",
            url = url,
            apiName = this.name,
            type = TvType.Movie,
            dataUrl = url,
            posterUrl = "https://www.rivestream.app/fallback-poster.jpg"
        )
    }

    override suspend fun loadLinks(
        dataUrl: String,
        isCues: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Menarik ID media film dari parameter rute URL (contoh: 687163)
        val mediaId = dataUrl.substringAfter("id=").substringBefore("&")
        if (mediaId.isEmpty()) return false

        // -----------------------------------------------------------------
        // JALUR UTAMA 1: NEXT.JS BACKEND FETCH API INTERNAL
        // -----------------------------------------------------------------
        val secretKey = "MzZmMWZjZjc=" 
        val activeServices = listOf("flowcast", "asiacloud", "guru", "ophim")
        
        for (service in activeServices) {
            try {
                val apiTarget = "$mainUrl/api/backendfetch?requestID=movieVideoProvider&id=$mediaId&service=$service&secretKey=$secretKey&proxyMode=noProxy"
                val res = app.get(
                    apiTarget,
                    headers = mapOf("Referer" to dataUrl)
                ).parsedNull<Map<String, Any>>()
                
                val data = res?.get("data") as? Map<*, *>
                val sources = data?.get("sources") as? List<*>
                sources?.forEach { src ->
                    val srcMap = src as? Map<*, *>
                    val videoUrl = srcMap?.get("url") as? String
                    if (!videoUrl.isNullOrEmpty()) {
                        loadExtractor(videoUrl, dataUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Mengabaikan error jaringan kecil dan lanjut menyisir kluster service berikutnya
            }
        }

        // -----------------------------------------------------------------
        // JALUR UTAMA 2: PRIMESRC AUTOMATED WEBVIEW INTERCEPT (BYPASS TURNSTILE)
        // -----------------------------------------------------------------
        try {
            val primeEmbedUrl = "https://primesrc.me/embed/movie?tmdb=$mediaId"
            
            // Membuka halaman embed di dalam WebView tersembunyi, membiarkan Cloudflare Turnstile 
            // diselesaikan oleh engine Chromium HP, lalu langsung MENCEGAT URL cermin player
            val automatedInterceptor = WebViewResolver(
                Regex(".*(?:streamta\\.site|tpead\\.net|streamtape\\.com)/e/.*")
            )
            
            val solvedResponse = app.get(primeEmbedUrl, interceptor = automatedInterceptor)
            val interceptedUrl = solvedResponse.url
            
            if (interceptedUrl.contains("/e/")) {
                loadExtractor(interceptedUrl, "https://primesrc.me/", subtitleCallback, callback)
                return true // Berhasil memotong kompas via jembatan intercept biner WebView
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // -----------------------------------------------------------------
        // JALUR FALLBACK 3: MANUAL REQABLE SESSION EXCHANGE
        // -----------------------------------------------------------------
        try {
            val primeApi = "https://primesrc.me/api/v1/s?tmdb=$mediaId&type=movie"
            val primeRes = app.get(
                primeApi,
                headers = mapOf("Referer" to "https://primesrc.me/embed/movie?tmdb=$mediaId")
            ).parsedNull<PrimeSrcResponse>()

            primeRes?.servers?.forEach { srv ->
                val serverName = srv.name?.lowercase() ?: ""
                val serverKey = srv.key ?: ""

                if (serverName.contains("voe")) {
                    val voeEmbedUrl = "https://voe.un/e/$serverKey"
                    loadExtractor(voeEmbedUrl, "https://primesrc.me/", subtitleCallback, callback)
                } 
                else if (serverName.contains("streamtape") || serverName.contains("tape")) {
                    val tokenExchangeUrl = "https://primesrc.me/api/v1/l?key=$serverKey"
                    val directJson = app.get(
                        tokenExchangeUrl,
                        headers = mapOf("Referer" to "https://primesrc.me/embed/movie?tmdb=$mediaId")
                    ).parsedNull<PrimeSrcDirectLink>()
                    
                    directJson?.link?.let { fallbackLink ->
                        loadExtractor(fallbackLink, "https://primesrc.me/", subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }
}
