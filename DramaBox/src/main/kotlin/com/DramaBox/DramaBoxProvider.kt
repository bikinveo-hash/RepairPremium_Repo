package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DramaBoxProvider : MainAPI() {
    override var mainUrl = "https://sapi.dramaboxvideo.com"
    override var name = "DramaBox VIP"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.TvSeries)

    // 🔥 KUNCI MASTER: SPOOFING HEADERS 🔥
    private val commonHeaders = mapOf(
        "pline" to "WEB",
        "version" to "100",
        "package-name" to "com.storymatrix.drama",
        "tn" to "Bearer ZXlKMGVYQWlPaUpLVjFRaUxDSmhiR2NpT2lKSVV6STFOaUo5LmV5SnlaV2RwYzNSbGNsUjVjR1VpT2lKVVJVMVFJaXdpZFhObGNrbGtJam8wTmpNek1qTTJOakI5LnVoYkVTODg1RlZCYVItMFEtY05KQ3hfcXBKeEJYc3VjajhJMS1EcGlRLUk=",
        "sn" to "VI/9EnXOF8jh78XV45v71VL1hZIO5hhmafsCGpEPG18JMnWEN9JGPWxIrU0lCkMJ+oFI9OWNmdyB6aHltxKloLHKWUITa94yA24NpL/MaM2ZnVYG62LHP3/+F0kX0DoE0rNAjniJBGiFsYBWvNOxsJ0TLVZi34DgzNN9clUJEpWHHAVGLdJNcgUVunr0EQTEmijj3Gy8/MdhQCAyc6CeXiEAif3jFl/YeVGygqOLAH9TPELmbC9M7OlApxE/9bO9wtaubJqjIh/6qSyy866Fojz+e+qDD5fWiBq7AfQFRwL5/II8Tqf47vWZN5BGnn4AuaKC2Qi2qBdAmM5XyFceGw==",
        "st" to "cK4n10B_0tTQBrxFxxkaXsVp",
        "device-id" to "821b6618-ce1a-4c79-9ecc-25efbd9883a8",
        "user-agent" to "okhttp/4.10.0"
    )

    data class EpisodeData(
        val bookId: String,
        val chapterId: String,
        val videoUrl: String
    )

    // ==========================================
    // 1. DAFTAR KATEGORI (MAIN PAGE)
    // ==========================================
    override val mainPage = mainPageOf(
        "1" to "Untukmu (For You)",
        "2" to "Pembalasan Dendam",
        "3" to "Romantis",
        "4" to "CEO / Bos",
        "5" to "Manusia Serigala",
        "6" to "Populer"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/drama-box/he001/channel"
        val payload = mapOf(
            "channelId" to request.data.toInt(),
            "pageNo" to page,
            "pageSize" to 20,
            "isNeedRank" to 1
        )

        val response = app.post(url, headers = commonHeaders, json = payload).parsedSafe<HomeApiRes>()
        val homeItems = response?.data?.list?.map { item ->
            // FIX: Menggunakan builder newTvSeriesSearchResponse sesuai aturan baru
            newTvSeriesSearchResponse(
                name = item.bookName ?: "",
                url = item.bookId ?: ""
            ) {
                this.posterUrl = item.bookCover ?: ""
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, homeItems)
    }

    // ==========================================
    // 2. FITUR PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/drama-box/search/search"
        val payload = mapOf(
            "keyword" to query,
            "pageNo" to 1,
            "pageSize" to 20,
            "synSwitch" to 1
        )

        val response = app.post(url, headers = commonHeaders, json = payload).parsedSafe<SearchApiRes>()
        return response?.data?.list?.map { item ->
            // FIX: Menggunakan builder newTvSeriesSearchResponse
            newTvSeriesSearchResponse(
                name = item.bookName ?: "",
                url = item.bookId ?: ""
            ) {
                this.posterUrl = item.bookCover ?: ""
            }
        } ?: emptyList()
    }

    // ==========================================
    // 3. HALAMAN DETAIL & DAFTAR EPISODE (LOAD)
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val bookId = url
        val loadUrl = "$mainUrl/drama-box/chapterv2/batch/load"
        val payload = mapOf(
            "bookId" to bookId,
            "boundaryIndex" to 0,
            "loadDirection" to 2,
            "index" to 1
        )

        val response = app.post(loadUrl, headers = commonHeaders, json = payload).parsedSafe<BatchLoadRes>()
            ?: throw Error("Gagal load data")
        val data = response.data ?: throw Error("Data kosong")

        val episodes = data.chapterList?.map { chapter ->
            val bestVideo = chapter.cdnList?.firstOrNull()?.videoPathList?.maxByOrNull { it.quality ?: 0 }
            
            val epData = EpisodeData(
                bookId = bookId,
                chapterId = chapter.chapterId ?: "",
                videoUrl = bestVideo?.videoPath ?: ""
            )

            // FIX: Menggunakan builder newEpisode
            newEpisode(data = epData.toJson()) {
                this.name = chapter.chapterName
                this.episode = (chapter.chapterIndex ?: 0) + 1
            }
        } ?: emptyList()

        // FIX: Menggunakan builder newTvSeriesLoadResponse
        return newTvSeriesLoadResponse(
            name = data.bookName ?: "",
            url = bookId,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = data.bookCover
            this.plot = data.introduction
            this.tags = data.tags
        }
    }

    // ==========================================
    // 4. SIHIR BYPASS VIP & PLAY VIDEO (LOAD LINKS)
    // ==========================================
    // FIX: Memperbaiki urutan parameter subtitleCallback dan callback
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedData = parseJson<EpisodeData>(data)

        // 🔥 UNLOCK VIP SILUMAN 🔥
        val unlockUrl = "$mainUrl/drama-box/chapterv2/unlock"
        val unlockPayload = mapOf(
            "bookId" to parsedData.bookId,
            "chapterId" to parsedData.chapterId,
            "vip" to true,
            "unLockType" to 1,
            "confirmPay" to true,
            "autoPay" to true
        )
        app.post(unlockUrl, headers = commonHeaders, json = unlockPayload)

        // FIX: Menggunakan builder newExtractorLink
        callback.invoke(
            newExtractorLink(
                source = "DramaBox",
                name = "DramaBox VIP",
                url = parsedData.videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }

    // ==========================================
    // MAPPING JSON DATA (DATA CLASSES)
    // ==========================================
    data class HomeApiRes(val data: HomeData?)
    data class HomeData(val list: List<HomeItem>?)
    data class HomeItem(val bookId: String?, val bookName: String?, val bookCover: String?)

    data class SearchApiRes(val data: SearchData?)
    data class SearchData(val list: List<HomeItem>?)

    data class BatchLoadRes(val data: BatchLoadData?)
    data class BatchLoadData(
        val bookName: String?,
        val bookCover: String?,
        val introduction: String?,
        val tags: List<String>?,
        val chapterList: List<Chapter>?
    )
    data class Chapter(
        val chapterId: String?,
        val chapterIndex: Int?,
        val chapterName: String?,
        val cdnList: List<Cdn>?
    )
    data class Cdn(val videoPathList: List<VideoPath>?)
    data class VideoPath(val quality: Int?, val videoPath: String?)
}
