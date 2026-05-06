package com.FreeReels

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FreeReels : MainAPI() {
    override var mainUrl = "https://m.mydramawave.com"
    private val h5ApiUrl = "https://api.mydramawave.com/h5-api"
    
    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private val aesKeyWeb = "2r36789f45q01ae5".toByteArray()
    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    
    // ==========================================
    // SANDI RAHASIA PENJEBOL VIP
    // (Kalau 666666 gagal, nanti kita ganti jadi 888888)
    // ==========================================
    private val internalCode = "666666" 

    private val secureRandom = SecureRandom()
    private val deviceId = (1..32).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
    
    private var sessionToken: String? = null
    private var sessionSecret: String? = null

    // Kategori disesuaikan dengan aplikasi aslinya
    override val mainPage = mainPageOf(
        "28" to "Populer",
        "29" to "New",
        "30" to "Segera Hadir",
        "31" to "Dubbing",
        "32" to "Perempuan",
        "33" to "Laki-Laki"
    )

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun encryptData(data: String): String {
        val iv = ByteArray(16).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyWeb, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(data.toByteArray())
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptData(data: String): String {
        val clean = data.trim().replace("\"", "")
        if (clean.startsWith("{") || clean.startsWith("[")) return clean
        return try {
            val decoded = Base64.decode(clean, Base64.DEFAULT)
            val iv = decoded.copyOfRange(0, 16)
            val payload = decoded.copyOfRange(16, decoded.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyWeb, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(payload))
        } catch (e: Exception) { clean }
    }

    private fun getWebHeaders(): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        val signature = md5(authSalt + (sessionSecret ?: ""))
        
        return mutableMapOf(
            "app-name" to "com.dramawave.h5",
            "app-version" to "1.2.20",
            "authorization" to "oauth_signature=$signature,oauth_token=${sessionToken ?: "undefined"},ts=$ts",
            "content-type" to "application/json",
            "country" to "ID",
            "device" to "h5",
            "device-id" to deviceId,
            "language" to "id-ID",
            "origin" to "https://m.mydramawave.com",
            "referer" to "https://m.mydramawave.com/",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            
            // SUNTIKAN VIP RAHASIA
            "internal-user-code" to internalCode 
        )
    }

    private suspend fun ensureSession() {
        if (sessionToken != null) return
        val reqBody = encryptData(mapOf("device_id" to deviceId).toJson()).toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("$h5ApiUrl/anonymous/login", headers = getWebHeaders(), requestBody = reqBody).text
        val authData = tryParseJson<NativeAuthResponse>(decryptData(res))
        sessionToken = authData?.data?.authKey ?: authData?.data?.token
        sessionSecret = authData?.data?.authSecret ?: ""
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()
        val res = app.post("$h5ApiUrl/homepage/v2/tab/feed", headers = getWebHeaders(), 
            requestBody = encryptData(mapOf("module_key" to request.data, "next" to (if (page == 1) "" else page.toString())).toJson()).toRequestBody("application/json".toMediaTypeOrNull())).text
        
        val data = tryParseJson<FeedResponse>(decryptData(res))
        val items = data?.data?.items?.mapNotNull { item -> 
            val title = item.title ?: item.name ?: return@mapNotNull null
            // Deteksi label Dubbing
            val isDubbed = title.contains("Dubbed", true) || title.contains("Dubbing", true) || title.contains("Sulih Suara", true)
            
            newTvSeriesSearchResponse(title, item.key ?: return@mapNotNull null) { 
                this.posterUrl = item.cover 
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, items, hasNext = data?.data?.pageInfo?.hasMore ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureSession()
        val res = app.post("$h5ApiUrl/drama/search", headers = getWebHeaders(), 
            requestBody = encryptData(mapOf("keyword" to query).toJson()).toRequestBody("application/json".toMediaTypeOrNull())).text
        val data = tryParseJson<NativeCategoryResponse>(decryptData(res))
        
        return data?.data?.items?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val isDubbed = title.contains("Dubbed", true) || title.contains("Dubbing", true) || title.contains("Sulih Suara", true)
            
            newTvSeriesSearchResponse(title, item.key ?: return@mapNotNull null) { 
                this.posterUrl = item.cover 
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        ensureSession()
        val seriesId = url.split("/").last()
        val res = app.get("$h5ApiUrl/drama/info?series_id=$seriesId", headers = getWebHeaders()).text
        val info = tryParseJson<NativeDetailResponse>(decryptData(res))?.data?.info ?: throw ErrorLoadingException("Film tidak ditemukan")

        val episodeList = info.episodeList?.map { ep -> 
            newEpisode(ep) {
                this.name = ep.name ?: "Episode ${ep.index}"
                this.episode = ep.index
                this.data = ep.toJson() 
            } 
        } ?: emptyList()

        return newTvSeriesLoadResponse(info.name ?: "Drama", url, TvType.AsianDrama, episodeList) {
            this.posterUrl = info.cover
            this.plot = info.desc
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val ep = tryParseJson<DramaEpisode>(data) ?: return false
        
        // Ambil link rahasia H5
        val videoUrl = ep.externalAudioH264 ?: ep.externalAudioH265 ?: ep.m3u8Url ?: ep.videoUrl
        
        if (!videoUrl.isNullOrBlank()) {
            callback.invoke(newExtractorLink(name, name, videoUrl, true) {
                this.headers = mapOf("Origin" to "https://m.mydramawave.com", "Referer" to "https://m.mydramawave.com/")
            })
        }

        ep.subtitleList?.forEach { sub ->
            val subUrl = sub.vtt ?: sub.subtitle
            if (!subUrl.isNullOrBlank()) subtitleCallback.invoke(newSubtitleFile(sub.language ?: "id", subUrl))
        }
        return true
    }
}

// Model data H5
data class NativeAuthResponse(val data: AuthData?)
data class AuthData(val auth_key: String?, val auth_secret: String?, val token: String?)
data class NativeCategoryResponse(val data: CategoryPage?)
data class CategoryPage(val items: List<HomeItem>?)
data class FeedResponse(val data: FeedData?)
data class FeedData(val items: List<HomeItem>?, val page_info: PageInfo?)
data class PageInfo(val has_more: Boolean?)
data class HomeItem(val key: String?, val title: String?, val name: String?, val cover: String?)
data class NativeDetailResponse(val data: DramaInfoData?)
data class DramaInfoData(val info: DramaInfo?)
data class DramaInfo(val name: String?, val cover: String?, val desc: String?, val episode_list: List<DramaEpisode>?)
data class DramaEpisode(
    val index: Int?, val name: String?,
    @JsonProperty("external_audio_h264_m3u8") val externalAudioH264: String?,
    @JsonProperty("external_audio_h265_m3u8") val externalAudioH265: String?,
    val m3u8_url: String?, val video_url: String?,
    val subtitle_list: List<DramaSubtitle>?
)
data class DramaSubtitle(val language: String?, val subtitle: String?, val vtt: String?)
