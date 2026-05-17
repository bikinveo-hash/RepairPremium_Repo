package com.ReelShort

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ==========================================
// 📦 CETAKAN DATA CLASS
// ==========================================

data class HallResponse(@JsonProperty("data") val data: HallData?)
data class HallData(@JsonProperty("lists") val lists: List<HallList>?)
data class HallList(
    @JsonProperty("title") val title: String?,
    @JsonProperty("books") val books: List<HallBook>?,
    @JsonProperty("rank_list") val rankList: List<HallBook>?
)
data class HallBook(
    @JsonProperty("book_id") val bookId: String?,
    @JsonProperty("book_title") val bookTitle: String?,
    @JsonProperty("book_pic") val bookPic: String?
)

data class SearchDefaultResponse(@JsonProperty("data") val data: SearchDefaultData?)
data class SearchDefaultData(@JsonProperty("book_rank_data") val bookRankData: List<HallBook>?)

data class SearchRsResponse(@JsonProperty("data") val data: SearchRsData?)
data class SearchRsData(@JsonProperty("lists") val lists: List<HallBook>?)

data class DetailResponse(@JsonProperty("data") val data: DetailData?)
data class DetailData(
    @JsonProperty("retBook") val retBook: RetBook?,
    @JsonProperty("chapterList") val chapterList: ChapterListObj?
)
data class RetBook(
    @JsonProperty("book_title") val bookTitle: String?,
    @JsonProperty("book_pic") val bookPic: String?,
    @JsonProperty("special_desc") val specialDesc: String?,
    @JsonProperty("tag") val tag: List<String>?
)
data class ChapterListObj(@JsonProperty("chapter_lists") val chapterLists: List<ChapterItem>?)
data class ChapterItem(
    @JsonProperty("chapter_id") val chapterId: String?,
    @JsonProperty("chapter_name") val chapterName: String?,
    @JsonProperty("video_pic") val videoPic: String?,
    @JsonProperty("serial_number") val serialNumber: Int?
)

data class ChapterContentResponse(@JsonProperty("data") val data: ChapterContentData?)
data class ChapterContentData(@JsonProperty("play_info") val playInfo: String?)

// ==========================================
// 🚀 KODE UTAMA PROVIDER CLOUDSTREAM
// ==========================================

class ReelShortProvider : MainAPI() {
    override var name = "ReelShort"
    override var mainUrl = "https://v-api.crazymaplestudios.com"
    override val hasMainPage = true
    override var lang = "in" 
    override val supportedTypes = setOf(TvType.TvSeries)

    // 🔥 HARTA KARUN GHIDRA: SALT SHA-256
    private val SIGN_SALT = "6a508f8a81314c65" 

    // 🔥 FUNGSI PEMBUAT TIKET OTOMATIS (Abjad A-Z)
    private fun generateSign(params: Map<String, String>): String {
        val sortedKeys = params.keys.sorted()
        val sb = StringBuilder()
        for (key in sortedKeys) {
            val value = params[key]
            if (!value.isNullOrEmpty()) {
                sb.append(key).append("=").append(value).append("&")
            }
        }
        var signString = sb.toString()
        if (signString.endsWith("&")) {
            signString = signString.dropLast(1)
        }
        val input = signString + SIGN_SALT
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // 🔥 FUNGSI PEMBONGKAR GEMBOK AES VIDEO
    private fun decryptPlayInfo(encryptedBase64: String): String {
        return try {
            val key = SecretKeySpec("jlcVUHH9XgmYlfsK".toByteArray(), "AES")
            val iv = IvParameterSpec("fOEZ9V4a3VaniWAa".toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decodedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8).replace("\"", "")
        } catch (e: Exception) {
            ""
        }
    }

    // ================== HELPER REQUEST ==================
    private suspend fun postWithSign(url: String, body: Map<String, String>): String {
        val currentTs = (System.currentTimeMillis() / 1000).toString()
        val clientTraceId = currentTs + (1000000..9999999).random().toString()
        
        // PAKAI REQUEST TIME & SESSION TERBARU LU HARI INI
        val requestTime = "XIEsgk5qm9LL1gJXM5Fwzk97GLUnRa1yn/5fTt+hucRlgcRDH+lRK36S5Y63RiEKOT374fOpWDE/5xvHK11odxqq9ia9KH+ECCNi0Vejvmo="
        val session = "89e92d91b0b2c17ea4007fdb6d1ea63f"

        val signParams = mutableMapOf(
            "uid" to "809046271",
            "channelId" to "AVG10003",
            "ts" to currentTs,
            "apiVersion" to "1.4.14",
            "session" to session,
            "lang" to "in",
            "devId" to "b0c9622df6e963d9",
            "clientVer" to "3.8.00",
            "clientTraceId" to clientTraceId,
            "requestTime" to requestTime
        )
        signParams.putAll(body)
        
        val dynamicSign = generateSign(signParams)

        val headers = mapOf(
            "uid" to "809046271",
            "channelid" to "AVG10003",
            "ts" to currentTs,
            "apiversion" to "1.4.14",
            "session" to session,
            "lang" to "in",
            "devid" to "b0c9622df6e963d9",
            "clientver" to "3.8.00",
            "clienttraceid" to clientTraceId,
            "requesttime" to requestTime,
            "sign" to dynamicSign,
            "user-agent" to "okhttp/4.11.0"
        )

        return app.post(url = url, data = body, headers = headers).text
    }

    // 1. MENAMPILKAN HALAMAN BERANDA (HOME PAGE)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl/api/ms/hall/infoV4"
        
        // Parameter body ini gue samain persis 100% sama Reqable lu hari ini
        val body = mapOf(
            "abtest_group" to "newHall", "is_first_req" to "0", "widescreen" to "0",
            "no_continue_watch" to "1", "current_level1_tab_id" to "44422", "current_level2_tab_id" to "0",
            "current_tag_id" to "", "tabs_md5" to "BRPoJKgbEFJsRTwxRUXYvA==", "tab_md5" to "aKg+ug0m91kD3cXjAlKT/w==", "action_type" to "1"
        )

        val res = postWithSign(url, body)
        Log.d("ReelShort", "Response Home: " + res.take(200)) // RADAR DEBUG
        
        val response = tryParseJson<HallResponse>(res)
        val items = mutableListOf<HomePageList>()

        if (response?.data?.lists == null) {
            val debugList = mutableListOf<SearchResponse>()
            debugList.add(newTvSeriesSearchResponse("Error Home: $res", "debug", TvType.TvSeries) { this.posterUrl = "" })
            items.add(HomePageList("⚠️ SERVER MENOLAK", debugList))
            return newHomePageResponse(items)
        }

        response.data.lists.forEachIndexed { index, list ->
            val innerItems = mutableListOf<SearchResponse>()
            val listName = list.title ?: list.books?.firstOrNull()?.bookTitle?.take(10) ?: "Rekomendasi ${index + 1}"

            list.books?.forEach { book ->
                if (book.bookTitle != null && book.bookId != null) {
                    innerItems.add(newTvSeriesSearchResponse(book.bookTitle, book.bookId, TvType.TvSeries) { this.posterUrl = book.bookPic })
                }
            }
            if (innerItems.isNotEmpty()) items.add(HomePageList(listName, innerItems))
        }
        return newHomePageResponse(items)
    }

