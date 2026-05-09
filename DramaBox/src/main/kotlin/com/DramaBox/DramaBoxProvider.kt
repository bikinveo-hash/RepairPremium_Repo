package com.lagradost.cloudstream3.providers

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

class DramaBoxProvider : MainAPI() {
    override var mainUrl = "https://sapi.dramaboxvideo.com"
    override var name = "DramaBox VIP"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ==========================================
    // DATA IDENTITAS DARI SNIFFING
    // ==========================================
    private val DEVICE_ID = "821b6618-ce1a-4c79-9ecc-25efbd9883a8"
    private val ANDROID_ID = "000000003801f1c83801f1c800000000"
    
    // Ganti tn ini dengan token terbarumu jika suatu hari expired
    private val TN_TOKEN = "Bearer ZXlKMGVYQWlPaUpLVjFRaUxDSmhiR2NpT2lKSVV6STFOaUo5LmV5SnlaV2RwYzNSbGNsUjVjR1VpT2lKVVJVMVFJaXdpZFhObGNrbGtJam8wTmpNek1qTTJOakI5LnVoYkVTODg1RlZCYVItMFEtY05KQ3hfcXBKeEJYc3VjajhJMS1EcGlRLUk="

    // ==========================================
    // MESIN SIGNATURE RSA (sn)
    // ==========================================
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

    // ==========================================
    // HEADERS DENGAN TRIK TIME TRAVEL (Bypass st)
    // ==========================================
    private fun getAppHeaders(timestamp: String, sn: String): Map<String, String> {
        return mapOf(
            "pline" to "ANDROID",
            "version" to "100", // TRIK: Pura-pura versi jadul!
            "vn" to "1.0.0",   // TRIK: Pura-pura versi jadul!
            "package-name" to "com.storymatrix.drama",
            "cid" to "DAPRAAG1050005",
            "language" to "in",
            "userid" to "463323660",
            "android-id" to ANDROID_ID,
            "device-id" to DEVICE_ID,
            "tn" to TN_TOKEN,
            "sn" to sn,
            // st TIDAK DISERTAKAN LAGI! KITA BEBAS DARI FILE .SO!
            "user-agent" to "okhttp/4.12.0",
            "content-type" to "application/json; charset=UTF-8"
        )
    }

    // ==========================================
    // BERANDA
    // ==========================================
    override val mainPage = mainPageOf("theater" to "Rekomendasi VIP")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val timestamp = System.currentTimeMillis().toString()
        val payload = mapOf("homePageStyle" to 0, "isNeedRank" to 1, "index" to 0, "type" to 0, "channelId" to 175)
        val sn = generateSn(timestamp, payload.toJson().replace(" ", ""))

        val response = app.post("$mainUrl/drama-box/he001/theater?timestamp=$timestamp",
            headers = getAppHeaders(timestamp, sn), json = payload).parsedSafe<TheaterApiRes>()

        val items = response?.data?.columnVoList?.flatMap { it.bookList ?: emptyList() }?.map {
            newTvSeriesSearchResponse(it.bookName ?: "", it.bookId ?: "") { this.posterUrl = it.coverWap }
        } ?: throw ErrorLoadingException("Gagal memuat Beranda")

