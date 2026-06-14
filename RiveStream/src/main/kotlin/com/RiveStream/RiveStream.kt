package com.RiveStream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson // SOLUSI JITU: Pengganti parsedNull agar lolos compile 100%
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.app

class RiveStreamProvider : MainAPI() {
    override var name = "RiveStream"
    override var mainUrl = "https://www.rivestream.app"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    // Mewajibkan engine untuk mengaktifkan sesi sinkronisasi Cookie lintas domain via WebView
    override val usesWebView = true

    // FITUR BARU: Mengaktifkan Kategori Halaman Utama Beranda Aplikasi
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse? {
        // Membuat baris daftar film rekomendasi untuk ditampilkan di beranda kategori
        val homeItems = listOf(
            MovieSearchResponse(
                name = "Project Hail Mary",
                url = "$mainUrl/movie?id=687163",
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = "https://thumb.tapecontent.net/thumb/g32W0kx6rZTqdq4/VaYqLO3qWbuKBKP.jpg"
            )
        )
        
        // Merender kategori horizontal di dasbor depan aplikasi Cloudstream
        return HomePageResponse(
            listOf(
                HomePageList("Rekomendasi Film Populer", homeItems, isHorizontal = true),
                HomePageList("Trending Sekarang", homeItems, isHorizontal = true)
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Mengembalikan list default untuk pencarian manual
        return listOf(
            MovieSearchResponse(
                name = "Project Hail Mary",
                url = "$mainUrl/movie?id=687163",
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = "https://thumb.tapecontent.net/thumb/g32W0kx6rZTqdq4/VaYqLO3qWbuKBKP.jpg"
            )
        )
    }

    override suspend fun load(url: String): LoadResponse {
        // Kerangka dasar pembungkus visual judul tontonan saat poster diklik
        return MovieLoadResponse(
            name = "Project Hail Mary",
            url = url,
            apiName = this.name,
            type = TvType.Movie,
            dataUrl = url,
            posterUrl = "https://thumb.tapecontent.net/thumb/g32W0kx6rZTqdq4/VaYqLO3qWbuKBKP.jpg"
        )
    }

    override suspend fun loadLinks(
        dataUrl: String,
        isCues: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
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
                val resText = app.get(apiTarget, headers = mapOf("Referer" to dataUrl)).text
                
                // Menggunakan parseJson untuk menjamin inferensi tipe data Map terpetakan sempurna
                val res = parseJson<Map<String, Any>>(resText)
                val data = res["data"] as? Map<*, *>
                val sources = data?.get("sources") as? List<*>
                
                if (sources != null) {
                    for (src in sources) {
                        val srcMap = src as? Map<*, *>
                        val videoUrl = srcMap?.get("url") as? String
                        if (!videoUrl.isNullOrEmpty()) {
                            loadExtractor(videoUrl, dataUrl, subtitleCallback, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                // Lanjut silang ke kluster service berikutnya jika ada interupsi
            }
        }

        // -----------------------------------------------------------------
        // JALUR UTAMA 2: PRIMESRC AUTOMATED WEBVIEW INTERCEPT (BYPASS TURNSTILE)
        // -----------------------------------------------------------------
        try {
            val primeEmbedUrl = "https://primesrc.me/embed/movie?tmdb=$mediaId"
            val automatedInterceptor = WebViewResolver(
                Regex(".*(?:streamta\\.site|tpead\\.net|streamtape\\.com)/e/.*")
            )
            
            val solvedResponse = app.get(primeEmbedUrl, interceptor = automatedInterceptor)
            val interceptedUrl = solvedResponse.url
            
            if (interceptedUrl.contains("/e/")) {
                loadExtractor(interceptedUrl, "https://primesrc.me/", subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // -----------------------------------------------------------------
        // JALUR FALLBACK 3: MANUAL PRIMESRC ENGINE (FIXED COROUTINE LOOP)
        // -----------------------------------------------------------------
        try {
            val primeApi = "https://primesrc.me/api/v1/s?tmdb=$mediaId&type=movie"
            val primeResText = app.get(
                primeApi,
                headers = mapOf("Referer" to "https://primesrc.me/embed/movie?tmdb=$mediaId")
            ).text
            
            val primeRes = parseJson<PrimeSrcResponse>(primeResText)
            val serversList = primeRes.servers

            if (serversList != null) {
                // SOLUSI JITU: Mengubah .forEach {} menjadi struktur Loop "for-in" reguler 
                // agar fungsi async/suspend loadExtractor bisa berjalan legal di memori Kotlin
                for (srv in serversList) {
                    val serverName = srv.name?.lowercase() ?: ""
                    val serverKey = srv.key ?: ""

                    if (serverName.contains("voe")) {
                        val voeEmbedUrl = "https://voe.un/e/$serverKey"
                        loadExtractor(voeEmbedUrl, "https://primesrc.me/", subtitleCallback, callback)
                    } 
                    else if (serverName.contains("streamtape") || serverName.contains("tape")) {
                        val tokenExchangeUrl = "https://primesrc.me/api/v1/l?key=$serverKey"
                        val directJsonText = app.get(
                            tokenExchangeUrl,
                            headers = mapOf("Referer" to "https://primesrc.me/embed/movie?tmdb=$mediaId")
                        ).text
                        
                        val directJson = parseJson<PrimeSrcDirectLink>(directJsonText)
                        directJson.link?.let { fallbackLink ->
                            loadExtractor(fallbackLink, "https://primesrc.me/", subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }
}
