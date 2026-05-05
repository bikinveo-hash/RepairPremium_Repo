package com.FreeReels // Perbaikan: Disesuaikan dengan nama folder agar terbaca oleh Plugin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Perbaikan: Import semua utilitas termasuk ExtractorLink dan newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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
        val payload = encrypt(mapOf("device_id" to deviceId, "brand" to "Redmi", "model" to "23090RA98G").toJson())
        
        val response = app.post(
            "$nativeApiUrl/auth/anonymous-login",
            data = mapOf("payload" to payload)
        ).text
        
        val authData = tryParseJson<NativeAuthResponse>(response)
        nativeSessionToken = authData?.data?.token
    }

    private suspend fun executeNativeRequest(endpoint: String, params: Map<String, Any>): String {
        ensureNativeSession()
        
        val jsonPayload = encrypt(params.toJson()) 
        
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
        val data = tryParseJson<NativeCategoryResponse>(res)
        
        val items = data?.data?.items?.map { item -> 
            newTvSeriesSearchResponse(item.title ?: "", item.id.toString()) { 
                this.posterUrl = item.cover 
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, items, hasNext = data?.data?.hasNext ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = executeNativeRequest("drama/search", mapOf("keyword" to query))
        val data = tryParseJson<NativeCategoryResponse>(res)
        
        return data?.data?.items?.map { item ->
            newTvSeriesSearchResponse(item.title ?: "", item.id.toString()) { 
                this.posterUrl = item.cover 
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val res = executeNativeRequest("drama/detail", mapOf("id" to url))
        val parsedData = tryParseJson<NativeDetailResponse>(res) ?: throw ErrorLoadingException("Gagal memuat data")
        val data = parsedData.data ?: throw ErrorLoadingException("Data detail kosong")

        val episodeList = data.episodes?.map { ep -> 
            newEpisode(ep.id.toString()) {
                this.name = "Eps ${ep.episodeNumber}"
                this.episode = ep.episodeNumber
            } 
        } ?: emptyList()

        return newTvSeriesLoadResponse(data.title ?: "", url, TvType.AsianDrama, episodeList) {
            this.posterUrl = data.cover
            this.plot = data.description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = executeNativeRequest("episode/links", mapOf("id" to data))
        val videoData = tryParseJson<NativeVideoResponse>(res)?.data

        videoData?.links?.forEach { link ->
            val videoUrl = link.url ?: return@forEach
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Authorization" to "Bearer $nativeSessionToken")
                }
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
