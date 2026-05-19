package com.LayarKacaProvider

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ==========================================
// DATA CLASSES ORIGINAL
// ==========================================

data class Lk21WatchData(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("rating") val rating: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("year") val year: Int?
)

data class Lk21Episode(
    @JsonProperty("s") val season: Int?,
    @JsonProperty("episode_no") val episode_no: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("slug") val slug: String?
)

data class Lk21SearchResponse(
    @JsonProperty("totalPages") val totalPages: Int?,
    @JsonProperty("data") val data: List<Lk21SearchItem>?
)

data class Lk21SearchItem(
    @JsonProperty("title") val title: String?,
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("quality") val quality: String?,
    @JsonProperty("rating") val rating: Any? 
)

// ==========================================
// DATA CLASSES BARU (SESUAI DENGAN DEKRIPSI ABYSS)
// ==========================================

data class AbyssPayload(
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("md5_id") val md5Id: Any?, // Menggunakan Any? agar angka JSON tidak menyebabkan crash
    @JsonProperty("user_id") val userId: Any?,
    @JsonProperty("media") val media: String?
)

data class DecryptedMediaData(
    @JsonProperty("sources") val sources: List<MediaSource>?,
    @JsonProperty("domains") val domains: List<String>?
)

data class DecryptedMedia(
    @JsonProperty("mp4") val mp4: DecryptedMediaData?,
    @JsonProperty("hls") val hls: DecryptedMediaData?
)

data class MediaSource(
    @JsonProperty("file") val file: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("path") val path: String?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("type") val type: String?
)

