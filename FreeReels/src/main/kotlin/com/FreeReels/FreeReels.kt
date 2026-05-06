package com.FreeReels

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
import java.security.SecureRandom

class FreeReels : MainAPI() {
    override var mainUrl = "https://m.mydramawave.com"
    private val h5ApiUrl = "https://api.mydramawave.com/h5-api"
    
    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    // Identitas Device Statis & Acak untuk "Mengelabuhi" Server Web
    private val secureRandom = SecureRandom()
    // KTP Palsu yang tidak akan pernah di-blacklist: 32 Karakter Hex
    private val deviceId = (1..32).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
    
    // Cookie Statis
    private val fakeCookie = "k_device_hash=$deviceId"

    // Kategori dari API Tab Web
    override val mainPage = mainPageOf(
        "28" to "Populer",       // id dari tab Populer
        "29" to "Terbaru",       // id dari tab Terbaru
        "30" to "Segera Hadir"   // id dari tab Segera Hadir
    )

    // ==========================================
    // MESIN KRIPTOGRAFI H5 (TANPA AES)
    // ==========================================
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ==========================================
    // INJEKSI KTP WEB (HEADER INTERCEPTOR)
    // ==========================================
    private fun getWebHeaders(): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        // Rumus sakti dari Kiwi Browser: MD5 dari ID perangkat!
        val signature = md5(deviceId) 
        
        return mutableMapOf(
            "authority" to "api.mydramawave.com",
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "id-ID,id;q=0.9",
            "app-name" to "com.dramawave.h5",
            "app-version" to "1.2.20",
            "authorization" to "oauth_signature=$signature,oauth_token=undefined,ts=$ts",
            "content-type" to "application/json",
            "cookie" to fakeCookie,
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
    private suspend fun executeGet(path: String): String {
        return app.get("$h5ApiUrl/$path", headers = getWebHeaders()).text
    }

    private suspend fun executePost(path: String, body: Any): String {
        // Karena H5 menggunakan JSON murni, kita tembak secara raw
        return app.post("$h5ApiUrl/$path", headers = getWebHeaders(), json = body).text
    }

    // ==========================================
    // FITUR UTAMA CLOUDSTREAM
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = executeGet("homepage/v2/tab/index?tab_key=${request.data}&position_index=10000&first=")
        
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
            // PERBAIKAN: Cukup gunakan `ep` saja, tanpa `.toJson()`! CloudStream akan otomatis memprosesnya.
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
