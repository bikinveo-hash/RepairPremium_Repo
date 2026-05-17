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

    // =======================================================
    // 🔥 MESIN PEMBUAT SIGNATURE DINAMIS (BYPASS 100007) 🔥
    // =======================================================
    private fun getSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getDynamicHeaders(body: Map<String, String>? = null): Map<String, String> {
        // 1. Bikin Timestamp waktu SEKARANG (10 digit / detik)
        val ts = (System.currentTimeMillis() / 1000).toString()
        val clientTraceId = ts + (100000..999999).random().toString()
        
        // 2. Urutkan parameter request dari A-Z (Wajib untuk Sign aplikasi Cina)
        val dataString = body?.entries?.sortedBy { it.key }?.joinToString("&") { "${it.key}=${it.value}" } ?: ""
        
        // 3. SALT SAKTI DARI GHIDRA
        val salt = "56jh5jsk98888888"
        
        // 4. Racik PlainText (Kita tes format paling umum: 1 + ts + data + salt)
        // Jika error, ubah line ini jadi: val plainText = "$dataString$salt"
        val plainText = "1${ts}${dataString}${salt}"
        
        // 5. Enkripsi jadi SHA-256
        val sign = getSha256(plainText)
        Log.d("ReelShort_Sign", "PlainText: $plainText | Hasil: $sign")

        return mapOf(
            "uid" to "809046271", 
            "channelid" to "AVG10003", 
            "ts" to ts, 
            "apiversion" to "1.4.14",
            "session" to "89e92d91b0b2c17ea4007fdb6d1ea63f", 
            "lang" to "in", 
            "devid" to "b0c9622df6e963d9",
            "clientver" to "3.8.00", 
            "clienttraceid" to clientTraceId,
            "sign" to sign, 
            "user-agent" to "okhttp/4.11.0"
            // requesttime sengaja dibuang biar server fokus ke 'ts' dan 'sign' baru kita
        )
    }

    // =======================================================
    // 🔥 FUNGSI PEMBONGKAR GEMBOK AES VIDEO
    // =======================================================
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

    // 1. MENAMPILKAN HALAMAN BERANDA (HOME PAGE)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "https://d1k8g7qaebqd28.cloudfront.net/api/ms/hall/infoV4"
        val body = mapOf(
            "abtest_group" to "newHall", "is_first_req" to "0", "widescreen" to "0",
            "no_continue_watch" to "1", "current_level1_tab_id" to "44423", "current_level2_tab_id" to "0",
            "current_tag_id" to "", "tabs_md5" to "BRPoJKgbEFJsRTwxRUXYvA==", "tab_md5" to "rVuHNANX2f9SVp+CxnlCWA==", "action_type" to "100"
        )
        
        // PANGGIL GENERATOR HEADER DINAMIS!
        val headers = getDynamicHeaders(body)

        val res = app.post(url = url, data = body, headers = headers).text
        val response = tryParseJson<HallResponse>(res)
        val items = mutableListOf<HomePageList>()

        if (response?.data?.lists == null) {
            val debugList = mutableListOf<SearchResponse>()
            debugList.add(newTvSeriesSearchResponse("Server Menolak Home: $res", "debug", TvType.TvSeries) { this.posterUrl = "" })
            items.add(HomePageList("⚠️ ERROR", debugList))
            return newHomePageResponse(items)
        }

        response.data.lists.forEachIndexed { index, list ->
            val innerItems = mutableListOf<SearchResponse>()
            val listName = list.title ?: list.books?.firstOrNull()?.bookTitle?.take(10) ?: "Trending ${index + 1}"

            list.books?.forEach { book ->
                if (book.bookTitle != null && book.bookId != null) {
                    innerItems.add(newTvSeriesSearchResponse(book.bookTitle, book.bookId, TvType.TvSeries) { this.posterUrl = book.bookPic })
                }
            }
            list.rankList?.forEach { book ->
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
            // PANGGIL GENERATOR HEADER (Kosongin parameter body)
            val headers = getDynamicHeaders(null)
            
            val res = app.post(url = apiUrl, headers = headers).text
            val response = tryParseJson<SearchDefaultResponse>(res)
            response?.data?.bookRankData?.forEach { book ->
                if (book.bookTitle != null && book.bookId != null) {
                    searchItems.add(newTvSeriesSearchResponse(book.bookTitle, book.bookId, TvType.TvSeries) { this.posterUrl = book.bookPic })
                }
            }
        } else {
            val apiUrl = "$mainUrl/api/video/search/search"
            val body = mapOf("word" to query, "page" to "1", "limit" to "20")
            // PANGGIL GENERATOR HEADER
            val headers = getDynamicHeaders(body)
            
            val res = app.post(url = apiUrl, data = body, headers = headers).text
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
        
        // PANGGIL GENERATOR HEADER
        val headers = getDynamicHeaders(body)
        
        val res = app.post(url = apiUrl, data = body, headers = headers).text
        val response = tryParseJson<DetailResponse>(res)
        if (response?.data?.retBook == null) throw Error("Server Nolak Detail: $res")

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
        
        // PANGGIL GENERATOR HEADER
        val headers = getDynamicHeaders(body)

        val res = app.post(url = apiUrl, data = body, headers = headers).text
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
