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

    // 🔑 KUNCI MASTER: SPOOFING HEADERS
    // Sudah diupdate menggunakan data terbaru hasil sniffing
    private val commonHeaders = mapOf(
        "pline" to "ANDROID",
        "version" to "572",
        "package-name" to "com.storymatrix.drama",
        "tn" to "Bearer ZXlKMGVYQWlPaUpLVjFRaUxDSmhiR2NpT2lKSVV6STFOaUo5LmV5SnlaV2RwYzNSbGNsUjVjR1VpT2lKVVJVMVFJaXdpZFhObGNrbGtJam8wTmpNek1qTTJOakI5LnVoYkVTODg1RlZCYVItMFEtY05KQ3hfcXBKeEJYc3VjajhJMS1EcGlRLUk=",
        "sn" to "X8qQyTiKDyPQ6ezq+a/WRw9lRJ2EGzwPF041JcOkLoDFp18qtAEajhY897+X48hV1Q/sbJkK63fgVna7bJ+9lNWSKwaO4OWMFxM+c6fWuguQBHO0gKLobzT9N5XY6871Z9GaQgbYZX3bGzHCvCoYL8HBjxg14jTPo9nPF9v1m4REutLSwbOXZSRXS8Gz91Js7PF4+KO02uE3g60/HxdGb6+XO8rTW0oeYcJF/JkSH6VH5QNuOgUxBvg3cLucvgCnUKnjquoGcDs8oVNLD+jis8rzEcnbicAK7ucyIHyu+Fd6U7wC0vqtNboR4SqbD4NQRfespte7yXskZ0CrFZos/g==",
        "st" to "cK4n10B_0tTQBrxFee_7cs5Q",
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
        "theater" to "Beranda",
        "classify_dub" to "Sulih Suara"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homePageLists = mutableListOf<HomePageList>()

        if (request.data == "theater") {
            val url = "$mainUrl/drama-box/he001/theater"
            val payload = mapOf(
                "homePageStyle" to 0,
                "isNeedRank" to 1,
                "isNeedNewChannel" to 1,
                "type" to 0
            )

            val responseReq = app.post(url, headers = commonHeaders, json = payload)
            // Uncomment baris di bawah kalau masih error buat nge-cek pesan dari servernya
            // println("DRAMABOX DEBUG THEATER: ${responseReq.text}")
            
            val response = responseReq.parsedSafe<TheaterApiRes>()
            
            response?.data?.columnVoList?.forEach { column ->
                val items = column.bookList?.mapNotNull { book ->
                    newTvSeriesSearchResponse(
                        name = book.bookName ?: "",
                        url = book.bookId ?: ""
                    ) {
                        this.posterUrl = book.coverWap ?: ""
                    }
                }
                if (!items.isNullOrEmpty()) {
                    homePageLists.add(HomePageList(column.title ?: "Rekomendasi", items))
                }
            }

        } else if (request.data == "classify_dub") {
            val url = "$mainUrl/drama-box/he001/classify"
            val payload = mapOf(
                "pageNo" to page,
                "pageSize" to 20,
                "showLabels" to false,
                "typeList" to listOf(
                    mapOf("type" to 1, "value" to ""),
                    mapOf("type" to 2, "value" to "1"), 
                    mapOf("type" to 3, "value" to ""),
                    mapOf("type" to 4, "value" to ""),
                    mapOf("type" to 5, "value" to "1")
                )
            )

            val response = app.post(url, headers = commonHeaders, json = payload).parsedSafe<ClassifyApiRes>()
            val items = response?.data?.classifyBookList?.records?.mapNotNull { book ->
                newTvSeriesSearchResponse(
                    name = book.bookName ?: "",
                    url = book.bookId ?: ""
                ) {
                    this.posterUrl = book.coverWap ?: ""
                }
            }
            if (!items.isNullOrEmpty()) {
                homePageLists.add(HomePageList("Drama Sulih Suara", items))
            }
        }

        // Fix: Menggunakan newHomePageResponse
        return newHomePageResponse(homePageLists)
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
            "sortType" to 1,
            "synSwitch" to 1
        )

        val response = app.post(url, headers = commonHeaders, json = payload).parsedSafe<SearchApiRes>()
        
        return response?.data?.searchList?.map { item ->
            newTvSeriesSearchResponse(
                name = item.bookName ?: "",
                url = item.bookId ?: ""
            ) {
                this.posterUrl = item.cover ?: "" 
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
            ?: throw ErrorLoadingException("Gagal memuat data drama")
        val data = response.data ?: throw ErrorLoadingException("Data drama kosong")

        val episodes = data.chapterList?.map { chapter ->
            val bestVideo = chapter.cdnList?.firstOrNull()?.videoPathList?.maxByOrNull { it.quality ?: 0 }
            
            val epData = EpisodeData(
                bookId = bookId,
                chapterId = chapter.chapterId ?: "",
                videoUrl = bestVideo?.videoPath ?: ""
            )

            newEpisode(data = epData.toJson()) {
                this.name = chapter.chapterName
                this.episode = (chapter.chapterIndex ?: 0) + 1
            }
        } ?: emptyList()

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
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsedData = parseJson<EpisodeData>(data)

        // Magic Unlock: Mengirim permintaan unlock agar server memberikan akses link video
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
    // DATA CLASSES UNTUK MAPPING JSON SERVER
    // ==========================================
    data class TheaterApiRes(val data: TheaterData?)
    data class TheaterData(val columnVoList: List<ColumnVo>?)
    data class ColumnVo(val title: String?, val bookList: List<BookItem>?)
    
    data class ClassifyApiRes(val data: ClassifyData?)
    data class ClassifyData(val classifyBookList: ClassifyBookList?)
    data class ClassifyBookList(val records: List<BookItem>?)
    
    data class BookItem(val bookId: String?, val bookName: String?, val coverWap: String?)

    data class SearchApiRes(val data: SearchData?)
    data class SearchData(val searchList: List<SearchItem>?)
    data class SearchItem(val bookId: String?, val bookName: String?, val cover: String?)

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
