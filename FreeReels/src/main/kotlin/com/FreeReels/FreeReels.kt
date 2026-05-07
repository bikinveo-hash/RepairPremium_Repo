package com.FreeReels

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

class FreeReels : MainAPI() {
    override var mainUrl = "https://m.mydramawave.com"
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
    private val sessionLock = Mutex()

    // Segera Hadir dihilangkan agar UI jauh lebih bersih
    override val mainPage = mainPageOf(
        "503_10000" to "Populer",
        "505_10001" to "New",
        "516_10003" to "Dubbing",
        "504_10004" to "Perempuan",
        "506_10005" to "Laki-Laki"
    )

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // STRATEGI NINJA: Jimat VIP diumpetin, hanya aktif kalau memutar video!
    private fun getNativeHeaders(isVip: Boolean = false): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        val signature = md5(authSalt + (sessionSecret ?: ""))
        
        val headers = mutableMapOf(
            "app-name" to "com.freereels.app",
            "app-version" to "1.2.20",
            "authorization" to "oauth_signature=$signature,oauth_token=${sessionToken ?: "undefined"},ts=$ts",
            "content-type" to "application/json",
            "device" to "android",
            "device-id" to deviceId,
            "language" to "id-ID",
            "user-agent" to "okhttp/4.9.2"
        )
        
        if (isVip) {
            headers["internal-user-code"] = "666666" 
        }
        
