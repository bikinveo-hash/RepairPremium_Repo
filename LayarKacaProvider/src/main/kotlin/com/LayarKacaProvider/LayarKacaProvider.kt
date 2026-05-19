package com.LayarKacaProvider

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
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
// DATA CLASSES TAMBAHAN UNTUK DEKRIPSI
// ==========================================

data class AbyssPayload(
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("md5_id") val md5Id: Any?, // Gunakan Any? agar angka JSON tidak error
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
        
        // Setup Header tiruan Browser WebView agar lolos Cloudflare
        val browserHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 12; SAMSUNG SM-A415F) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/17.0 Chrome/96.0.4664.104 Mobile Safari/537.36",
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
                    Log.d("LK21_DEBUG", "Memulai request ke: $iframePopupUrl")
                    val iframeResponse = app.get(iframePopupUrl, referer = data, headers = browserHeaders)
                    val realIframeSrc = iframeResponse.document.selectFirst("div.embed-container iframe")?.attr("src")
                    
                    if (!realIframeSrc.isNullOrEmpty()) {
                        Log.d("LK21_DEBUG", "Iframe asli ditemukan: $realIframeSrc")
                        
                        // EKSTRAKSI ID LANGSUNG (Contoh: https://abyssplayer.com/L33S7fyKh -> L33S7fyKh)
                        val id = realIframeSrc.substringAfterLast("/").substringBefore("?")
                        val directCdnUrl = "https://abysscdn.com/?v=$id"
                        
                        Log.d("LK21_DEBUG", "Melompati 302, langsung menembak CDN: $directCdnUrl")

                        // Langkah 1: Request langsung ke abysscdn dengan referer playeriframe.sbs
                        var abyssHtml = app.get(
                            url = directCdnUrl,
                            headers = browserHeaders,
                            referer = "https://playeriframe.sbs/"
                        ).text

                        // Langkah 2: Fallback jika 'const datas' tidak langsung ditemukan
                        if (!abyssHtml.contains("const datas")) {
                            Log.d("LK21_DEBUG", "datas tidak ditemukan, mencoba fallback referer")
                            abyssHtml = app.get(
                                url = directCdnUrl,
                                headers = browserHeaders,
                                referer = "https://abyssplayer.com/"
                            ).text
                        }

                        // REGEX PENYARING DATA ENKRIPSI
                        val datasRegex = Regex("""const\s+datas\s*=\s*"([^"]+)"""")
                        val base64Data = datasRegex.find(abyssHtml)?.groupValues?.get(1)
                        
                        if (base64Data != null) {
                            Log.d("LK21_DEBUG", "Payload Base64 berhasil diekstrak!")
                            val decodedJsonString = String(android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT))
                            val payload = tryParseJson<AbyssPayload>(decodedJsonString)
                            
                            if (payload != null) {
                                val decryptedJsonStr = decryptAbyssMedia(payload)
                                
                                if (decryptedJsonStr != null) {
                                    val mediaData = tryParseJson<DecryptedMedia>(decryptedJsonStr)
                                    val serverTitle = serverName.uppercase()
                                    
                                    // Ekstrak HLS (.m3u8)
                                    mediaData?.hls?.sources?.forEach { source ->
                                        source.file?.let { m3u8Url ->
                                            Log.d("LK21_DEBUG", "HLS ditemukan: $m3u8Url")
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
                                    
                                    // Ekstrak MP4 direct
                                    mediaData?.mp4?.sources?.forEach { source ->
                                        source.file?.let { mp4Url ->
                                            Log.d("LK21_DEBUG", "MP4 ditemukan: $mp4Url")
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
                                    Log.e("LK21_DEBUG", "Dekripsi gagal! Key AES-CTR tidak cocok.")
                                }
                            }
                        } else {
                            Log.e("LK21_DEBUG", "Gagal menyaring 'const datas' dari HTML CDN!")
                        }
                    } else {
                        Log.e("LK21_DEBUG", "Iframe kontainer kosong!")
                    }
                } catch (e: Exception) {
                    Log.e("LK21_DEBUG", "Error di loadLinks: ${e.message}")
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
            val md5Bytes = MessageDigest.getInstance("MD5").digest(rawKey.toByteArray(Charsets.UTF_8))
            val md5Hex = md5Bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

            // 2. Setup Spesifikasi AES-CTR (Key 32-Byte & IV 16-Byte dari Hex)
            val secretKey = SecretKeySpec(md5Hex.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(md5Hex.substring(0, 16).toByteArray(Charsets.UTF_8))

            // 3. Konversi karakter String Media ke wujud Byte Array murni
            val encryptedBytes = mediaString.map { it.code.toByte() }.toByteArray()

            // 4. Eksekusi Dekripsi
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
