package com.lagradost.cloudstream3.providers

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

class DramaBoxProvider : MainAPI() {
    override var mainUrl = "https://sapi.dramaboxvideo.com"
    override var name = "DramaBox VIP"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 1500L

    private val DEVICE_ID = "821b6618-ce1a-4c79-9ecc-25efbd9883a8"
    private val ANDROID_ID = "000000003801f1c83801f1c800000000"
    private val TN_TOKEN = "Bearer ZXlKMGVYQWlPaUpLVjFRaUxDSmhiR2NpT2lKSVV6STFOaUo5LmV5SnlaV2RwYzNSbGNsUjVjR1VpT2lKVVJVMVFJaXdpZFhObGNrbGtJam8wTmpNek1qTTJOakI5LnVoYkVTODg1RlZCYVItMFEtY05KQ3hfcXBKeEJYc3VjajhJMS1EcGlRLUk="

    private fun generateSn(timestamp: String, payload: String): String {
        try {
            val p1 = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC9Q4Y5QX5j08HrnbY3irfKdkEllAU2OORnAjlXDyCzcm2Z6ZRrGvtTZUAMelfU5PWS6XGEm3d4kJEKbXi4Crl8o2E/E3YJPk1lQD1d0JTdrvZleETN1ViHZFSQwS3L94Woh0E3TPebaEYq88eExvKu1tDdjSoFjBbgMezySnas5Nc2xF28"
            val p2Enc = "l|d,WL\$EI,?xyw+*)^#?U`[whXlG`-GZif,.jCxbKkaY\"{w*y]_jax^/1iVDdyg(Wbz+z/\$xVjCiH0lZf/d|%gZglW)\"~J,^~}w\"}m(E'eEunz)eyEy`XGaVF|_(Kw)|awUG\"'{{e#%\$0E.ffHVU++\$giHzdvC0ZLXG|U{aVUUYW{{YVU^x),J'If`nG|C[`ZF),xLv(-H'}ZIEyCfke0dZ%aU[V)\"V0}mhKvZ]Gw%-^a|m'`\\f}{(~kzi&zjG+|fXX0\$IH#j`+hfnME\"|fa/{.j.xf,\"LZ.K^bZy%c.W^/v{x#(J},Ua,ew#.##K(ki)\$LX{a-1\\MG/zL&JlEKEw'Hg|D&{EfuKYM[nGKx1V#lFu^V_LjVzw+n%+,Xd"
            val p3 = "x52e71nafqfbjXxZuEtpu92oJd6A9mWbd0BZTk72ZHUmDcKcqjfcEH19SWOphMJFYkxU5FRoIEr3/zisyTO4Mt33ZmwELOrY9PdlyAAyed7ZoH+hlTr7c025QROvb2LmqgRiUT56tMECgYEA+jH5m6iMRK6XjiBhSUnlr3DzRybwlQrtIj5sZprWe2my5uYHG3jbViYIO7GtQvMTnDrBCxNhuM6dPrL0cRnbsp/iBMXe3pyjT/aWveBkn4R+UpBsnbtDn28r1MZpCDtr5UNc0TPj4KFJvjnV/e8oGoyYEroECqcw1LqNOGDiLhkCgYEAwaemNePYrXW+MVX/hatfLQ96tpxwf7yuHdENZ2q5AFw73GJWYvC8VY+TcoKPAmeoCUMltI3TrS6K5Q/GoLd5K2BsoJrSxQNQFd3ehWAtdOuPDvQ5rn/2fsvgvc3rOvJh7uNnwEZCI/45WQg+UFWref4PPc+ArNtp9Xj2y7LndwkCgYARojIQeXmhYZjG6JtSugWZLuHGkwUDzChYcIPd"
            val p4 = "W25gdluokG/RzNvQn4+W/XfTryQjr7RpXm1VxCIrCBvYWNU2KrSYV4XUtL+B5ERNj6In6AOrOAifuVITy5cQQQeoD+AT4YKKMBkQfO2gnZzqb8+ox130e+3K/mufoqJPZeyrCQKBgC2fobjwhQvYwYY+DIUharri+rYrBRYTDbJYnh/PNOaw1CmHwXJt5PEDcml3+NlIMn58I1X2U/hpDrAIl3MlxpZBkVYFI8LmlOeR7ereTddN59ZOE4jY/OnCfqA480Jf+FKfoMHby5lPO5OOLaAfjtae1FhrmpUe3EfIx9wVuhKBAoGBAPFzHKQZbGhkqmyPW2ctTEIWLdUHyO37fm8dj1WjN4wjRAI4ohNiKQJRh3QE11E1PzBTl9lZVWT8QtEsSjnrA/tpGr378fcUT7WGBgTmBRaAnv1P1n/Tp0TSvh5XpIhhMuxcitIgrhYMIG3GbP9JNAarxO/qPW6Gi0xWaF7il7Or"
            
            val p2 = p2Enc.map { 
                var code = it.toInt()
                if (code in 33..126) {
                    code -= 20
                    if (code < 33) code += 93
                }
                code.toChar()
            }.joinToString("")

            val privateKeyBytes = Base64.decode(p1 + p2 + p3 + p4, Base64.DEFAULT)
            val privKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))

