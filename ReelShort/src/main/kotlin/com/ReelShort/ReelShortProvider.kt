package com.ReelShort

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ==========================================
// 📦 CETAKAN DATA CLASS (SESUAI JSON ASLI)
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
    override var lang = "in" // Pakai "in" sesuai request Reqable lu
    override val supportedTypes = setOf(TvType.TvSeries)

    // HARTA KARUN GHIDRA: SALT SHA-256
    private val SIGN_SALT = "6a508f8a81314c65" 

    // FUNGSI SAKTI PEMBUAT TIKET MASUK (SIGN GENERATOR)
    private fun generateSign(ts: String): String {
        // Logika umum: Menggabungkan timestamp + uid + salt (Bisa disesuaikan kalau api butuh parameter lain)
        val input = "ts=${ts}uid=809046271" + SIGN_SALT
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // FUNGSI SAKTI PEMBONGKAR GEMBOK AES (DARI MT MANAGER)
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
        val url = "$mainUrl/api/ms/hall/infoV4"
        
        val body = mapOf(
            "abtest_group" to "newHall",
            "is_first_req" to "0",
            "widescreen" to "0",
            "no_continue_watch" to "1",
            "current_level1_tab_id" to "44421",
            "current_level2_tab_id" to "0",
            "action_type" to "100"
        )

        // Bikin Waktu Otomatis (Format Detik)
        val currentTs = (System.currentTimeMillis() / 1000).toString()
        // Bikin Sign Otomatis pakai Salt Ghidra
        val dynamicSign = generateSign(currentTs)

        val res = app.post(
            url = url,
            data = body,
            headers = mapOf(
                "clientver" to "3.8.00",
                "lang" to "in",
                "uid" to "809046271",
                "ts" to currentTs, 
                // Jika server menolak dynamicSign kita, sementara bisa pakai sign hardcoded lu kemarin buat testing aja
                "sign" to "d50f6adff61cd67b4da656e59befd367b7530161b60bb2b79e3c55ea9d85cb58", 
                "user-agent" to "okhttp/4.11.0"
            )
        ).text

        val response = tryParseJson<HallResponse>(res)
        val items = mutableListOf<HomePageList>()

        response?.data?.lists?.forEachIndexed { index, list ->
            val innerItems = mutableListOf<SearchResponse>()
            val listName = list.title ?: "Rekomendasi ${index + 1}"

            // Ambil dari keranjang 'books'
            list.books?.forEach { book ->
                if (book.bookTitle != null && book.bookId != null) {
                    innerItems.add(
                        newTvSeriesSearchResponse(book.bookTitle, book.bookId, TvType.TvSeries) {
                            this.posterUrl = book.bookPic
                        }
                    )
                }
            }

            // Ambil dari keranjang 'rank_list' (Trending)
            list.rankList?.forEach { book ->
                if (book.bookTitle != null && book.bookId != null) {
                    innerItems.add(
                        newTvSeriesSearchResponse(book.bookTitle, book.bookId, TvType.TvSeries) {
                            this.posterUrl = book.bookPic
                        }
                    )
                }
            }

            if (innerItems.isNotEmpty()) {
                items.add(HomePageList(listName, innerItems))
            }
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(items)
    }

    // 2. MENAMPILKAN DETAIL & DAFTAR EPISODE
    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = "$mainUrl/api/video/book/getBookDetailV2"
        val body = mapOf("book_id" to url, "from" to "0", "play_details" to "1")
        val currentTs = (System.currentTimeMillis() / 1000).toString()

        val res = app.post(
            url = apiUrl,
            data = body,
            headers = mapOf(
                "clientver" to "3.8.00",
                "lang" to "in",
                "uid" to "809046271",
                "user-agent" to "okhttp/4.11.0",
                "ts" to currentTs, 
                "sign" to "1d5243b95fc83e677594c65b1bbf7ec4b45c87c32e34ff398e9d1303e47aa1a2" // Pakai hardcode dulu buat tes parsing berjalan mulus
            )
        ).text

        val response = tryParseJson<DetailResponse>(res)
        val retBook = response?.data?.retBook ?: return null
        
        val episodes = response.data.chapterList?.chapterLists?.mapNotNull { ep ->
            val chapId = ep.chapterId ?: return@mapNotNull null
            newEpisode(data = "$url||$chapId") {
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

    // 3. MENGAMBIL LINK VIDEO (.m3u8) & DEKRIPSI AES
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
            "is_hand_pay" to "0", "is_wait_free_unlock" to "0",
            "book_id" to parts[0], "chapter_id" to parts[1],
            "set_auto" to "1", "account_bind_from_player" to "0", "is_adv_unlock" to "0"
        )

        val currentTs = (System.currentTimeMillis() / 1000).toString()

        val res = app.post(
            url = apiUrl,
            data = body,
            headers = mapOf(
                "clientver" to "3.8.00",
                "lang" to "in",
                "uid" to "809046271",
                "user-agent" to "okhttp/4.11.0",
                "ts" to currentTs, 
                "sign" to "99378f58651f984ee19d93f22b6a334d0c9a297c0b323354a555ce2dfbf090f9" // Pakai hardcode dulu buat tes parsing berjalan mulus
            )
        ).text

        val response = tryParseJson<ChapterContentResponse>(res)
        val playInfoEncrypted = response?.data?.playInfo ?: return false

        // Momen Kebenaran: Dekripsi menggunakan kunci AES!
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
