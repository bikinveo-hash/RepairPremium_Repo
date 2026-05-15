package com.ReelShort

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ==========================================
// CETAKAN DATA CLASS (UNTUK MEMBACA JSON)
// ==========================================

data class HallResponse(@JsonProperty("data") val data: HallData?)
data class HallData(@JsonProperty("lists") val lists: List<HallList>?)
data class HallList(@JsonProperty("books") val books: List<HallBook>?)
data class HallBook(
    @JsonProperty("book_id") val bookId: String?,
    @JsonProperty("book_title") val bookTitle: String?,
    @JsonProperty("book_pic") val bookPic: String?
)

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
// KODE UTAMA PROVIDER
// ==========================================

class ReelShortProvider : MainAPI() {
    override var name = "ReelShort"
    override var mainUrl = "https://v-api.crazymaplestudios.com"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.TvSeries)

    // FUNGSI SAKTI PEMBONGKAR GEMBOK AES REELSHORT
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

    // 1. MENAMPILKAN HALAMAN BERANDA
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl/api/ms/hall/infoV4"
        
        val body = mapOf(
            "abtest_group" to "newHall",
            "is_first_req" to "0",
            "widescreen" to "0",
            "no_continue_watch" to "1",
            "current_level1_tab_id" to "44421",
            "current_level2_tab_id" to "0",
            "action_type" to "1"
        )

        // Menggunakan metode .text lalu tryParseJson untuk menghindari error parsedSafe
        val res = app.post(
            url = url,
            data = body,
            headers = mapOf(
                "clientver" to "3.8.00",
                "lang" to "in",
                "uid" to "809046271",
                "ts" to "1778882859", 
                "sign" to "05cdc786050ebeba31836c403f1c27a31f8724cf44f626f5c997310b83947424", 
                "user-agent" to "okhttp/4.11.0"
            )
        ).text

        val response = tryParseJson<HallResponse>(res)
        val items = mutableListOf<SearchResponse>()

        response?.data?.lists?.forEach { list ->
            list.books?.forEach { book ->
                if (book.bookTitle != null && book.bookId != null) {
                    val show = newTvSeriesSearchResponse(
                        name = book.bookTitle,
                        url = book.bookId
                    ) {
                        this.posterUrl = book.bookPic
                    }
                    items.add(show)
                }
            }
        }

        if (items.isEmpty()) return null

        return newHomePageResponse(
            list = HomePageList("ReelShort Populer", items),
            hasNext = false
        )
    }

    // 2. MENAMPILKAN DETAIL & DAFTAR EPISODE
    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = "$mainUrl/api/video/book/getBookDetailV2"
        
        val body = mapOf(
            "book_id" to url,
            "from" to "0",
            "play_details" to "1"
        )

        val res = app.post(
            url = apiUrl,
            data = body,
            headers = mapOf(
                "clientver" to "3.8.00",
                "lang" to "in",
                "uid" to "809046271",
                "user-agent" to "okhttp/4.11.0",
                "ts" to "1778884289", 
                "sign" to "1d5243b95fc83e677594c65b1bbf7ec4b45c87c32e34ff398e9d1303e47aa1a2" 
            )
        ).text

        val response = tryParseJson<DetailResponse>(res)
        val retBook = response?.data?.retBook ?: return null
        
        val episodes = response.data.chapterList?.chapterLists?.mapNotNull { ep ->
            val chapId = ep.chapterId ?: return@mapNotNull null
            
            newEpisode(
                data = "$url||$chapId"
            ) {
                this.name = ep.chapterName
                this.posterUrl = ep.videoPic
                this.episode = ep.serialNumber
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(
            name = retBook.bookTitle ?: "",
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl = retBook.bookPic
            this.plot = retBook.specialDesc
            this.tags = retBook.tag
        }
    }

    // 3. MENGAMBIL LINK VIDEO (.m3u8)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("||")
        if (parts.size < 2) return false
        val bookId = parts[0]
        val chapterId = parts[1]

        val apiUrl = "$mainUrl/api/video/book/getChapterContent"
        val body = mapOf(
            "is_hand_pay" to "0",
            "is_wait_free_unlock" to "0",
            "book_id" to bookId,
            "chapter_id" to chapterId,
            "set_auto" to "1",
            "account_bind_from_player" to "0",
            "is_adv_unlock" to "0"
        )

        val res = app.post(
            url = apiUrl,
            data = body,
            headers = mapOf(
                "clientver" to "3.8.00",
                "lang" to "in",
                "uid" to "809046271",
                "user-agent" to "okhttp/4.11.0",
                "ts" to "1778884634", 
                "sign" to "99378f58651f984ee19d93f22b6a334d0c9a297c0b323354a555ce2dfbf090f9" 
            )
        ).text

        val response = tryParseJson<ChapterContentResponse>(res)
        val playInfoEncrypted = response?.data?.playInfo ?: return false

        // Dekripsi menggunakan AES
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
