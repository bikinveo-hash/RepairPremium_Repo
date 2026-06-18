package com.RiveStream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

// ─────────────────────────────────────────────────────────────────────────────
// Helper Global untuk Bypass Cloudflare menggunakan WebViewResolver
// ─────────────────────────────────────────────────────────────────────────────
suspend fun getHtmlWithBypass(url: String, userAgent: String, interceptRegex: String): String {
    try {
        println("[RIVE_DEBUG] Memulai pengambilan HTML dengan Bypass Helper untuk: $url")
        val res = app.get(url, headers = mapOf("User-Agent" to userAgent))
        val body = res.text
        
        // Deteksi apakah request normal dihadang oleh Cloudflare
        val isBlocked = res.code != 200 || 
                        body.contains("cf-challenge") || 
                        body.contains("__cf_chl_opt") || 
                        body.contains("Just a moment...") || 
                        body.contains("id=\"cf-wrapper\"")

        if (!isBlocked) {
            println("[RIVE_DEBUG] Direct GET Sukses! Tanpa proteksi Cloudflare (Code: ${res.code})")
            return body
        }

        println("[RIVE_DEBUG] Proteksi Cloudflare terdeteksi (Code: ${res.code}). Mengaktifkan WebViewResolver...")
        
        // Inisialisasi WebView resolver di background (useOkhttp = false wajib untuk Cloudflare)
        val resolver = WebViewResolver(
            interceptUrl = Regex(interceptRegex),
            useOkhttp = false
        )
        resolver.resolveUsingWebView(url)
        println("[RIVE_DEBUG] WebViewResolver berhasil menyelesaikan tantangan Cloudflare!")

        // Request ulang setelah cookie clearance terpasang di CookieManager
        val retryRes = app.get(url, headers = mapOf("User-Agent" to userAgent))
        println("[RIVE_DEBUG] Request ulang sukses setelah bypass WebView (Code: ${retryRes.code})")
        return retryRes.text
    } catch (e: Exception) {
        println("[RIVE_DEBUG] Kegagalan sistem pada getHtmlWithBypass: ${e.message}")
        e.printStackTrace()
        return ""
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RiveVidara — https://vidara.so
// Flow: GET landing → POST /api/stream {filecode, device} → streaming_url M3U8
// ─────────────────────────────────────────────────────────────────────────────
class RiveVidara : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            println("[RIVE_DEBUG] RiveVidara dijalankan untuk URL: $url")

            // Ambil landing page dengan bypass Cloudflare (Interseptor diarahkan ke endpoint API stream)
            val landingResponse = getHtmlWithBypass(url, userAgent, ".*vidara\\.so/api/stream.*")

            if (landingResponse.contains("/api/stream")) {
                println("[RIVE_DEBUG] Landing page Vidara terverifikasi valid!")
                val fileCode = url.substringAfter("/e/").substringBefore("?")
                val jsonString = mapOf("filecode" to fileCode, "device" to "android").toJson()
                val requestBody = jsonString.toRequestBody("application/json".toMediaTypeOrNull())

                println("[RIVE_DEBUG] Mengirimkan POST request ke /api/stream membawa filecode: $fileCode")
                val apiResponse = app.post(
                    url = "$mainUrl/api/stream",
                    requestBody = requestBody,
                    headers = mapOf("User-Agent" to userAgent, "Referer" to url)
                ).text

                val streamUrl = Regex("""(?i)"streaming_url"\s*:\s*"([^"]+)"""").find(apiResponse)?.groupValues?.get(1)
                println("[RIVE_DEBUG] Hasil ekstraksi streamUrl: $streamUrl")

                if (!streamUrl.isNullOrEmpty()) {
                    println("[RIVE_DEBUG] Mengirimkan link Vidara ke CloudStream Player! ✅")
                    callback(newExtractorLink(
                        source = this.name,
                        name = "$name - 1080p",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    })
                } else {
                    println("[RIVE_DEBUG] Gagal mendapatkan streaming_url dari respon API Vidara.")
                }
            } else {
                println("[RIVE_DEBUG] HTML Landing Page Vidara tidak valid atau diblokir permanen.")
            }
        } catch (e: Exception) { 
            println("[RIVE_DEBUG] Exception terjadi pada RiveVidara: ${e.message}")
            e.printStackTrace() 
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RiveVidsST — https://vids.st
// Flow: GET page → normalize \/ → regex `const url = "..."` → MP4
// ─────────────────────────────────────────────────────────────────────────────
class RiveVidsST : ExtractorApi() {
    override val name = "VidsST"
    override val mainUrl = "https://vids.st"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            println("[RIVE_DEBUG] RiveVidsST dijalankan untuk URL: $url")

            // Ambil html dengan bypass Cloudflare (Interseptor diarahkan ke load aset internal .js)
            val html = getHtmlWithBypass(url, userAgent, ".*vids\\.st/.*\\.js")
            val normalized = html.replace("\\/", "/")
            val mp4Regex = Regex("""const\s+url\s*=\s*["'](https?://[^"']+\.mp4)["']""")
            val streamUrl = mp4Regex.find(normalized)?.groupValues?.get(1)
            println("[RIVE_DEBUG] Hasil ekstraksi streamUrl VidsST: $streamUrl")

            if (!streamUrl.isNullOrEmpty()) {
                println("[RIVE_DEBUG] Mengirimkan link VidsST ke CloudStream Player! ✅")
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - 1080p",
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                })
            }
        } catch (e: Exception) { 
            println("[RIVE_DEBUG] Exception terjadi pada RiveVidsST: ${e.message}")
            e.printStackTrace() 
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RiveSavefiles — https://savefiles.com
// Flow: POST /dl {op, file_code, auto, referer} → regex master.m3u8
// ─────────────────────────────────────────────────────────────────────────────
class RiveSavefiles : ExtractorApi() {
    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            println("[RIVE_DEBUG] RiveSavefiles dijalankan untuk URL: $url")

            // Priming clearance cookie Savefiles terlebih dahulu sebelum menembak POST request
            getHtmlWithBypass(url, userAgent, ".*savefiles\\.com/.*\\.js")

            val fileCode = url.substringAfter("/e/").substringBefore("?")
            val actualReferer = referer ?: "https://primesrc.me/"

            val formBody = okhttp3.FormBody.Builder()
                .add("op", "embed")
                .add("file_code", fileCode)
                .add("auto", "1")
                .add("referer", actualReferer)
                .build()

            println("[RIVE_DEBUG] Mengirimkan POST request ke Savefiles /dl membawa filecode: $fileCode")
            val dlResponse = app.post(
                url = "$mainUrl/dl",
                requestBody = formBody,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            ).text

            val m3u8Regex = Regex("""https?://[^\s"'<>]+master\.m3u8[^\s"'<>]*""")
            val masterM3u8 = m3u8Regex.find(dlResponse)?.value
            println("[RIVE_DEBUG] Hasil ekstraksi masterM3u8 Savefiles: $masterM3u8")

            if (!masterM3u8.isNullOrEmpty()) {
                println("[RIVE_DEBUG] Mengirimkan link Savefiles ke CloudStream Player! ✅")
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - 720p",
                    url = masterM3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P720.value
                })
            }
        } catch (e: Exception) { 
            println("[RIVE_DEBUG] Exception terjadi pada RiveSavefiles: ${e.message}")
            e.printStackTrace() 
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RiveLizer — https://lizer123.site
// Flow: GET /getm3u8/{id} → redirect atau JSON → M3U8
// ─────────────────────────────────────────────────────────────────────────────
class RiveLizer : ExtractorApi() {
    override val name = "Lizer"
    override val mainUrl = "https://lizer123.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
            println("[RIVE_DEBUG] RiveLizer dijalankan untuk URL: $url")

            val responseBody = getHtmlWithBypass(url, userAgent, ".*lizer123\\.site/.*")

            val m3u8Regex = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            val m3u8Url = m3u8Regex.find(responseBody)?.value
            println("[RIVE_DEBUG] Hasil ekstraksi m3u8 RiveLizer: $m3u8Url")

            if (!m3u8Url.isNullOrEmpty()) {
                println("[RIVE_DEBUG] Mengirimkan link Lizer ke CloudStream Player! ✅")
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - Auto",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
                return
            }

            val mp4Regex = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
            val mp4Url = mp4Regex.find(responseBody)?.value
            println("[RIVE_DEBUG] Hasil ekstraksi mp4 RiveLizer: $mp4Url")

            if (!mp4Url.isNullOrEmpty()) {
                println("[RIVE_DEBUG] Mengirimkan link Lizer ke CloudStream Player! ✅")
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - Auto",
                    url = mp4Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) { 
            println("[RIVE_DEBUG] Exception terjadi pada RiveLizer: ${e.message}")
            e.printStackTrace() 
        }
    }
}
