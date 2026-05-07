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
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Anime)

    private val secureRandom = SecureRandom()
    private val deviceId = (1..32).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
    private val sessionId = java.util.UUID.randomUUID().toString()
    
    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    private val nativeLoginSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv"
    
    private var sessionToken: String? = null
    private var sessionSecret: String? = null
    private val sessionLock = Mutex()

    // 🪄 Struktur persis seperti kitab suci MainAPI.kt aslinya
    private data class NativeCategory(val key: String, val name: String, val tabKey: String, val posIndex: Int, val isComingSoon: Boolean = false)
    
    private val nativeCategories = listOf(
        NativeCategory("popular", "Populer", "993", 10000),
        NativeCategory("new", "New", "995", 10000),
        NativeCategory("coming_soon", "Segera Hadir", "1004", 10000, true),
        NativeCategory("dubbing", "Dubbing", "1002", 10000),
        NativeCategory("female", "Perempuan", "994", 10000),
        NativeCategory("male", "Laki-Laki", "996", 10000),
        NativeCategory("anime", "Anime", "1005", 10001)
    )

    override val mainPage = mainPageOf(
        "popular" to "Populer",
        "new" to "New",
        "coming_soon" to "Segera Hadir",
        "dubbing" to "Dubbing",
        "female" to "Perempuan",
        "male" to "Laki-Laki",
        "anime" to "Anime"
    )

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun getNativeHeaders(isVip: Boolean = false): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        val signature = md5(authSalt + (sessionSecret ?: ""))
        
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "OpCountryCode" to "ID",
            "X-AppEngine-Country" to "ID",
            "app-language" to "id",
            "prefer_country" to "ID",
            "locale" to "id-ID",
            "language" to "id-ID",
            "country" to "ID",
            "X-Timezone" to "Asia/Jakarta",
            "timezone" to "7",
            "X-Timezone-offset" to "7",
            "network-type" to "WIFI",
            "screen-width" to "411",
            "screen-height" to "891",
            "is-mainland" to "false",
            "device-memory" to "8.00",
            "device-country" to "ID",
            "device-language" to "id-ID",
            "x-device-model" to "23090RA98G",
            "x-device-manufacturer" to "Xiaomi",
            "x-device-brand" to "Redmi",
            "x-device-product" to "sky",
            "x-device-fingerprint" to "Redmi/sky_global/sky:14/UKQ1.231003.002/V816.0.11.0.UMWMIXM:user/release-keys",
            "session-id" to sessionId,
            "app-name" to "com.freereels.app",
            "app-version" to "2.2.40", // Versi asli anti-blokir
            "device-id" to deviceId,
            "device-version" to "34",
            "device" to "android",
            "Authorization" to "oauth_signature=$signature,oauth_token=${sessionToken ?: "undefined"},ts=$ts"
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
            
            val loginSig = md5(nativeLoginSalt + deviceId)
            val reqBody = mapOf(
                "device_id" to deviceId,
                "device_name" to "Redmi 23090RA98G",
                "device_sign" to loginSig
            ).toJson().toRequestBody("application/json".toMediaTypeOrNull())
            
            val res = app.post("$nativeApiUrl/anonymous/login", headers = getNativeHeaders(), requestBody = reqBody).text
            val authData = tryParseJson<NativeAuthResponse>(res)
            
            sessionToken = authData?.data?.authKey ?: authData?.data?.token
            sessionSecret = authData?.data?.authSecret ?: ""
        }
    }

    // 🪄 Logika persis fungsi getCategoryPage() dan fetchFeedPage() dari Kode Asli!
    private suspend fun getCategoryPage(category: NativeCategory, page: Int): Pair<List<UniversalItem>, Boolean> {
        if (category.isComingSoon) {
            if (page <= 1) {
                val url = "$nativeApiUrl/coming-soon/list"
                val res = app.get(url, headers = getNativeHeaders(isVip = false)).text
                val dataObj = tryParseJson<UniversalFeedResponse>(res)?.data
                
                val items = mutableListOf<UniversalItem>()
                // Rute coming soon punya format json flat (langsung items)
                dataObj?.items?.let { items.addAll(it) }
                dataObj?.list?.let { items.addAll(it) }
                return items to false
            }
            return emptyList<UniversalItem>() to false
        }

        val url = "$nativeApiUrl/homepage/v2/tab/index?tab_key=${category.tabKey}&position_index=${category.posIndex}&first="
        val res = app.get(url, headers = getNativeHeaders(isVip = false)).text 
        val moduleIndex = tryParseJson<UniversalFeedResponse>(res)?.data ?: return emptyList<UniversalItem>() to false

        if (page <= 1) {
            val items = mutableListOf<UniversalItem>()
            // 🪄 FIX BUG: Kita abaikan 'components' (Banner) persis seperti aslinya agar film belum rilis tidak bocor ke Populer
            moduleIndex.items?.forEach { mod ->
                mod.items?.let { items.addAll(it) }
                mod.list?.let { items.addAll(it) }
            }
            moduleIndex.list?.forEach { mod ->
                mod.items?.let { items.addAll(it) }
                mod.list?.let { items.addAll(it) }
            }
            // Khusus Anime, kalau items/list di dalam modul kosong, kita sedot items utamanya
            if (items.isEmpty()) {
                moduleIndex.items?.let { items.addAll(it) }
                moduleIndex.list?.let { items.addAll(it) }
            }
            val hasMore = moduleIndex.pageInfo?.hasMore == true || !moduleIndex.pageInfo?.next.isNullOrBlank()
            return items to hasMore
        }

        // PAGE > 1: Cari Recommend Key & Looping Berantai (Sistem asli tanpa Cache)
        val feedPages = moduleIndex.items?.filter { !it.moduleKey.isNullOrBlank() && !it.items.isNullOrEmpty() }
        val recommendKey = feedPages?.firstOrNull()?.moduleKey ?: category.tabKey
        
        var currentNext = moduleIndex.pageInfo?.next
        var currentData: UniversalFeedData? = null
        
        for (i in 1 until page) {
            if (currentNext.isNullOrBlank()) break
            val reqBody = mapOf("module_key" to recommendKey, "next" to currentNext).toJson().toRequestBody("application/json".toMediaTypeOrNull())
            val feedRes = app.post("$nativeApiUrl/homepage/v2/tab/feed", headers = getNativeHeaders(isVip = false), requestBody = reqBody).text
            currentData = tryParseJson<UniversalFeedResponse>(feedRes)?.data
            currentNext = currentData?.pageInfo?.next
        }

        val items = mutableListOf<UniversalItem>()
        currentData?.items?.let { items.addAll(it) }
        currentData?.list?.let { items.addAll(it) }
        
        val hasMore = currentData?.pageInfo?.hasMore == true || !currentNext.isNullOrBlank()
        
        return items to hasMore
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()
        
        val cat = nativeCategories.find { it.key == request.data } ?: throw ErrorLoadingException("Kategori tidak ditemukan")
        val (rawItems, hasMore) = getCategoryPage(cat, page)

        val items = rawItems.mapNotNull { item -> 
            val title = item.title ?: item.name ?: return@mapNotNull null
            val idStr = item.id?.toString() ?: item.key ?: item.seriesId?.toString() ?: return@mapNotNull null
            
            if (title.equals("Ranking", ignoreCase = true) || title.equals("Peringkat", ignoreCase = true) || title.equals("Top", ignoreCase = true)) {
                return@mapNotNull null
            }
            
            // 🪄 FIX BUG: Label Dubbing eksklusif hanya jika di judul ada tulisan Dubbed/Sulih Suara
            val isDubbed = title.contains("Dubbed", true) || title.contains("Sulih Suara", true) || title.contains("(Dub)", true)
            val cover = item.cover ?: item.verticalCover

            newAnimeSearchResponse(title, idStr, TvType.AsianDrama) { 
                this.posterUrl = fixUrlNull(cover)
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, hasNext = hasMore)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureSession()
        val nextToken = if (page == 1) "" else "offset=${(page - 1) * 20}&page_size=20"
        val reqBody = mapOf("keyword" to query, "next" to nextToken).toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("$nativeApiUrl/search/drama", headers = getNativeHeaders(isVip = false), requestBody = reqBody).text
        
        val searchItems = mutableListOf<UniversalItem>()
        var hasMore = false
        
        try {
            val dataObj = tryParseJson<UniversalFeedResponse>(res)?.data
            if (dataObj != null) {
                hasMore = dataObj.pageInfo?.hasMore ?: false
                dataObj.items?.let { searchItems.addAll(it) }
                dataObj.list?.let { searchItems.addAll(it) }
            }
        } catch (e: Exception) {}
        
        val list = searchItems.mapNotNull { item ->
            val title = item.name ?: item.title ?: return@mapNotNull null
            val idStr = item.id?.toString() ?: item.key ?: item.seriesId?.toString() ?: return@mapNotNull null
            
            // 🪄 FIX BUG: Sama dengan Homepage
            val isDubbed = title.contains("Dubbed", true) || title.contains("Sulih Suara", true) || title.contains("(Dub)", true)
            
            newAnimeSearchResponse(title, idStr, TvType.AsianDrama) { 
                this.posterUrl = fixUrlNull(item.cover ?: item.verticalCover)
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        }.distinctBy { it.url }
        
        return newSearchResponseList(list, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        ensureSession()
        
        val seriesId = url.split("/").last()
        
        var res = app.get("$nativeApiUrl/drama/info_v2?series_id=$seriesId", headers = getNativeHeaders(isVip = true)).text
        var info = tryParseJson<NativeDetailResponse>(res)?.data?.info
        
        // Membaca fallback info dari endpoint lama
        if (info == null || info.episodeList.isNullOrEmpty()) {
            val fallbackRes = app.get("$nativeApiUrl/drama/info?series_id=$seriesId", headers = getNativeHeaders(isVip = true)).text
            val fallbackInfo = tryParseJson<NativeDetailResponse>(fallbackRes)?.data?.info
            
            if (fallbackInfo != null && !fallbackInfo.episodeList.isNullOrEmpty()) {
                info = fallbackInfo
            } else if (fallbackInfo != null && info == null) {
                info = fallbackInfo
            }
        }

        if (info == null) throw ErrorLoadingException("Detail Film tidak ditemukan di server.")

        val mainCover = fixUrlNull(info.cover ?: info.verticalCover)

        val episodeList = info.episodeList?.mapNotNull { ep -> 
            newEpisode(ep.toJson()) {
                this.name = ep.name ?: "Episode ${ep.index}"
                this.episode = ep.index
                this.posterUrl = fixUrlNull(ep.cover) ?: mainCover 
            } 
        } ?: emptyList()

        return newTvSeriesLoadResponse(info.name ?: "Drama", url, TvType.AsianDrama, episodeList) {
            this.posterUrl = mainCover
            this.plot = info.desc
            // 🪄 Menampilkan UI "Segera Hadir" (Menghindari crash layar hitam jika film belum rilis)
            this.comingSoon = episodeList.isEmpty() 
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
// ==========================================
data class NativeAuthResponse(@JsonProperty("data") val data: AuthData?)
data class AuthData(@JsonProperty("auth_key") val authKey: String?, @JsonProperty("auth_secret") val authSecret: String?, @JsonProperty("token") val token: String?)

data class UniversalFeedResponse(@JsonProperty("data") val data: UniversalFeedData?)
data class UniversalFeedData(
    @JsonProperty("items") val items: List<UniversalItem>?,
    @JsonProperty("list") val list: List<UniversalItem>?,
    @JsonProperty("components") val components: List<UniversalItem>?,
    @JsonProperty("modules") val modules: List<UniversalItem>?,
    @JsonProperty("page_info") val pageInfo: PageInfo?
)

data class UniversalItem(
    @JsonProperty("id") val id: Any?, 
    @JsonProperty("key") val key: String?, 
    @JsonProperty("series_id") val seriesId: Any?, 
    @JsonProperty("title") val title: String?, 
    @JsonProperty("name") val name: String?, 
    @JsonProperty("cover") val cover: String?,
    @JsonProperty("vertical_cover") val verticalCover: String?,
    @JsonProperty("type") val type: String?, 
    @JsonProperty("module_key") val moduleKey: String?, 
    @JsonProperty("episode_info") val episodeInfo: NativeEpisodeInfo?,
    @JsonProperty("items") val items: List<UniversalItem>?,
    @JsonProperty("list") val list: List<UniversalItem>?
)

data class PageInfo(
    @JsonProperty("has_more") val hasMore: Boolean?,
    @JsonProperty("next") val next: String?
)

data class NativeEpisodeInfo(
    @JsonProperty("audio") val audio: List<String>?,
    @JsonProperty("original_audio_language") val originalAudioLanguage: String?,
    @JsonProperty("new") val isNew: Boolean?
)

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
    @JsonProperty("cover") val cover: String?,
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
