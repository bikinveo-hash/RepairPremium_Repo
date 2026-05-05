package com.freereels

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FreeReels : MainAPI() {
    override var mainUrl = "https://free-reels.com"
    private val nativeApiUrl = "https://apiv2.free-reels.com/frv2-api"

    override var name = "FreeReels"
    override var lang = "id"
    
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "popular" to "Populer",
        "new" to "New",
        "coming_soon" to "Segera Hadir",
        "dubbing" to "Dubbing",
        "female" to "Perempuan",
        "male" to "Laki-Laki",
        "anime" to "Anime"
    )

    private val cryptoKey = "2r36789f45q01ae5"

    // ==========================================
    // 1. FUNGSI HALAMAN UTAMA
    // ==========================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val category = request.data
        val url = "$nativeApiUrl/drama/list?type=$category&page=$page"

        val response = app.get(url).text 
        val homeItems = arrayListOf<SearchResponse>()
        
        // Catatan bro: Parsing JSON dari API untuk halaman utama 
        // harus ditambahkan di sini sesuai struktur API asli FreeReels.
        // Contoh:
        // val parsedData = parseJson<SearchApiResponse>(response)
        // parsedData.data?.forEach { item -> ... homeItems.add(...) }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name, 
                list = homeItems, 
                isHorizontalImages = false
            ),
            hasNext = true 
        )
    }

    // ==========================================
    // 2. FUNGSI PENCARIAN
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$nativeApiUrl/drama/search?keyword=$query"
        val response = app.get(url).text
        val searchResult = parseJson<SearchApiResponse>(response)

        return searchResult.data?.mapNotNull { item ->
            newTvSeriesSearchResponse(
                name = item.title ?: "",
                url = item.id.toString(), 
                type = TvType.AsianDrama
            ) {
                this.posterUrl = item.cover
            }
        } ?: emptyList() 
    }

    // ==========================================
    // 3. FUNGSI DETAIL FILM
    // ==========================================
    override suspend fun load(url: String): LoadResponse {
        // url di sini berisi ID drama yang diklik
        val apiUrl = "$nativeApiUrl/drama/detail?id=$url"
        val response = app.get(apiUrl).text
        
        val data = parseJson<DramaDetailResponse>(response)
        val detail = data.data

        val episodes = detail.episodes?.map { ep ->
            Episode(
                data = ep.id.toString(), // ID ini dikirim ke loadLinks
                name = "Episode ${ep.episodeNumber}",
                episode = ep.episodeNumber,
                posterUrl = ep.thumbnail
            )
        } ?: emptyList()

        return newTvSeriesLoadResponse(
            name = detail.title ?: "",
            url = url,
            type = TvType.AsianDrama,
            episodeList = episodes
        ) {
            this.posterUrl = detail.cover
            this.plot = detail.description
            this.year = detail.year
            this.showStatus = if (detail.status == 1) ShowStatus.Ongoing else ShowStatus.Completed
            this.tags = detail.genres?.map { it.name }
        }
    }

    // ==========================================
    // 4. FUNGSI AMBIL LINK VIDEO & DEKRIPSI
    // ==========================================
    private fun decrypt(encryptedText: String): String {
        val decoded = Base64.decode(encryptedText, Base64.DEFAULT)
        val iv = decoded.copyOfRange(0, 16)
        val payload = decoded.copyOfRange(16, decoded.size)
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cryptoKey.toByteArray(), "AES"), IvParameterSpec(iv))
        
        return String(cipher.doFinal(payload))
    }

    private fun decryptIfNeeded(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{") || text.startsWith("[")) {
            return text
        }
        return try {
            decrypt(text)
        } catch (e: Exception) {
            text 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = "$nativeApiUrl/episode/links?id=$data" 
        val response = app.get(url).text
        val decryptedJson = decryptIfNeeded(response)
        
        val videoData = parseJson<VideoResponse>(decryptedJson)
        val videoUrl = videoData.url
        
        if (videoUrl != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    referer = mainUrl, 
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }

        videoData.subtitles?.forEach { sub ->
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = sub.language ?: "id",
                    url = sub.url ?: ""
                )
            )
        }
        return true
    }
}

// ==========================================
// 5. KERANGKA DATA (MODEL JSON)
// ==========================================

data class SearchApiResponse(
    val data: List<SearchItem>?
)

data class SearchItem(
    val id: Int?,
    val title: String?,
    val cover: String?
)

data class DramaDetailResponse(
    val data: DramaDetail
)

data class DramaDetail(
    val title: String?,
    val description: String?,
    val cover: String?,
    val year: Int?,
    val status: Int?,
    val episodes: List<EpisodeInfo>?,
    val genres: List<GenreInfo>?
)

data class EpisodeInfo(
    val id: Int,
    val episodeNumber: Int,
    val thumbnail: String?
)

data class GenreInfo(
    val name: String
)

data class VideoResponse(
    val url: String?,
    val subtitles: List<SubtitleInfo>?
)

data class SubtitleInfo(
    val language: String?,
    val url: String?
)
