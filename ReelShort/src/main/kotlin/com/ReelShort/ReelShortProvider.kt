package com.ReelShort

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// --- DATA CLASSES ---
data class HallResponse(@JsonProperty("data") val data: HallData?)
data class HallData(@JsonProperty("lists") val lists: List<HallList>?)
data class HallList(@JsonProperty("books") val books: List<HallBook>?)
data class HallBook(@JsonProperty("book_id") val bookId: String?, @JsonProperty("book_title") val bookTitle: String?, @JsonProperty("book_pic") val bookPic: String?)
data class DetailResponse(@JsonProperty("data") val data: DetailData?)
data class DetailData(@JsonProperty("retBook") val retBook: RetBook?, @JsonProperty("chapterList") val chapterList: ChapterListObj?)
data class RetBook(@JsonProperty("book_title") val bookTitle: String?, @JsonProperty("book_pic") val bookPic: String?, @JsonProperty("special_desc") val specialDesc: String?, @JsonProperty("tag") val tag: List<String>?)
data class ChapterListObj(@JsonProperty("chapter_lists") val chapterLists: List<ChapterItem>?)
data class ChapterItem(@JsonProperty("chapter_id") val chapterId: String?, @JsonProperty("chapter_name") val chapterName: String?, @JsonProperty("video_pic") val videoPic: String?, @JsonProperty("serial_number") val serialNumber: Int?)
data class ChapterContentResponse(@JsonProperty("data") val data: ChapterContentData?)
data class ChapterContentData(@JsonProperty("play_info") val playInfo: String?)

class ReelShortProvider : MainAPI() {
    override var name = "ReelShort"
    override var mainUrl = "https://v-api.crazymaplestudios.com"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.TvSeries)

    // --- FUNGSI KRIPTOGRAFI (DARI HASIL BONGKAR APK) ---

    private fun decryptPlayInfo(encrypted: String): String {
        return try {
            val key = SecretKeySpec("jlcVUHH9XgmYlfsK".toByteArray(), "AES")
            val iv = IvParameterSpec("fOEZ9V4a3VaniWAa".toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decoded = Base64.decode(encrypted, Base64.DEFAULT)
            String(cipher.doFinal(decoded)).replace("\"", "")
        } catch (e: Exception) { "" }
    }

    // Fungsi untuk membuat tanda tangan digital (Sign) otomatis
    private fun generateSign(params: Map<String, String>): String {
        val sortedString = params.toSortedMap().map { "${it.key}=${it.value}" }.joinToString("&")
        return MessageDigest.getInstance("SHA-256")
            .digest(sortedString.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun getVictorHeaders(extraParams: Map<String, String>): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val headers = mutableMapOf(
            "uid" to "809046271",
            "channelId" to "AVG10003",
            "ts" to ts,
            "apiVersion" to "1.4.14",
            "lang" to "in",
            "clientVer" to "3.8.00",
            "user-agent" to "okhttp/4.11.0"
        )
        // Gabungkan parameter body dan header untuk membuat sign
        val signMap = headers.toMutableMap()
        signMap.putAll(extraParams)
        headers["sign"] = generateSign(signMap)
        return headers
    }

    // --- MAIN FUNCTIONS ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val body = mapOf(
            "current_level1_tab_id" to "44421",
            "action_type" to "1",
            "is_first_req" to "0"
        )
        val res = app.post("$mainUrl/api/ms/hall/infoV4", data = body, headers = getVictorHeaders(body)).text
        val response = tryParseJson<HallResponse>(res)
        val items = mutableListOf<SearchResponse>()

        response?.data?.lists?.forEach { list ->
            list.books?.forEach { book ->
                if (book.bookId != null) {
                    items.add(newTvSeriesSearchResponse(book.bookTitle ?: "", book.bookId) { this.posterUrl = book.bookPic })
                }
            }
        }
        return newHomePageResponse(HomePageList("Populer", items), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val body = mapOf("book_id" to url, "play_details" to "1")
        val res = app.post("$mainUrl/api/video/book/getBookDetailV2", data = body, headers = getVictorHeaders(body)).text
        val response = tryParseJson<DetailResponse>(res) ?: return null
        val episodes = response.data?.chapterList?.chapterLists?.map { ep ->
            newEpisode("$url||${ep.chapterId}") {
                this.name = ep.chapterName
                this.posterUrl = ep.videoPic
                this.episode = ep.serialNumber
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(response.data?.retBook?.bookTitle ?: "", url, TvType.TvSeries, episodes) {
            this.posterUrl = response.data?.retBook?.bookPic
            this.plot = response.data?.retBook?.specialDesc
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parts = data.split("||")
        val body = mapOf("book_id" to parts[0], "chapter_id" to parts[1])
        val res = app.post("$mainUrl/api/video/book/getChapterContent", data = body, headers = getVictorHeaders(body)).text
        val playInfo = tryParseJson<ChapterContentResponse>(res)?.data?.playInfo ?: return false
        val videoUrl = decryptPlayInfo(playInfo)

        if (videoUrl.isNotEmpty()) {
            callback.invoke(newExtractorLink(name, "ReelShort HD", videoUrl, ExtractorLinkType.M3U8) {
                this.quality = Qualities.P720.value
            })
            return true
        }
        return false
    }
}