// ==========================================
// MAIN PROVIDER CLASS
// ==========================================

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://mamamas.xyz"
    override var name = "LK21"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "/latest-series" to "Series Terbaru",
        "/top-series-today" to "Series Unggulan",
        "/populer" to "Populer",
        "/nonton-bareng-keluarga" to "Nobar Keluarga",
        "/genre/action" to "Action",
        "/genre/horror" to "Horror"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}/page/$page"
        }

        val document = app.get(url).document

        val home = document.select("div.gallery-grid article").mapNotNull { element ->
            val title = element.selectFirst("h3.poster-title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val link = fixUrl(href)
            val poster = element.selectFirst("img[itemprop=image]")?.attr("src")
            val qualityStr = element.selectFirst("span.label")?.text() ?: ""
            val ratingStr = element.selectFirst("span[itemprop=ratingValue]")?.text()

            val isTvSeries = element.selectFirst("span.episode") != null

            if (isTvSeries) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    addQuality(qualityStr)
                    ratingStr?.let { this.score = Score.from(it, 10) }
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    addQuality(qualityStr)
                    ratingStr?.let { this.score = Score.from(it, 10) }
                }
            }
        }

        return newHomePageResponse(HomePageList(request.name, home, false), hasNext = true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchUrl = "https://gudangvape.com/search.php?s=$query&page=$page"
        
        val resText = app.get(
            url = searchUrl,
            headers = mapOf(
                "X-Requested-With" to "org.streaming.lk21official",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
        ).text
        
        val response = tryParseJson<Lk21SearchResponse>(resText) ?: return null

        val results = response.data?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val slug = item.slug ?: return@mapNotNull null
            val link = "$mainUrl/$slug"
            val posterUrl = item.poster?.let { "https://static-jpg.showcdnx.com/wp-content/uploads/$it" }
            
            if (item.type == "series") {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    addQuality(item.quality ?: "")
                    item.rating?.toString()?.let { this.score = Score.from(it, 10) }
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = posterUrl
                    addQuality(item.quality ?: "")
                    item.rating?.toString()?.let { this.score = Score.from(it, 10) }
                }
            }
        } ?: emptyList()

        return newSearchResponseList(results, hasNext = page < (response.totalPages ?: 1))
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val jsonString = document.selectFirst("script#watch-history-data")?.data()
        val watchData = jsonString?.let { tryParseJson<Lk21WatchData>(it) } ?: return null

        val plotText = document.selectFirst("div.synopsis")?.text()?.trim()
        val tagsList = document.select("div.tag-list span.tag a").map { it.text() }
        val seasonDataString = document.selectFirst("script#season-data")?.data()

        if (seasonDataString != null) {
            val seasonData = tryParseJson<Map<String, List<Lk21Episode>>>(seasonDataString)
            val episodes = mutableListOf<Episode>()

            seasonData?.forEach { (_, epsList) ->
                episodes.addAll(epsList.mapNotNull { ep ->
                    val epSlug = ep.slug ?: return@mapNotNull null
                    newEpisode("$mainUrl/$epSlug") {
                        this.name = ep.title
                        this.season = ep.season
                        this.episode = ep.episode_no
                    }
                })
            }

            return newTvSeriesLoadResponse(
                name = watchData.title ?: "",
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = watchData.poster
                this.year = watchData.year
                this.plot = plotText
                this.tags = tagsList
                watchData.rating?.let { this.score = Score.from(it, 10) }
            }
        } else {
            return newMovieLoadResponse(
                name = watchData.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = url 
            ) {
                this.posterUrl = watchData.poster
                this.year = watchData.year
                this.plot = plotText
                this.tags = tagsList
                watchData.rating?.let { this.score = Score.from(it, 10) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val serverElements = document.select("ul#player-list li a")
        val providerName = this.name
        
        // Gunakan USER_AGENT sistem aplikasi agar TLS fingerprint sinkron secara global
        val browserHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "X-Requested-With" to "org.streaming.lk21official",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        serverElements.forEach { element ->
            val serverName = element.attr("data-server").lowercase()
            val encryptedId = element.attr("data-url")   
            
            if (serverName.isNotBlank() && encryptedId.isNotBlank()) {
                val serverId = encryptedId 
                val iframePopupUrl = "https://playeriframe.sbs/mobile/$serverName/$serverId/embed"
                
                try {
                    Log.d("LK21_DEBUG", "Menghubungi popup: $iframePopupUrl")
                    val iframeResponse = app.get(iframePopupUrl, referer = data, headers = browserHeaders)
                    val realIframeSrc = iframeResponse.document.selectFirst("div.embed-container iframe")?.attr("src")
                    
                    if (!realIframeSrc.isNullOrEmpty()) {
                        Log.d("LK21_DEBUG", "Gerbang asli ditemukan: $realIframeSrc")
                        
                        // LANGKAH CRITICAL 1 (Persis seperti Python Hop 2):
                        // Dapatkan data redirect Location secara manual tanpa memicu auto-redirect dari OkHttp
                        val checkRedirectResponse = app.get(
                            url = realIframeSrc,
                            headers = browserHeaders,
                            allowRedirects = false
                        )

                        // Ambil target url redirect dari header manual
                        val redirectUrl = checkRedirectResponse.headers["Location"] ?: checkRedirectResponse.headers["location"]
                        
                        val finalHtmlText = if (!redirectUrl.isNullOrEmpty()) {
                            Log.d("LK21_DEBUG", "Mengakses CDN sesungguhnya: $redirectUrl")
                            
                            // LANGKAH CRITICAL 2 (Persis seperti Python Hop 3):
                            // Menembak CDN tujuan akhir dengan menyisipkan Header Referer secara paksa
                            val cdnHeaders = browserHeaders.toMutableMap()
                            cdnHeaders["Referer"] = "https://playeriframe.sbs/"
                            
                            app.get(
                                url = redirectUrl,
                                headers = cdnHeaders
                            ).text
                        } else {
                            Log.d("LK21_DEBUG", "Tidak ada redirect. Memproses teks html gerbang utama.")
                            checkRedirectResponse.text
                        }

                        // REGEX PENYARING DATA ENKRIPSI
                        val datasRegex = Regex("""const\s+datas\s*=\s*"([^"]+)"""")
                        val base64Data = datasRegex.find(finalHtmlText)?.groupValues?.get(1)
                        
                        if (base64Data != null) {
                            Log.d("LK21_DEBUG", "Payload Base64 berhasil disaring!")
                            
                            // Decode menggunakan Latin-1 (ISO_8859_1) agar byte biner media tidak rusak/corrupt
                            val decodedJsonString = String(
                                android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT), 
                                StandardCharsets.ISO_8859_1
                            )
                            val payload = tryParseJson<AbyssPayload>(decodedJsonString)
                            
                            if (payload != null) {
                                val decryptedJsonStr = decryptAbyssMedia(payload)
                                
                                if (decryptedJsonStr != null) {
                                    val mediaData = tryParseJson<DecryptedMedia>(decryptedJsonStr)
                                    val serverTitle = serverName.uppercase()
                                    
                                    // Ekstrak HLS (.m3u8) jika tersedia
                                    mediaData?.hls?.sources?.forEach { source ->
                                        val m3u8Url = when {
                                            !source.file.isNullOrBlank() -> source.file
                                            !source.url.isNullOrBlank() && !source.path.isNullOrBlank() -> {
                                                val baseUrl = source.url.trimEnd('/')
                                                val path = source.path.trimStart('/')
                                                "$baseUrl/$path"
                                            }
                                            else -> null
                                        }

                                        if (m3u8Url != null) {
                                            Log.d("LK21_DEBUG", "HLS berhasil diuraikan: $m3u8Url")
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = providerName,
                                                    name = "$providerName - $serverTitle (HLS ${source.label ?: "Auto"})",
                                                    url = m3u8Url,
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.referer = "https://abysscdn.com/"
                                                    this.quality = getQualityFromName(source.label)
                                                }
                                            )
                                        }
                                    }
                                    
                                    // Ekstrak MP4 direct (Seperti hasil uji sukses Termux)
                                    mediaData?.mp4?.sources?.forEach { source ->
                                        val mp4Url = when {
                                            !source.file.isNullOrBlank() -> source.file
                                            !source.url.isNullOrBlank() && !source.path.isNullOrBlank() -> {
                                                val baseUrl = source.url.trimEnd('/')
                                                val path = source.path.trimStart('/')
                                                "$baseUrl/$path"
                                            }
                                            else -> null
                                        }

                                        if (mp4Url != null) {
                                            Log.d("LK21_DEBUG", "MP4 berhasil diuraikan: $mp4Url")
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = providerName,
                                                    name = "$providerName - $serverTitle (MP4 ${source.label ?: "HD"})",
                                                    url = mp4Url,
                                                    type = ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = "https://abysscdn.com/"
                                                    this.quality = getQualityFromName(source.label)
                                                }
                                            )
                                        }
                                    }
                                } else {
                                    Log.e("LK21_DEBUG", "Gagal mendekripsi payload Abyss. Kunci AES tidak cocok.")
                                }
                            }
                        } else {
                            Log.e("LK21_DEBUG", "Gagal menyaring 'const datas' dari HTML CDN!")
                        }
                    } else {
                        Log.e("LK21_DEBUG", "Kontainer Iframe kosong!")
                    }
                } catch (e: Exception) {
                    Log.e("LK21_DEBUG", "Kesalahan pada loadLinks: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        return true
    }

    // ==========================================
    // ENGINE DEKRIPSI (AES-CTR)
    // ==========================================

    private fun decryptAbyssMedia(payload: AbyssPayload): String? {
        try {
            val slug = payload.slug ?: return null
            val md5Id = payload.md5Id?.toString() ?: return null
            val userId = payload.userId?.toString() ?: return null
            val mediaString = payload.media ?: return null

            // 1. Generate Key menggunakan format gabungan
            val rawKey = "$userId:$slug:$md5Id"
            val md5Bytes = MessageDigest.getInstance("MD5").digest(rawKey.toByteArray(StandardCharsets.UTF_8))
            val md5Hex = md5Bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

            // 2. Setup Spesifikasi AES-CTR (Key 32-Byte & IV 16-Byte dari Hex)
            val secretKey = SecretKeySpec(md5Hex.toByteArray(StandardCharsets.UTF_8), "AES")
            val iv = IvParameterSpec(md5Hex.substring(0, 16).toByteArray(StandardCharsets.UTF_8))

            // 3. Konversi karakter String Media ke wujud Byte Array murni secara aman (ISO-8859-1)
            val encryptedBytes = mediaString.toByteArray(StandardCharsets.ISO_8859_1)

            // 4. Eksekusi Dekripsi AES-CTR
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, StandardCharsets.UTF_8)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
