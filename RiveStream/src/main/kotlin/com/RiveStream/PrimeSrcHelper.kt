package com.RiveStream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

        // REGEX SAKTI: Menangkap momen saat situs primesrc memuat iframe server video asli
        val playerRegex = Regex(".*(streamtape|voe|streamwish|filemoon|dood|mixdrop|streamplay|vinovo|vidmoly|vidara|savefiles|filelions|luluvdoo|streamruby|vidsst|mshare|uqload|fembed).*")

        var linksFound = 0

        try {
            // Berikan timeout agak panjang (25 detik) agar WebView punya waktu untuk
            // menyelesaikan tantangan Cloudflare, memuat API, dan meluncurkan iframe server video.
            val webViewResolver = com.lagradost.cloudstream3.network.WebViewResolver(
                interceptUrl = playerRegex,
                userAgent = null, // Biarkan organik sesuai bawaan HP kamu
                useOkhttp = false // Wajib false untuk menghadapi Cloudflare
            )

            // WebView akan memuat halaman secara mandiri di latar belakang
            val (interceptedRequest, _) = webViewResolver.resolveUsingWebView(
                url = embedUrl,
                referer = "$mainUrl/"
            )

            // Ambil URL server video asli hasil tangkapan WebView
            val realEmbedUrl = interceptedRequest?.url?.toString()

            if (!realEmbedUrl.isNullOrBlank()) {
                // Berhasil ditangkap! Langsung lemparkan ke Extractor bawaan Cloudstream (Streamtape/Filemoon/Voe/dll.)
                val isExtractorFound = loadExtractor(
                    url = realEmbedUrl,
                    referer = embedUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )

                if (isExtractorFound) {
                    linksFound++
                } else {
                    // Fallback jika tidak ada extractor bawaan yang cocok, kirim sebagai direct link
                    callback(newExtractorLink(
                        source = providerName,
                        name = "Mirror Video",
                        url = realEmbedUrl
                    ) {
                        this.referer = embedUrl
                    })
                    linksFound++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return linksFound > 0
    }
}