        return headers
    }

    private suspend fun ensureSession() {
        if (sessionToken != null) return
        sessionLock.withLock {
            if (sessionToken != null) return@withLock 
            
            val reqBody = mapOf("device_id" to deviceId).toJson().toRequestBody("application/json".toMediaTypeOrNull())
            val res = app.post("$nativeApiUrl/anonymous/login", headers = getNativeHeaders(), requestBody = reqBody).text
            
            // Untuk Auth, Data Class masih aman
            val authData = tryParseJson<NativeAuthResponse>(res)
            sessionToken = authData?.data?.authKey ?: authData?.data?.token
            sessionSecret = authData?.data?.authSecret ?: ""
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()
        
        // Halaman kategori Native tidak di-scroll agar rapi
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val keys = request.data.split("_")
        val tabKey = keys[0]
        val posIndex = keys.getOrNull(1) ?: "10000"
        
        val url = "$nativeApiUrl/homepage/v2/tab/index?tab_key=$tabKey&position_index=$posIndex&rec_trigger=0"
        val res = app.get(url, headers = getNativeHeaders(isVip = false)).text 
        
        val rawItems = mutableListOf<Map<*, *>>()

        try {
            // METODE PYTHON-STYLE: Parsing sebagai Map kasar, lupakan Data Class untuk Halaman Depan!
            val parsedData = tryParseJson<Map<String, Any>>(res)
            val dataObj = parsedData?.get("data") as? Map<*, *>
            
            val components = (dataObj?.get("components") as? List<*>) ?: (dataObj?.get("modules") as? List<*>)
            if (components != null) {
                // Sedot dari dalam kotak komponen
                components.filterIsInstance<Map<*, *>>().forEach { comp ->
                    (comp["items"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { rawItems.addAll(it) }
                    (comp["list"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { rawItems.addAll(it) }
                }
            } else {
                // Sedot langsung kalau tidak ada kotak
                (dataObj?.get("items") as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { rawItems.addAll(it) }
                (dataObj?.get("list") as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { rawItems.addAll(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val items = rawItems.mapNotNull { item -> 
            val title = (item["title"] as? String) ?: (item["name"] as? String) ?: return@mapNotNull null
            // Ekstrak ID secara paksa jadi String, tidak peduli aslinya Angka atau Teks
            val idStr = item["id"]?.toString() ?: item["key"]?.toString() ?: item["series_id"]?.toString() ?: return@mapNotNull null
            
            // Filter cerdas membuang banner
            if (title.equals("Ranking", ignoreCase = true) || title.equals("Peringkat", ignoreCase = true) || title.equals("Top", ignoreCase = true)) {
                return@mapNotNull null
            }
            
            val epInfo = item["episode_info"] as? Map<*, *>
            val audioList = epInfo?.get("audio") as? List<*>
            val hasIndoAudio = audioList?.any { it?.toString()?.contains("id-ID") == true } == true
            val isDubbed = hasIndoAudio || title.contains("Dubbed", true) || title.contains("Sulih Suara", true)
            
            val cover = (item["cover"] as? String) ?: (item["vertical_cover"] as? String)

            newAnimeSearchResponse(title, idStr, TvType.AsianDrama) { 
                this.posterUrl = fixUrlNull(cover)
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureSession()
        val nextToken = if (page == 1) "" else "offset=${(page - 1) * 20}&page_size=20"
        val reqBody = mapOf("keyword" to query, "next" to nextToken).toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("$nativeApiUrl/search/drama", headers = getNativeHeaders(isVip = false)).text
        
        val rawItems = mutableListOf<Map<*, *>>()
        var hasMore = false
        
        try {
            val parsedData = tryParseJson<Map<String, Any>>(res)
            val dataObj = parsedData?.get("data") as? Map<*, *>
            
            val pageInfo = dataObj?.get("page_info") as? Map<*, *>
            hasMore = pageInfo?.get("has_more") as? Boolean ?: false
            
            (dataObj?.get("items") as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { rawItems.addAll(it) }
            (dataObj?.get("list") as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { rawItems.addAll(it) }
        } catch (e: Exception) {}
        
        val list = rawItems.mapNotNull { item ->
            val title = (item["title"] as? String) ?: (item["name"] as? String) ?: return@mapNotNull null
            val idStr = item["id"]?.toString() ?: item["key"]?.toString() ?: item["series_id"]?.toString() ?: return@mapNotNull null
            
            val epInfo = item["episode_info"] as? Map<*, *>
            val audioList = epInfo?.get("audio") as? List<*>
            val hasIndoAudio = audioList?.any { it?.toString()?.contains("id-ID") == true } == true
            val isDubbed = hasIndoAudio || title.contains("Dubbed", true) || title.contains("Sulih Suara", true)
            
            val cover = (item["cover"] as? String) ?: (item["vertical_cover"] as? String)

            newAnimeSearchResponse(title, idStr, TvType.AsianDrama) { 
                this.posterUrl = fixUrlNull(cover)
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        }
        return newSearchResponseList(list, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        ensureSession()
        
        val seriesId = url.split("/").last()
        
        // MENGELUARKAN JIMAT VIP SAAT LOAD EPISODE AGAR BISA NONTON GRATIS!
        var res = app.get("$nativeApiUrl/drama/info_v2?series_id=$seriesId", headers = getNativeHeaders(isVip = true)).text
        var info = tryParseJson<NativeDetailResponse>(res)?.data?.info
        
        if (info == null || info.episodeList.isNullOrEmpty()) {
            res = app.get("$nativeApiUrl/drama/info?series_id=$seriesId", headers = getNativeHeaders(isVip = true)).text
            info = tryParseJson<NativeDetailResponse>(res)?.data?.info ?: throw ErrorLoadingException("Film tidak ditemukan / Belum rilis")
        }

        val episodeList = info.episodeList?.mapNotNull { ep -> 
            newEpisode(ep.toJson()) {
                this.name = ep.name ?: "Episode ${ep.index}"
                this.episode = ep.index
            } 
        } ?: emptyList()

        return newTvSeriesLoadResponse(info.name ?: "Drama", url, TvType.AsianDrama, episodeList) {
            this.posterUrl = fixUrlNull(info.cover ?: info.verticalCover)
            this.plot = info.desc
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val ep = tryParseJson<NativeEpisode>(data) ?: return false
        
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

        ep.subtitleList?.forEach { sub ->
            val subUrl = sub.vtt ?: sub.subtitle
            if (!subUrl.isNullOrBlank()) {
                subtitleCallback.invoke(
                    newSubtitleFile(sub.language ?: "id", fixUrl(subUrl))
                )
            }
        }
        return true
    }
}

// ==========================================
// DATA MODELS PURE NATIVE 
// (Tinggal sisa Auth & Detail yang JSON-nya stabil, Feed murni pakai Map)
// ==========================================
data class NativeAuthResponse(@JsonProperty("data") val data: AuthData?)
data class AuthData(@JsonProperty("auth_key") val authKey: String?, @JsonProperty("auth_secret") val authSecret: String?, @JsonProperty("token") val token: String?)

data class NativeDetailResponse(@JsonProperty("data") val data: DramaInfoData?)
data class DramaInfoData(@JsonProperty("info") val info: DramaInfo?)
data class DramaInfo(
    @JsonProperty("name") val name: String?, 
    @JsonProperty("cover") val cover: String?, 
    @JsonProperty("vertical_cover") val verticalCover: String?,
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
