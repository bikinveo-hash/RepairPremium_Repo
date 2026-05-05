package com.FreeReels

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
import java.security.SecureRandom
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

    // Rahasia dari aplikasi aslinya
    private val cryptoKey = "2r36789f45q01ae5"
    private val nativeLoginSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv"
    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    private val secureRandom = SecureRandom()
    private var nativeSessionToken: String? = null

    // Kategori Lengkap Sesuai Kode Java Asli
    override val mainPage = mainPageOf(
        "popular" to "Populer",
        "new" to "Terbaru",
        "coming_soon" to "Segera Hadir",
        "dubbing" to "Dubbing",
        "female" to "Perempuan",
        "male" to "Laki-Laki",
        "anime" to "Anime"
    )

    // ==========================================
    // MESIN KRIPTOGRAFI
    // ==========================================
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun encrypt(text: String): String {
        val iv = ByteArray(16).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cryptoKey.toByteArray(), "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encryptedText: String): String {
        return try {
            val decoded = Base64.decode(encryptedText, Base64.DEFAULT)
            if (decoded.size < 16) return encryptedText 
            
            val iv = decoded.copyOfRange(0, 16)
            val payload = decoded.copyOfRange(16, decoded.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cryptoKey.toByteArray(), "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(payload))
        } catch (e: Exception) {
            encryptedText
        }
    }

    // ==========================================
    // SISTEM LOGIN & REQUEST API
    // ==========================================
    private suspend fun ensureNativeSession() {
        if (nativeSessionToken != null) return

        val deviceId = (1..16).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
        val sign = md5(deviceId + nativeLoginSalt)
        
        val payload = mapOf(
            "device_id" to deviceId, 
            "device_name" to "Redmi", 
            "sign" to sign
        ).toJson()
        
        val response = app.post(
            "$nativeApiUrl/auth/anonymous-login",
            data = mapOf("payload" to encrypt(payload))
        ).text
        
        val authData = tryParseJson<NativeAuthResponse>(response)
        nativeSessionToken = authData?.data?.authKey ?: authData?.data?.token
    }

    private suspend fun executeNativeRequest(endpoint: String, params: Map<String, Any>): String {
        ensureNativeSession()
        val jsonPayload = encrypt(params.toJson()) 
        val headers = mapOf(
            "Authorization" to "Bearer $nativeSessionToken",
            "X-Auth-Salt" to authSalt
        )

        val res = app.post("$nativeApiUrl/$endpoint", headers = headers, data = mapOf("payload" to jsonPayload)).text
        return if (res.startsWith("{") || res.startsWith("[")) res else decrypt(res)
    }

    // ==========================================
    // FITUR UTAMA CLOUDSTREAM
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = executeNativeRequest("drama/list", mapOf("type" to request.data, "page" to page))
        val data = tryParseJson<NativeCategoryResponse>(res)
        
        val items = data?.data?.items?.mapNotNull { item -> 
            if (item.title.isNullOrBlank() || item.key.isNullOrBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(item.title, item.key) { 
                this.posterUrl = item.cover 
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, items, hasNext = data?.data?.hasNext ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = executeNativeRequest("drama/search", mapOf("keyword" to query))
        val data = tryParseJson<NativeCategoryResponse>(res)
        
        return data?.data?.items?.mapNotNull { item ->
            if (item.title.isNullOrBlank() || item.key.isNullOrBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(item.title, item.key) { 
                this.posterUrl = item.cover 
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val res = executeNativeRequest("drama/info_v2", mapOf("series_id" to url))
        val parsedData = tryParseJson<NativeDetailResponse>(res) ?: throw ErrorLoadingException("Gagal memuat data")
        val info = parsedData.data?.info ?: throw ErrorLoadingException("Data detail kosong")

        val episodeList = info.episodeList?.map { ep -> 
            newEpisode(ep.toJson()) {
                this.name = ep.name ?: "Eps ${ep.index}"
                this.episode = ep.index
                this.posterUrl = ep.cover ?: info.cover
            } 
        } ?: emptyList()

        return newTvSeriesLoadResponse(info.name ?: "Unknown Title", url, TvType.AsianDrama, episodeList) {
            this.posterUrl = info.cover
            this.plot = info.desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = tryParseJson<DramaEpisode>(data) ?: return false

        // PERBAIKAN: Memastikan URL tidak null sebelum dimasukkan ke daftar
        val videoLinks = mutableListOf<Pair<String, String>>()
        ep.m3u8Url?.let { videoLinks.add(it to "M3U8") }
        ep.videoUrl?.let { videoLinks.add(it to "MP4") }
        ep.h264M3u8?.let { videoLinks.add(it to "H264 M3U8") }
        ep.h265M3u8?.let { videoLinks.add(it to "H265 M3U8") }

        videoLinks.forEach { (url, typeName) ->
            if (url.isNotBlank()) {
                // PERBAIKAN: Penulisan ExtractorLink yang sesuai dengan standar baru
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - $typeName",
                        url = url,
                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Authorization" to "Bearer $nativeSessionToken")
                    }
                )
            }
        }

        ep.subtitleList?.forEach { sub ->
            val subUrl = sub.vtt ?: sub.subtitle
            if (!subUrl.isNullOrBlank()) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = sub.language ?: sub.displayName ?: "id",
                        url = subUrl
                    )
                )
            }
        }

        return true
    }
}

// ==========================================
// DATA MODELS (UPDATE DARI JAVA ASLI)
// ==========================================
data class NativeAuthResponse(@JsonProperty("data") val data: AuthData?)
data class AuthData(@JsonProperty("auth_key") val authKey: String?, @JsonProperty("token") val token: String?)

data class NativeCategoryResponse(@JsonProperty("data") val data: CategoryPage?)
data class CategoryPage(@JsonProperty("items") val items: List<HomeItem>?, @JsonProperty("has_next") val hasNext: Boolean)
data class HomeItem(
    @JsonProperty("key") val key: String?, 
    @JsonProperty("title") val title: String?, 
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("desc") val desc: String?
)

data class NativeDetailResponse(@JsonProperty("data") val data: DramaInfoData?)
data class DramaInfoData(@JsonProperty("info") val info: DramaInfo?)
data class DramaInfo(
    @JsonProperty("name") val name: String?, 
    @JsonProperty("cover") val cover: String?, 
    @JsonProperty("desc") val desc: String?, 
    @JsonProperty("episode_list") val episodeList: List<DramaEpisode>?
)

data class DramaEpisode(
    @JsonProperty("id") val id: String?, 
    @JsonProperty("index") val index: Int?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("m3u8_url") val m3u8Url: String?,
    @JsonProperty("video_url") val videoUrl: String?,
    @JsonProperty("external_audio_h264_m3u8") val h264M3u8: String?,
    @JsonProperty("external_audio_h265_m3u8") val h265M3u8: String?,
    @JsonProperty("subtitle_list") val subtitleList: List<DramaSubtitle>?
)

data class DramaSubtitle(
    @JsonProperty("language") val language: String?,
    @JsonProperty("subtitle") val subtitle: String?,
    @JsonProperty("vtt") val vtt: String?,
    @JsonProperty("display_name") val displayName: String?
)
