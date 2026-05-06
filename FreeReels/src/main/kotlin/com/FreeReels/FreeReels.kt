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
    private val nativeApiUrl = "https://apiv2.free-reels.com/frv2-api" // DOMAIN RAHASIA NATIVE
    
    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val aesKeyWeb = "2r36789f45q01ae5".toByteArray()
    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"

    private val secureRandom = SecureRandom()
    private val deviceId = (1..32).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
    
    private var sessionToken: String? = null
    private var sessionSecret: String? = null

    override val mainPage = mainPageOf(
        "28" to "Populer",
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

    // Mengganti identitas secara dinamis (H5 vs Native Android)
    private fun getWebHeaders(isNative: Boolean = false): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        val signature = md5(authSalt + (sessionSecret ?: ""))
        
        val headers = mutableMapOf(
            "app-version" to "1.2.20",
            "authorization" to "oauth_signature=$signature,oauth_token=${sessionToken ?: "undefined"},ts=$ts",
            "content-type" to "application/json",
            "device-id" to deviceId,
            "internal-user-code" to "666666" // Jimat VIP!
        )
        
        if (isNative) {
            headers["app-name"] = "com.freereels.app"
            headers["device"] = "android"
            headers["user-agent"] = "okhttp/4.9.2"
        } else {
            headers["app-name"] = "com.dramawave.h5"
            headers["device"] = "h5"
            headers["device-hash"] = deviceId
            headers["language"] = "id-ID"
            headers["origin"] = "https://m.mydramawave.com"
            headers["referer"] = "https://m.mydramawave.com/"
            headers["user-agent"] = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/137.0.0.0 Mobile Safari/537.36"
        }
        return headers
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
            if (title.equals("Peringkat", ignoreCase = true)) return@mapNotNull null
            val isDubbed = title.contains("Dubbed", true) || title.contains("Dubbing", true) || title.contains("Sulih Suara", true)
            
            newAnimeSearchResponse(title, item.key ?: return@mapNotNull null, TvType.AsianDrama) { 
                this.posterUrl = item.cover 
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, items, hasNext = data?.data?.pageInfo?.hasMore ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureSession()
        val res = app.post("$h5ApiUrl/search/drama", headers = getWebHeaders(), 
            requestBody = encryptData(mapOf("keyword" to query).toJson()).toRequestBody("application/json".toMediaTypeOrNull())).text
        val data = tryParseJson<SearchDataResponse>(decryptData(res))
        
        val searchItems = data?.data?.items ?: data?.data?.list ?: emptyList()
        
        return searchItems.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val isDubbed = title.contains("Dubbed", true) || title.contains("Dubbing", true) || title.contains("Sulih Suara", true)
            
            newAnimeSearchResponse(title, item.seriesId ?: item.key ?: return@mapNotNull null, TvType.AsianDrama) { 
                this.posterUrl = item.cover 
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureSession()
        val seriesId = url.split("/").last()
        val res = app.get("$h5ApiUrl/drama/info?series_id=$seriesId", headers = getWebHeaders()).text
        val info = tryParseJson<NativeDetailResponse>(decryptData(res))?.data?.info ?: throw ErrorLoadingException("Film tidak ditemukan")

        val episodeList = info.episodeList?.map { ep -> 
            // Kita simpan seluruh data JSON episode ini agar bisa diambil Playload-nya nanti
            val epData = ep.toJson()
            newEpisode(epData) {
                this.name = ep.name ?: "Episode ${ep.index}"
                this.episode = ep.index
            } 
        } ?: emptyList()

        return newTvSeriesLoadResponse(info.name ?: "Drama", url, TvType.AsianDrama, episodeList) {
            this.posterUrl = info.cover
            this.plot = info.desc
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        ensureSession()
        val ep = tryParseJson<DramaEpisode>(data) ?: return false
        
        var videoUrl = ep.externalAudioH264 ?: ep.externalAudioH265 ?: ep.m3u8Url ?: ep.videoUrl
        
        // KOMBINASI MAUT: Jika link kosong dari Web (VIP), Curi dari API Native Android!
        if (videoUrl.isNullOrBlank() && !ep.playload.isNullOrBlank()) {
            val playloadData = tryParseJson<Playload>(ep.playload)
            if (playloadData?.seriesId != null) {
                val nativeUrl = "$nativeApiUrl/drama/info_v2?series_id=${playloadData.seriesId}"
                // API Native merespon JSON Mentah, jadi kita langsung parse (tanpa decryptData)
                val nativeRes = app.get(nativeUrl, headers = getWebHeaders(isNative = true)).text
                val nativeInfo = tryParseJson<NativeInfoV2Response>(nativeRes)
                
                // Cocokkan index episode dan ekstrak link VIP-nya
                val nativeEp = nativeInfo?.data?.info?.episodeList?.find { it.episodeId == playloadData.episodeId || it.index == ep.index }
                videoUrl = nativeEp?.externalAudioH264 ?: nativeEp?.externalAudioH265 ?: nativeEp?.m3u8Url ?: nativeEp?.videoUrl
            }
        }

        if (!videoUrl.isNullOrBlank()) {
            val isM3u8 = videoUrl.contains(".m3u8")
            callback.invoke(newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf("Origin" to "https://m.mydramawave.com", "Referer" to "https://m.mydramawave.com/")
            })
        }

        val subs = ep.subtitleList ?: emptyList()
        for (sub in subs) {
            val subUrl = sub.vtt ?: sub.subtitle
            if (!subUrl.isNullOrBlank()) {
                val fixSubUrl = if (subUrl.startsWith("http")) subUrl else "https://static-v1.mydramawave.com$subUrl"
                subtitleCallback.invoke(
                    newSubtitleFile(sub.language ?: "id", fixSubUrl)
                )
            }
        }
        
        return true
    }
}

// ==========================================
// DATA MODELS
// ==========================================
data class Playload(@JsonProperty("series_id") val seriesId: Long?, @JsonProperty("episode_id") val episodeId: Long?)
data class NativeAuthResponse(@JsonProperty("data") val data: AuthData?)
data class AuthData(@JsonProperty("auth_key") val authKey: String?, @JsonProperty("auth_secret") val authSecret: String?, @JsonProperty("token") val token: String?)
data class SearchDataResponse(@JsonProperty("data") val data: SearchResultList?)
data class SearchResultList(@JsonProperty("list") val list: List<HomeItem>?, @JsonProperty("items") val items: List<HomeItem>?)
data class FeedResponse(@JsonProperty("data") val data: FeedData?)
data class FeedData(@JsonProperty("items") val items: List<HomeItem>?, @JsonProperty("page_info") val pageInfo: PageInfo?)
data class PageInfo(@JsonProperty("has_more") val hasMore: Boolean?)
data class HomeItem(@JsonProperty("key") val key: String?, @JsonProperty("series_id") val seriesId: String?, @JsonProperty("title") val title: String?, @JsonProperty("name") val name: String?, @JsonProperty("cover") val cover: String?)

// H5 Detail Response Models
data class NativeDetailResponse(@JsonProperty("data") val data: DramaInfoData?)
data class DramaInfoData(@JsonProperty("info") val info: DramaInfo?)
data class DramaInfo(@JsonProperty("name") val name: String?, @JsonProperty("cover") val cover: String?, @JsonProperty("desc") val desc: String?, @JsonProperty("episode_list") val episodeList: List<DramaEpisode>?)

// Native V2 Detail Response Models (Tanpa Enkripsi)
data class NativeInfoV2Response(@JsonProperty("data") val data: NativeInfoV2Data?)
data class NativeInfoV2Data(@JsonProperty("info") val info: NativeInfoV2?)
data class NativeInfoV2(@JsonProperty("episode_list") val episodeList: List<NativeEpisode>?)
data class NativeEpisode(
    @JsonProperty("episode_id") val episodeId: Long?,
    @JsonProperty("index") val index: Int?,
    @JsonProperty("external_audio_h264_m3u8") val externalAudioH264: String?,
    @JsonProperty("external_audio_h265_m3u8") val externalAudioH265: String?,
    @JsonProperty("m3u8_url") val m3u8Url: String?,
    @JsonProperty("video_url") val videoUrl: String?
)

data class DramaEpisode(
    @JsonProperty("index") val index: Int?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("playload") val playload: String?, // Kunci rahasia berisi Integer ID
    @JsonProperty("external_audio_h264_m3u8") val externalAudioH264: String?,
    @JsonProperty("external_audio_h265_m3u8") val externalAudioH265: String?,
    @JsonProperty("m3u8_url") val m3u8Url: String?,
    @JsonProperty("video_url") val videoUrl: String?,
    @JsonProperty("subtitle_list") val subtitleList: List<DramaSubtitle>?
)

data class DramaSubtitle(
    @JsonProperty("language") val language: String?,
    @JsonProperty("subtitle") val subtitle: String?,
    @JsonProperty("vtt") val vtt: String?,
    @JsonProperty("display_name") val displayName: String?
)