    // 2. FUNGSI PENCARIAN FILM
    override suspend fun search(query: String): List<SearchResponse> {
        val searchItems = mutableListOf<SearchResponse>()
        if (query.isBlank()) {
            val apiUrl = "$mainUrl/api/video/search/getSearchDefault"
            val res = postWithSign(apiUrl, emptyMap())
            val response = tryParseJson<SearchDefaultResponse>(res)
            response?.data?.bookRankData?.forEach { book ->
                if (book.bookTitle != null && book.bookId != null) {
                    searchItems.add(newTvSeriesSearchResponse(book.bookTitle, book.bookId, TvType.TvSeries) { this.posterUrl = book.bookPic })
                }
            }
        } else {
            val apiUrl = "$mainUrl/api/video/search/search"
            val body = mapOf("word" to query, "page" to "1", "limit" to "20")
            val res = postWithSign(apiUrl, body)
            val response = tryParseJson<SearchRsResponse>(res)
            response?.data?.lists?.forEach { book ->
                if (book.bookTitle != null && book.bookId != null) {
                    searchItems.add(newTvSeriesSearchResponse(book.bookTitle, book.bookId, TvType.TvSeries) { this.posterUrl = book.bookPic })
                }
            }
        }
        return searchItems
    }

    // 3. MENAMPILKAN DETAIL & DAFTAR EPISODE
    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = "$mainUrl/api/video/book/getBookDetailV2"
        val body = mapOf("book_id" to url, "from" to "0", "play_details" to "1")
        val res = postWithSign(apiUrl, body)
        
        Log.d("ReelShort", "Response Detail: " + res.take(200)) // RADAR DEBUG

        val response = tryParseJson<DetailResponse>(res)
        if (response?.data?.retBook == null) throw Error("Server Nolak Detail: " + res.take(100))

        val retBook = response.data.retBook
        val episodes = response.data.chapterList?.chapterLists?.mapNotNull { ep ->
            val chapId = ep.chapterId ?: return@mapNotNull null
            newEpisode(data = "$url||$chapId") {
                this.name = ep.chapterName
                this.posterUrl = ep.videoPic
                this.episode = ep.serialNumber
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(
            name = retBook.bookTitle ?: "Unknown",
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = retBook.bookPic
            this.plot = retBook.specialDesc
            this.tags = retBook.tag
        }
    }

    // 4. MENGAMBIL LINK VIDEO (.m3u8) & DEKRIPSI AES
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("||")
        if (parts.size < 2) return false
        
        val apiUrl = "$mainUrl/api/video/book/getChapterContent"
        val body = mapOf(
            "is_hand_pay" to "0", "is_wait_free_unlock" to "0", "book_id" to parts[0], 
            "chapter_id" to parts[1], "set_auto" to "0", "account_bind_from_player" to "0", "is_adv_unlock" to "0"
        )

        val res = postWithSign(apiUrl, body)
        Log.d("ReelShort", "Response Video: " + res.take(200)) // RADAR DEBUG

        val response = tryParseJson<ChapterContentResponse>(res)
        val playInfoEncrypted = response?.data?.playInfo ?: return false

        val m3u8Url = decryptPlayInfo(playInfoEncrypted)
        if (m3u8Url.isBlank()) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "ReelShort HD",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P720.value
            }
        )
        return true
    }
}