            val rawData = "timestamp=$timestamp$payload$DEVICE_ID$ANDROID_ID$TN_TOKEN"

            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(privKey)
            signer.update(rawData.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        } catch (e: Exception) { return "" }
    }

    private fun getAppHeaders(timestamp: String, sn: String): Map<String, String> {
        return mapOf(
            "version" to "100", 
            "package-name" to "com.storymatrix.drama",
            "p" to "63",
            "cid" to "DAPRAAG1050005",
            "apn" to "2",
            "country-code" to "ID",
            "mchid" to "XDASEO1000000",
            "mbid" to "42000005343",
            "tz" to "-540",
            "language" to "in",
            "mcc" to "510",
            "locale" to "in_ID",
            "is_root" to "0",
            "device-id" to DEVICE_ID,
            "nchid" to "DRA1000042",
            "instanceid" to "8bb28856c28507963a1b3becf0907d31",
            "md" to "CPH2235",
            "store-source" to "store_google",
            "mf" to "OPPO",
            "device-score" to "70",
            "lat" to "0",
            "is_emulator" to "0",
            "externalbillingavailable" to "0",
            "current-language" to "in",
            "ov" to "13",
            "userid" to "463323660",
            "afid" to "1778337696476-8633634605086751842",
            "android-id" to ANDROID_ID,
            "srn" to "1080x2400",
            "is_vpn" to "1",
            "build" to "Build/TP1A.220905.001",
            "pline" to "WEB", // Trick Bypass Aliyun
            "vn" to "1.0.0",
            "over-flow" to "new-fly",
            "tn" to TN_TOKEN,
            "sn" to sn,
            "user-agent" to "okhttp/4.12.0"
        )
    }

    override val mainPage = mainPageOf("Beranda" to "Beranda")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val timestamp = System.currentTimeMillis().toString()
        val payloadStr = """{"homePageStyle":0,"isNeedRank":1,"isNeedNewChannel":1,"recSessionId":"885925837429f1bfa0c2dea2e415f431de37f587b307d294a1c5389f9be6461e","type":0}"""
        
        val sn = generateSn(timestamp, payloadStr)
        val requestBody = payloadStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val responseText = app.post("$mainUrl/drama-box/he001/theater?timestamp=$timestamp", headers = getAppHeaders(timestamp, sn), requestBody = requestBody).text
        val response = parseJson<TheaterApiRes>(responseText)
        if (response.status != 0) throw ErrorLoadingException("Server Error: ${response.message}")

        val homePageList = mutableListOf<HomePageList>()
        response.data?.columnVoList?.forEach { column ->
            val title = column.title ?: return@forEach
            val items = column.bookList?.mapNotNull { book ->
                val bId = book.bookId
                val bName = book.bookName
                if (bId.isNullOrEmpty() || bName.isNullOrEmpty()) return@mapNotNull null
                newTvSeriesSearchResponse(bName, bId) { this.posterUrl = book.coverWap }
            } ?: emptyList()
            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
        }
        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val timestamp = System.currentTimeMillis().toString()
        val payloadStr = """{"keyword":"$query","pageNo":1,"pageSize":20,"sortType":1,"synSwitch":1}"""
        
        val sn = generateSn(timestamp, payloadStr)
        val requestBody = payloadStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val responseText = app.post("$mainUrl/drama-box/search/search?timestamp=$timestamp", headers = getAppHeaders(timestamp, sn), requestBody = requestBody).text
        val response = parseJson<SearchApiRes>(responseText)
        if (response.status != 0) throw ErrorLoadingException("Search Error: ${response.message}")

        return response.data?.searchList?.mapNotNull {
            val bId = it.bookId
            val bName = it.bookName
            if (bId.isNullOrEmpty() || bName.isNullOrEmpty()) return@mapNotNull null
            newTvSeriesSearchResponse(bName, bId) { this.posterUrl = it.cover }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/")
        val timestamp = System.currentTimeMillis().toString()
        
        val metaPayloadStr = """{"boundaryIndex":0,"index":0,"currencyPlaySource":"jmtj","needEndRecommend":0,"currencyPlaySourceName":"剧末推荐","preLoad":false,"rid":"","pullCid":"","loadDirection":0,"startUpKey":"76892858-3e57-40b1-80cd-bfe098991909","bookId":"$bookId"}"""
        val snMeta = generateSn(timestamp, metaPayloadStr)
        val requestMeta = metaPayloadStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val metaResText = app.post("$mainUrl/drama-box/chapterv2/batch/load?timestamp=$timestamp", headers = getAppHeaders(timestamp, snMeta), requestBody = requestMeta).text
        val metaData = parseJson<BatchLoadRes>(metaResText).data
        val coverUrl = metaData?.bookCover
        val title = metaData?.bookName ?: "DramaBox"
        val plot = metaData?.introduction

        val detailPayloadStr = """{"needRecommend":true,"from":"book_ablum","bookId":"$bookId"}"""
        val snDetail = generateSn(timestamp, detailPayloadStr)
        val requestDetail = detailPayloadStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val detailResText = app.post("$mainUrl/drama-box/chapterv2/detail?timestamp=$timestamp", headers = getAppHeaders(timestamp, snDetail), requestBody = requestDetail).text
        val detailResponse = parseJson<DetailApiRes>(detailResText)
        val listEps = detailResponse.data?.list ?: throw ErrorLoadingException("Daftar Episode Kosong")

        val episodes = listEps.mapNotNull { chapter ->
            val chId = chapter.chapterId ?: return@mapNotNull null
            val idx = chapter.chapterIndex ?: 0
            
            val dataString = """{"bookId":"$bookId","chapterId":"$chId","index":$idx}"""
            
            newEpisode(dataString) {
                this.name = "Episode ${idx + 1}"
                this.episode = idx + 1
                this.posterUrl = coverUrl 
            }
        }

        return newTvSeriesLoadResponse(title, bookId, TvType.TvSeries, episodes) {
            this.posterUrl = coverUrl
            this.plot = plot
            this.tags = metaData?.tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parsedBook = data.substringAfter("\"bookId\":\"").substringBefore("\"")
        val parsedId = data.substringAfter("\"chapterId\":\"").substringBefore("\"")
        val parsedIndex = data.substringAfter("\"index\":").substringBefore("}").toIntOrNull() ?: 0

        val timestamp = System.currentTimeMillis().toString()
        
        val unlockPayloadStr = """{"bookId":"$parsedBook","chapterId":"$parsedId","vip":true,"unLockType":1,"confirmPay":true,"autoPay":true}"""
        val snUnlock = generateSn(timestamp, unlockPayloadStr)
        val requestBody = unlockPayloadStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        try {
            app.post("$mainUrl/drama-box/chapterv2/unlock?timestamp=$timestamp", headers = getAppHeaders(timestamp, snUnlock), requestBody = requestBody)
        } catch (e: Exception) {}

        var targetChapter: Chapter? = null
        var currentBoundary = 0
        var lastIndex = -1
        
        for (i in 0..10) {
            val loadPayloadStr = """{"boundaryIndex":$currentBoundary,"index":-1,"currencyPlaySource":"jmtj","needEndRecommend":0,"currencyPlaySourceName":"剧末推荐","preLoad":false,"rid":"","pullCid":"","loadDirection":0,"startUpKey":"76892858-3e57-40b1-80cd-bfe098991909","bookId":"$parsedBook"}"""
            val snLoad = generateSn(timestamp, loadPayloadStr)
            val loadReqBody = loadPayloadStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            
            val loadResText = app.post("$mainUrl/drama-box/chapterv2/batch/load?timestamp=$timestamp", headers = getAppHeaders(timestamp, snLoad), requestBody = loadReqBody).text
            val loadRes = parseJson<BatchLoadRes>(loadResText)
            
            val chapterList = loadRes.data?.chapterList
            if (chapterList.isNullOrEmpty()) break
            
            targetChapter = chapterList.find { it.chapterId == parsedId }
            if (targetChapter != null) break 
            
            val newLastIndex = chapterList.last().chapterIndex ?: break
            if (newLastIndex == lastIndex) break 
            lastIndex = newLastIndex
            currentBoundary = lastIndex 
            
            if (currentBoundary > parsedIndex + 50) break 
        }

        if (targetChapter == null) {
             val fbPayload = """{"boundaryIndex":$parsedIndex,"index":$parsedIndex,"currencyPlaySource":"jmtj","needEndRecommend":0,"currencyPlaySourceName":"剧末推荐","preLoad":false,"rid":"","pullCid":"","loadDirection":0,"startUpKey":"76892858-3e57-40b1-80cd-bfe098991909","bookId":"$parsedBook"}"""
             val snFb = generateSn(timestamp, fbPayload)
             val reqFb = fbPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
             val fbText = app.post("$mainUrl/drama-box/chapterv2/batch/load?timestamp=$timestamp", headers = getAppHeaders(timestamp, snFb), requestBody = reqFb).text
             val fbRes = parseJson<BatchLoadRes>(fbText)
             targetChapter = fbRes.data?.chapterList?.find { it.chapterId == parsedId } ?: fbRes.data?.chapterList?.firstOrNull()
        }

        targetChapter?.cdnList?.forEachIndexed { serverIndex, cdn ->
            cdn.videoPathList?.forEach { videoInfo ->
                val videoUrl = videoInfo.videoPath ?: return@forEach
                val qualityNum = videoInfo.quality ?: Qualities.P1080.value
                
                // BERSIHKAN URL TANPA MEMBUANG SIGNATURE
                // Aliyun Web Video Player butuh MP4 bersih tanpa ekstensi encrypt
                val cleanMp4 = videoUrl
                    .replace(".nav2.encrypt.mp4", ".mp4")
                    .replace(".nav2.mp4", ".mp4")
                    .replace(".encrypt.mp4", ".mp4")
                
                // M3U8 Murni untuk HLS
                val cleanM3u8 = videoUrl
                    .replace(".nav2.encrypt.mp4", ".m3u8")
                    .replace(".nav2.mp4", ".m3u8")
                    .replace(".encrypt.mp4", ".m3u8")
                    .replace(".mp4", ".m3u8")
                
                // TEMBAKAN PERTAMA: M3U8 Mutlak (Bypass HLS Aliyun)
                callback.invoke(
                    newExtractorLink(
                        source = "DramaBox",
                        name = "Server ${serverIndex + 1} HLS Q${qualityNum}",
                        url = cleanM3u8,
                        type = ExtractorLinkType.M3U8 // PASTI DIBACA SEBAGAI M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = qualityNum
                    }
                )
                
                // TEMBAKAN KEDUA: MP4 Mutlak (Bypass MP4 Direct)
                callback.invoke(
                    newExtractorLink(
                        source = "DramaBox",
                        name = "Server ${serverIndex + 1} MP4 Q${qualityNum}",
                        url = cleanMp4,
                        type = ExtractorLinkType.VIDEO // PASTI DIBACA SEBAGAI VIDEO MP4
                    ) {
                        this.referer = mainUrl
                        this.quality = qualityNum
                    }
                )
            }
        }
        return true
    }

    data class TheaterApiRes(val status: Int?, val message: String?, val data: TheaterData?)
    data class TheaterData(val columnVoList: List<ColumnVo>?)
    data class ColumnVo(val title: String?, val bookList: List<BookItem>?)
    data class BookItem(val bookId: String?, val bookName: String?, val coverWap: String?)

    data class SearchApiRes(val status: Int?, val message: String?, val data: SearchData?)
    data class SearchData(val searchList: List<SearchItem>?)
    data class SearchItem(val bookId: String?, val bookName: String?, val cover: String?)
    
    data class DetailApiRes(val status: Int?, val message: String?, val data: DetailData?)
    data class DetailData(val list: List<DetailChapter>?)
    data class DetailChapter(val chapterId: String?, val chapterIndex: Int?)

    data class BatchLoadRes(val status: Int?, val message: String?, val data: BatchLoadData?)
    data class BatchLoadData(val bookName: String?, val bookCover: String?, val introduction: String?, val tags: List<String>?, val chapterList: List<Chapter>?)
    data class Chapter(val chapterId: String?, val chapterIndex: Int?, val chapterName: String?, val cdnList: List<Cdn>?)
    data class Cdn(val videoPathList: List<VideoPath>?)
    data class VideoPath(val quality: Int?, val videoPath: String?)
}
