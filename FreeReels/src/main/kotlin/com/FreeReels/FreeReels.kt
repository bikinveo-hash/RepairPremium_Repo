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

    // Kunci Rahasia hasil bongkaran Smali
    private val aesKey = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv".toByteArray()
    private val aesIv = "https://apiv2.fr".toByteArray()

    // Identitas Device Statis
    private val secureRandom = SecureRandom()
    private val deviceId = (1..32).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
    
    private var sessionToken: String? = null
    private var sessionSecret: String? = null

    // Kategori dari API Tab Web
    override val mainPage = mainPageOf(
        "28" to "Populer",
        "29" to "Terbaru",
        "30" to "Segera Hadir"
    )

    // ==========================================
    // MESIN KRIPTOGRAFI AES & MD5
    // ==========================================
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun encryptData(data: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        return Base64.encodeToString(cipher.doFinal(data.toByteArray()), Base64.NO_WRAP)
    }

    private fun decryptData(data: String): String {
        val clean = data.trim().replace("\"", "")
        if (clean.startsWith("{") || clean.startsWith("[")) return clean
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            String(cipher.doFinal(Base64.decode(clean, Base64.DEFAULT)))
        } catch (e: Exception) {
            clean
        }
    }

    // ==========================================
    // INJEKSI KTP WEB (Sama Persis dgn Kiwi Browser)
    // ==========================================
    private fun getWebHeaders(): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        val secretToHash = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&" + (sessionSecret ?: "")
        val signature = md5(secretToHash)
        val token = sessionToken ?: "undefined"
        
        return mutableMapOf(
            "accept" to "application/json, text/plain, */*",
            "app-name" to "com.dramawave.h5",
            "app-version" to "1.2.20",
            "authorization" to "oauth_signature=$signature,oauth_token=$token,ts=$ts",
            "content-type" to "application/json",
            "cookie" to "k_device_hash=$deviceId",
            "country" to "US",
            "device" to "h5",
            "device-hash" to deviceId,
            "device-id" to deviceId,
            "language" to "en-US",
            "origin" to "https://m.mydramawave.com",
            "referer" to "https://m.mydramawave.com/",
            "shortcode" to "en",
            "timezone" to "America/New_York",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )
    }

    // ==========================================
    // FUNGSI REQUEST
    // ==========================================
    private suspend fun ensureSession() {
        if (sessionToken != null) return
        try {
            val reqBody = encryptData(mapOf("device_id" to deviceId).toJson())
                .toRequestBody("application/json".toMediaTypeOrNull())
            
            val res = app.post("$h5ApiUrl/anonymous/login", headers = getWebHeaders(), requestBody = reqBody).text
            val authData = tryParseJson<NativeAuthResponse>(decryptData(res))
            
            val token = authData?.data?.authKey ?: authData?.data?.token
            if (token != null) {
                sessionToken = token
                sessionSecret = authData?.data?.authSecret ?: ""
                return
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Gagal login: ${e.message}")
        }
    }

    private suspend fun executeGet(path: String): String {
        ensureSession()
        val res = app.get("$h5ApiUrl/$path", headers = getWebHeaders()).text
        return decryptData(res)
    }

    private suspend fun executePost(path: String, body: Any): String {
        ensureSession()
        val reqBody = encryptData(body.toJson()).toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("$h5ApiUrl/$path", headers = getWebHeaders(), requestBody = reqBody).text
        return decryptData(res)
    }

    // ==========================================
    // FITUR UTAMA CLOUDSTREAM
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val nextCursor = if (page == 1) "" else page.toString()
        val res = executePost("homepage/v2/tab/feed", mapOf("module_key" to request.data, "next" to nextCursor))
        
        val data = tryParseJson<FeedResponse>(res) 
            ?: throw ErrorLoadingException("Gagal membaca struktur halaman utama.")
        
        val items = data.data?.items?.mapNotNull { item -> 
            val title = item.title ?: item.name
            if (title.isNullOrBlank() || item.key.isNullOrBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, item.key) { 
                this.posterUrl = item.cover 
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, items, hasNext = data.data?.pageInfo?.hasMore ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = executePost("drama/search", mapOf("keyword" to query))
        val data = tryParseJson<NativeCategoryResponse>(res)
        
        return data?.data?.items?.mapNotNull { item ->
            val title = item.title ?: item.name
            if (title.isNullOrBlank() || item.key.isNullOrBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, item.key) { 
                this.posterUrl = item.cover 
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val res = executeGet("drama/info?series_id=$url")
        val parsedData = tryParseJson<NativeDetailResponse>(res) 
            ?: throw ErrorLoadingException("Gagal membaca struktur detail JSON.")
        
        val info = parsedData.data?.info ?: throw ErrorLoadingException("Data film tidak ada di server.")

        val episodeList = info.episodeList?.map { ep -> 
            newEpisode(ep) {
                this.name = ep.name ?: "Episode ${ep.index}"
                this.episode = ep.index
                this.posterUrl = ep.cover ?: info.cover
            } 
        } ?: emptyList()

        return newTvSeriesLoadResponse(info.name ?: "Tanpa Judul", url, TvType.AsianDrama, episodeList) {
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
        val ep = tryParseJson<DramaEpisode>(data) ?: throw ErrorLoadingException("Data video rusak.")

        val videoLinks = mutableListOf<Pair<String, String>>()
        ep.m3u8Url?.let { videoLinks.add(it to "M3U8") }
        ep.videoUrl?.let { videoLinks.add(it to "MP4") }
        ep.h264M3u8?.let { videoLinks.add(it to "H264 M3U8") }
        ep.h265M3u8?.let { videoLinks.add(it to "H265 M3U8") }

        videoLinks.forEach { (url, typeName) ->
            if (url.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - $typeName",
                        url = url,
                        type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        ep.subtitleList?.forEach { sub ->
            val subUrl = sub.vtt ?: sub.subtitle
            if (!subUrl.isNullOrBlank()) {
                subtitleCallback.invoke(
                    newSubtitleFile(
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
// DATA MODELS
// ==========================================
data class NativeAuthResponse(@JsonProperty("data") val data: AuthData?)
data class AuthData(
    @JsonProperty("auth_key") val authKey: String?, 
    @JsonProperty("auth_secret") val authSecret: String?,
    @JsonProperty("token") val token: String?
)

data class NativeCategoryResponse(@JsonProperty("data") val data: CategoryPage?)
data class CategoryPage(@JsonProperty("items") val items: List<HomeItem>?, @JsonProperty("has_next") val hasNext: Boolean)

data class FeedResponse(@JsonProperty("data") val data: FeedData?)
data class FeedData(
    @JsonProperty("items") val items: List<HomeItem>?, 
    @JsonProperty("page_info") val pageInfo: PageInfo?
)
data class PageInfo(
    @JsonProperty("next") val next: String?, 
    @JsonProperty("has_more") val hasMore: Boolean?
)

data class HomeItem(
    @JsonProperty("key") val key: String?, 
    @JsonProperty("title") val title: String?, 
    @JsonProperty("name") val name: String?, 
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
