package com.lagradost.cloudstream3.movieproviders

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class ReelShortProvider : MainAPI() {
    override var mainUrl = "https://www.reelshort.com"
    override var name = "ReelShort"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ==========================================
    // 🕵️‍♂️ HARTA KARUN REVERSE ENGINEERING KITA
    // ==========================================
    private val AES_KEY = "jlcVUHH9XgmYlfsK"
    private val SIGN_SALT_NORMAL = "6a508f8a81314c65"

    /**
     * Fungsi pembuat SIGN (SHA-256)
     * Menggabungkan parameter request dengan Salt rahasia dari libstupid.so
     */
    private fun generateSign(params: String): String {
        // Gabungkan parameter dengan salt. (Biasanya ReelShort nambahin salt di akhir)
        val input = params + SIGN_SALT_NORMAL
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Fungsi pembuka gembok video (AES Decrypt)
     * Menggunakan kunci yang kita dapat dari MT Manager
     */
    private fun decryptContent(encryptedBase64: String): String {
        return try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(), "AES")
            // Umumnya ReelShort pakai ECB. Kalau error, ganti ke "AES/CBC/PKCS5Padding" dan tambahin IV.
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding") 
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            
            val decodedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // ==========================================
    // 🚀 IMPLEMENTASI CLOUDSTREAM
    // ==========================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Contoh cara pakai Sign di URL/Header
        val timestamp = System.currentTimeMillis().toString()
        val rawParams = "ts=$timestamp&lang=id" // Ganti dengan parameter asli mereka
        val sign = generateSign(rawParams)
        
        // Contoh request (Ganti dengan endpoint API asli)
        // val url = "$mainUrl/api/home/list?$rawParams&sign=$sign"
        // val response = app.get(url).text
        
        val items = ArrayList<HomePageList>()
        // TODO: Parse response JSON dan masukkan ke daftar items
        
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchList = ArrayList<SearchResponse>()
        // TODO: Implementasi endpoint pencarian
        return searchList
    }

    override suspend fun load(url: String): LoadResponse? {
        // TODO: Ambil detail judul, poster, dan daftar episode
        
        // Contoh balikan statis:
        return TvSeriesLoadResponse(
            name = "Judul Drama",
            url = url,
            apiName = this.name,
            type = TvType.TvSeries,
            posterUrl = "https://...",
            episodes = listOf(
                // Episode list di-generate di sini
            )
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Ambil data episode (biasanya terenkripsi dari server)
        // val encryptedResponse = app.get(data).text
        
        // 2. Dekripsi data menggunakan kunci AES kita!
        // val decryptedJson = decryptContent(encryptedResponse)
        
        // 3. Ekstrak link m3u8 / mp4 dari JSON yang sudah terbuka
        // val videoUrl = ...
        
        /*
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                videoUrl,
                referer = mainUrl,
                quality = Qualities.P720.value,
                isM3u8 = true // Ubah jadi false kalau mp4
            )
        )
        */
        return true
    }
}