        return newHomePageResponse(listOf(HomePageList("DramaBox Trending", items)))
    }

    // ==========================================
    // PENCARIAN
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val timestamp = System.currentTimeMillis().toString()
        val payload = mapOf("keyword" to query, "pageNo" to 1, "pageSize" to 20, "sortType" to 1, "synSwitch" to 1)
        val sn = generateSn(timestamp, payload.toJson().replace(" ", ""))

        val response = app.post("$mainUrl/drama-box/search/search?timestamp=$timestamp",
            headers = getAppHeaders(timestamp, sn), json = payload).parsedSafe<SearchApiRes>()

        return response?.data?.searchList?.map {
            newTvSeriesSearchResponse(it.bookName ?: "", it.bookId ?: "") { this.posterUrl = it.cover }
        } ?: emptyList()
    }

    // ==========================================
    // DETAIL & EPISODE
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        val bookId = url
        val timestamp = System.currentTimeMillis().toString()
        
        val payload = mapOf(
            "boundaryIndex" to 0, 
            "index" to -1, 
            "currencyPlaySource" to "jmtj",
            "needEndRecommend" to 0, 
            "currencyPlaySourceName" to "剧末推荐",
            "preLoad" to false, 
            "rid" to "",
            "pullCid" to "",
            "loadDirection" to 0, 
            "startUpKey" to "76892858-3e57-40b1-80cd-bfe098991909",
            "bookId" to bookId
        )
        val sn = generateSn(timestamp, payload.toJson().replace(" ", ""))

        val response = app.post("$mainUrl/drama-box/chapterv2/batch/load?timestamp=$timestamp", 
            headers = getAppHeaders(timestamp, sn), json = payload).parsedSafe<BatchLoadRes>()
            ?: throw ErrorLoadingException("Gagal Memuat Episode")
        
        val data = response.data ?: throw ErrorLoadingException("Data Episode Kosong")

        val episodes = data.chapterList?.map { chapter ->
            val bestVideo = chapter.cdnList?.firstOrNull()?.videoPathList?.maxByOrNull { it.quality ?: 0 }
            val epData = EpisodeData(bookId, chapter.chapterId ?: "", bestVideo?.videoPath ?: "")
            
            newEpisode(epData.toJson()) {
                this.name = chapter.chapterName
                this.episode = (chapter.chapterIndex ?: 0) + 1
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(data.bookName ?: "", bookId, TvType.TvSeries, episodes) {
            this.posterUrl = data.bookCover
            this.plot = data.introduction
            this.tags = data.tags
        }
    }

    // ==========================================
    // PEMUTAR VIDEO (UNLOCK & PLAY)
    // ==========================================
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parsed = parseJson<EpisodeData>(data)
        val timestamp = System.currentTimeMillis().toString()
        
        // Tembak API Unlock agar terhitung VIP
        val unlockPayload = mapOf(
            "bookId" to parsed.bookId, 
            "chapterId" to parsed.chapterId, 
            "vip" to true, 
            "unLockType" to 1, 
            "confirmPay" to true, 
            "autoPay" to true
        )
        val sn = generateSn(timestamp, unlockPayload.toJson().replace(" ", ""))
        app.post("$mainUrl/drama-box/chapterv2/unlock?timestamp=$timestamp", headers = getAppHeaders(timestamp, sn), json = unlockPayload)

        // Penulisan newExtractorLink yang benar sesuai API CloudStream terbaru
        callback.invoke(
            newExtractorLink(
                source = "DramaBox", 
                name = "DramaBox VIP", 
                url = parsed.videoUrl, 
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }

    // ==========================================
    // KELAS BANTUAN JSON
    // ==========================================
    data class EpisodeData(val bookId: String, val chapterId: String, val videoUrl: String)
    data class TheaterApiRes(val data: TheaterData?)
    data class TheaterData(val columnVoList: List<ColumnVo>?)
    data class ColumnVo(val bookList: List<BookItem>?)
    data class BookItem(val bookId: String?, val bookName: String?, val coverWap: String?)
    data class SearchApiRes(val data: SearchData?)
    data class SearchData(val searchList: List<SearchItem>?)
    data class SearchItem(val bookId: String?, val bookName: String?, val cover: String?)
    data class BatchLoadRes(val data: BatchLoadData?)
    data class BatchLoadData(val bookName: String?, val bookCover: String?, val introduction: String?, val tags: List<String>?, val chapterList: List<Chapter>?)
    data class Chapter(val chapterId: String?, val chapterIndex: Int?, val chapterName: String?, val cdnList: List<Cdn>?)
    data class Cdn(val videoPathList: List<VideoPath>?)
    data class VideoPath(val quality: Int?, val videoPath: String?)
}
