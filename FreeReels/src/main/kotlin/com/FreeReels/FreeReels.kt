package com.FreeReels

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

class FreeReels : MainAPI() {
    override var mainUrl = "https://m.mydramawave.com"
    // DOMAIN & PREFIX RAHASIA APLIKASI ANDROID (Full Bypass)
    private val nativeApiUrl = "https://apiv2.free-reels.com/frv2-api"
    
    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    private val secureRandom = SecureRandom()
    private val deviceId = (1..32).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
    
    private var sessionToken: String? = null
    private var sessionSecret: String? = null

    // Menggunakan ID Tab khusus Native Android (503 = Popular)
    override val mainPage = mainPageOf(
        "503" to "Populer",
        "504" to "Segera Hadir",
        "505" to "Perempuan",
        "506" to "Laki-Laki"
    )

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // Header khusus menipu server seolah-olah kita adalah Aplikasi Android Asli
    private fun getNativeHeaders(): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        val signature = md5(authSalt + (sessionSecret ?: ""))
        
        return mutableMapOf(
            "app-name" to "com.freereels.app",
            "app-version" to "1.2.20",
            "authorization" to "oauth_signature=$signature,oauth_token=${sessionToken ?: "undefined"},ts=$ts",
            "content-type" to "application/json",
            "device" to "android",
            "device-id" to deviceId,
            "user-agent" to "okhttp/4.9.2",
            "internal-user-code" to "666666" // Jimat Penembus VIP
        )
    }

    private suspend fun ensureSession() {
        if (sessionToken != null) return
        // Login menggunakan Native API (Plain JSON, Tanpa Enkripsi!)
        val reqBody = mapOf("device_id" to deviceId).toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("$nativeApiUrl/anonymous/login", headers = getNativeHeaders(), requestBody = reqBody).text
        val authData = tryParseJson<NativeAuthResponse>(res)
        sessionToken = authData?.data?.authKey ?: authData?.data?.token
        sessionSecret = authData?.data?.authSecret ?: ""
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()
        val reqBody = mapOf("module_key" to request.data, "next" to (if (page == 1) "" else page.toString())).toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("$nativeApiUrl/homepage/v2/tab/feed", headers = getNativeHeaders(), requestBody = reqBody).text
        
        val data = tryParseJson<NativeFeedResponse>(res)
        val items = data?.data?.items?.mapNotNull { item -> 
            val title = item.title ?: item.name ?: return@mapNotNull null
            
            // Filter cerdas: Buang poster "Ranking/Peringkat" yang tidak bisa diputar
            if (title.equals("Ranking", ignoreCase = true) || title.equals("Peringkat", ignoreCase = true) || item.key.isNullOrBlank()) {
                return@mapNotNull null
            }
            
            val isDubbed = title.contains("Dubbed", true) || title.contains("Dubbing", true) || title.contains("Sulih Suara", true)
            
            newAnimeSearchResponse(title, item.key, TvType.AsianDrama) { 
                this.posterUrl = item.cover 
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, items, hasNext = data?.data?.pageInfo?.hasMore ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureSession()
        val reqBody = mapOf("keyword" to query).toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("$nativeApiUrl/search/drama", headers = getNativeHeaders(), requestBody = reqBody).text
        val data = tryParseJson<NativeSearchResponse>(res)
        
        val searchItems = data?.data?.items ?: data?.data?.list ?: emptyList()
        
        return searchItems.mapNotNull { item ->
            val title = item.name ?: item.title ?: return@mapNotNull null
            val isDubbed = title.contains("Dubbed", true) || title.contains("Dubbing", true) || title.contains("Sulih Suara", true)
            
            // Native search menggunakan "id", bukan "series_id"
            newAnimeSearchResponse(title, item.id ?: item.key ?: return@mapNotNull null, TvType.AsianDrama) { 
                this.posterUrl = item.cover 
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureSession()
        val seriesId = url.split("/").last()
        
        // Coba rute info_v2 terlebih dahulu
        var res = app.get("$nativeApiUrl/drama/info_v2?series_id=$seriesId", headers = getNativeHeaders()).text
        var info = tryParseJson<NativeDetailResponse>(res)?.data?.info
        
        // Fallback cerdas: Jika info_v2 kosong, pakai rute info biasa (seperti kasus ID 72hx92GGzS)
        if (info == null || info.episodeList.isNullOrEmpty()) {
            res = app.get("$nativeApiUrl/drama/info?series_id=$seriesId", headers = getNativeHeaders()).text
            info = tryParseJson<NativeDetailResponse>(res)?.data?.info ?: throw ErrorLoadingException("Film tidak ditemukan / Geo-blocked")
        }

        val episodeList = info.episodeList?.map { ep -> 
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
        val ep = tryParseJson<NativeEpisode>(data) ?: return false
        
        // Di API Native, link VIP sudah langsung tersedia di sini!
        val videoUrl = ep.externalAudioH264 ?: ep.externalAudioH265 ?: ep.m3u8Url ?: ep.videoUrl
        
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
// DATA MODELS (PURE NATIVE)
// ==========================================
data class NativeAuthResponse(@JsonProperty("data") val data: AuthData?)
data class AuthData(@JsonProperty("auth_key") val authKey: String?, @JsonProperty("auth_secret") val authSecret: String?, @JsonProperty("token") val token: String?)

data class NativeSearchResponse(@JsonProperty("data") val data: SearchResultList?)
data class SearchResultList(@JsonProperty("list") val list: List<NativeItem>?, @JsonProperty("items") val items: List<NativeItem>?)

data class NativeFeedResponse(@JsonProperty("data") val data: FeedData?)
data class FeedData(@JsonProperty("items") val items: List<NativeItem>?, @JsonProperty("page_info") val pageInfo: PageInfo?)
data class PageInfo(@JsonProperty("has_more") val hasMore: Boolean?)

data class NativeItem(
    @JsonProperty("id") val id: String?, 
    @JsonProperty("key") val key: String?, 
    @JsonProperty("series_id") val seriesId: String?, 
    @JsonProperty("title") val title: String?, 
    @JsonProperty("name") val name: String?, 
    @JsonProperty("cover") val cover: String?
)

data class NativeDetailResponse(@JsonProperty("data") val data: DramaInfoData?)
data class DramaInfoData(@JsonProperty("info") val info: DramaInfo?)
data class DramaInfo(
    @JsonProperty("name") val name: String?, 
    @JsonProperty("cover") val cover: String?, 
    @JsonProperty("desc") val desc: String?, 
    @JsonProperty("episode_list") val episodeList: List<NativeEpisode>?
)

data class NativeEpisode(
    @JsonProperty("index") val index: Int?, 
    @JsonProperty("name") val name: String?,
    @JsonProperty("external_audio_h264_m3u8") val externalAudioH264: String?,
    @JsonProperty("external_audio_h265_m3u8") val externalAudioH265: String?,
    @JsonProperty("m3u8_url") val m3u8Url: String?, 
    @JsonProperty("video_url") val videoUrl: String?,
    @JsonProperty("subtitle_list") val subtitleList: List<NativeSubtitle>?
)

data class NativeSubtitle(
    @JsonProperty("language") val language: String?, 
    @JsonProperty("subtitle") val subtitle: String?, 
    @JsonProperty("vtt") val vtt: String?, 
    @JsonProperty("display_name") val displayName: String?
)
