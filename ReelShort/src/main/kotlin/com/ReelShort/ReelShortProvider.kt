package com.ReelShort

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.* import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class ReelShortProvider : MainAPI() {
    override var mainUrl = "https://www.reelshort.com" 
    override var name = "ReelShort"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ==========================================
    // 🕵️‍♂️ HARTA KARUN REVERSE ENGINEERING
    // ==========================================
    private val AES_KEY = "jlcVUHH9XgmYlfsK"
    private val SIGN_SALT = "6a508f8a81314c65" 

    private fun generateSign(params: String): String {
        val input = params + SIGN_SALT
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun decryptContent(encryptedBase64: String): String {
        return try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding") 
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // ==========================================
    // 📦 DATA CLASSES (Struktur JSON Umum)
    // ==========================================
    data class RsResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("data") val data: Any?, 
        @JsonProperty("msg") val msg: String?
    )

    data class RsBook(
        @JsonProperty("book_id") val bookId: String?,
        @JsonProperty("book_name") val bookName: String?,
        @JsonProperty("cover_url") val coverUrl: String?,
        @JsonProperty("intro") val intro: String?
    )

    data class RsChapter(
        @JsonProperty("chapter_id") val chapterId: String?,
        @JsonProperty("chapter_name") val chapterName: String?,
        @JsonProperty("chapter_index") val chapterIndex: Int?
    )

    data class RsVideoData(
        @JsonProperty("video_url") val videoUrl: String?,
        @JsonProperty("play_url") val playUrl: String?
    )

    // ==========================================
    // 🚀 IMPLEMENTASI CLOUDSTREAM TERBARU
    // ==========================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val timestamp = System.currentTimeMillis().toString()
        val rawParams = "lang=id&page=$page&ts=$timestamp"
        val sign = generateSign(rawParams)
        
        val url = "$mainUrl/api/v1/getRecommendBook?$rawParams&sign=$sign"
        val response = app.get(url).text
        
        val items = ArrayList<HomePageList>()
        val homeItems = ArrayList<SearchResponse>()
        
        tryParseJson<RsResponse>(response)?.let { res ->
            val jsonString = res.data?.toJson() ?: ""
            val books = tryParseJson<List<RsBook>>(jsonString) 
            
            books?.forEach { book ->
                homeItems.add(
                    newTvSeriesSearchResponse(
                        book.bookName ?: "Unknown", 
                        book.bookId ?: "", 
                        TvType.TvSeries
                    ) {
                        this.posterUrl = book.coverUrl
                    }
                )
            }
        }
        
        if (homeItems.isNotEmpty()) {
            items.add(HomePageList("Rekomendasi", homeItems))
        }
        
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val timestamp = System.currentTimeMillis().toString()
        val rawParams = "keyword=$query&lang=id&ts=$timestamp"
        val sign = generateSign(rawParams)
        
        val url = "$mainUrl/api/v1/search.json?$rawParams&sign=$sign"
        val response = app.get(url).text
        val searchList = ArrayList<SearchResponse>()
        
        tryParseJson<RsResponse>(response)?.let { res ->
            val jsonString = res.data?.toJson() ?: ""
            val books = tryParseJson<List<RsBook>>(jsonString)
            books?.forEach { book ->
                searchList.add(
                    newTvSeriesSearchResponse(
                        book.bookName ?: "Unknown", 
                        book.bookId ?: "", 
                        TvType.TvSeries
                    ) {
                        this.posterUrl = book.coverUrl
                    }
                )
            }
        }
        return searchList
    }

    override suspend fun load(url: String): LoadResponse? {
        val timestamp = System.currentTimeMillis().toString()
        val rawParams = "book_id=$url&lang=id&ts=$timestamp"
        val sign = generateSign(rawParams)
        
        val apiUrl = "$mainUrl/api/v1/getChapterList?$rawParams&sign=$sign"
        val response = app.get(apiUrl).text
        
        val episodes = ArrayList<Episode>()
        
        tryParseJson<RsResponse>(response)?.let { res ->
            val jsonString = res.data?.toJson() ?: ""
            val chapters = tryParseJson<List<RsChapter>>(jsonString)
            
            chapters?.forEach { ch ->
                episodes.add(
                    newEpisode(ch.chapterId ?: "") {
                        this.name = ch.chapterName ?: "Episode ${ch.chapterIndex}"
                        this.episode = ch.chapterIndex
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(
            "ReelShort Drama", 
            url, 
            TvType.TvSeries, 
            episodes
        ) {
            this.posterUrl = ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val timestamp = System.currentTimeMillis().toString()
        val rawParams = "chapter_id=$data&lang=id&ts=$timestamp"
        val sign = generateSign(rawParams)
        
        val apiUrl = "$mainUrl/api/v1/getChapterContent?$rawParams&sign=$sign"
        val response = app.get(apiUrl).text
        
        tryParseJson<RsResponse>(response)?.let { res ->
            val encryptedData = res.data.toString()
            val decryptedJson = decryptContent(encryptedData)
            
            tryParseJson<RsVideoData>(decryptedJson)?.let { videoData ->
                val videoUrl = videoData.playUrl ?: videoData.videoUrl ?: return false
                
                // 👈 INI YANG BARU: Langsung masukin nilainya sesuai urutan (tanpa nama parameter)
                callback.invoke(
                    newExtractorLink(
                        this.name,                  // source
                        this.name,                  // name
                        videoUrl,                   // url
                        mainUrl,                    // referer
                        Qualities.P720.value,       // quality
                        videoUrl.contains(".m3u8")  // isM3u8
                    )
                )
            }
        }
        return true
    }
}
