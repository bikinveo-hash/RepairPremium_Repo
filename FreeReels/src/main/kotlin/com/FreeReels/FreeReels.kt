package com.freereels

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FreeReels : MainAPI() {
    override var mainUrl = "https://free-reels.com"
    private val nativeApiUrl = "https://apiv2.free-reels.com/frv2-api"
    
    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    // Konfigurasi Keamanan dari File Java
    private val cryptoKey = "2r36789f45q01ae5"
    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    private val secureRandom = SecureRandom()
    private var nativeSessionToken: String? = null

    override val mainPage = mainPageOf(
        "popular" to "Populer",
        "new" to "Terbaru",
        "anime" to "Anime"
    )

    // ==========================================
    // MESIN KRIPTOGRAFI (AES/CBC)
    // ==========================================
    private fun encrypt(text: String): String {
        val iv = ByteArray(16).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cryptoKey.toByteArray(), "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(text.toByteArray())
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedText: String): String {
        val decoded = Base64.decode(encryptedText, Base64.DEFAULT)
        val iv = decoded.copyOfRange(0, 16)
        val payload = decoded.copyOfRange(16, decoded.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cryptoKey.toByteArray(), "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(payload))
    }

    // ==========================================
    // SISTEM LOGIN SILUMAN (SESSION)
    // ==========================================
    private suspend fun ensureNativeSession() {
        if (nativeSessionToken != null) return

        val deviceId = (1..16).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
        val payload = encrypt("{\"device_id\":\"$deviceId\",\"brand\":\"Redmi\",\"model\":\"23090RA98G\"}")
        
        val response = app.post(
            "$nativeApiUrl/auth/anonymous-login",
            data = mapOf("payload" to payload)
        ).parsed<NativeAuthResponse>()
        
        nativeSessionToken = response.data?.token
    }

    private suspend fun executeNativeRequest(endpoint: String, params: Map<String, Any>): String {
        ensureNativeSession()
        val jsonPayload = encrypt(app.base64Canany(params)) // Versi simpel dari JSON
        
        val headers = mapOf(
            "Authorization" to "Bearer $nativeSessionToken",
            "X-Auth-Salt" to authSalt
        )

        val res = app.post("$nativeApiUrl/$endpoint", headers = headers, data = mapOf("payload" to jsonPayload)).text
        return if (res.startsWith("{")) res else decrypt(res)
    }

    // ==========================================
    // IMPLEMENTASI FITUR UTAMA
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = executeNativeRequest("drama/list", mapOf("type" to request.data, "page" to page))
        val data = parseJson<NativeCategoryResponse>(res)
        
        val items = data.data?.items?.map { 
            newTvSeriesSearchResponse(it.title ?: "", it.id.toString()) { this.posterUrl = it.cover }
        } ?: emptyList()

        return newHomePageResponse(request.name, items, hasNext = data.data?.hasNext ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = executeNativeRequest("drama/search", mapOf("keyword" to query))
        val data = parseJson<NativeCategoryResponse>(res)
        return data.data?.items?.map {
            newTvSeriesSearchResponse(it.title ?: "", it.id.toString()) { this.posterUrl = it.cover }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val res = executeNativeRequest("drama/detail", mapOf("id" to url))
        val data = parseJson<NativeDetailResponse>(res).data!!

        return newTvSeriesLoadResponse(data.title ?: "", url, TvType.AsianDrama, 
            data.episodes?.map { Episode(it.id.toString(), "Eps ${it.episodeNumber}", episode = it.episodeNumber) } ?: emptyList()
        ) {
            this.posterUrl = data.cover
            this.plot = data.description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val res = executeNativeRequest("episode/links", mapOf("id" to data))
        val videoData = parseJson<NativeVideoResponse>(res).data

        videoData?.links?.forEach { link ->
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = link.url ?: "",
                    referer = "$mainUrl/", // Penting!
                    quality = Qualities.Unknown.value,
                    isM3u8 = link.url?.contains(".m3u8") == true,
                    headers = mapOf("Authorization" to "Bearer $nativeSessionToken") // Header wajib
                )
            )
        }
        return true
    }
}

// ==========================================
// DATA MODELS (JSON MAPPING)
// ==========================================
data class NativeAuthResponse(val data: TokenData?)
data class TokenData(val token: String?)
data class NativeCategoryResponse(val data: CategoryPage?)
data class CategoryPage(val items: List<HomeItem>?, val hasNext: Boolean)
data class HomeItem(val id: Int?, val title: String?, val cover: String?)
data class NativeDetailResponse(val data: DetailData?)
data class DetailData(val title: String?, val cover: String?, val description: String?, val episodes: List<NativeEpisode>?)
data class NativeEpisode(val id: Int, val episodeNumber: Int)
data class NativeVideoResponse(val data: VideoData?)
data class VideoData(val links: List<VideoLink>?)
data class VideoLink(val url: String?)
