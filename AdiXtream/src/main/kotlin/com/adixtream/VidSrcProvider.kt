package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class VidSrcProvider : MainAPI() {
    override var name = "VidSrc AdiXtream"
    override var mainUrl = "https://vidsrc.net"
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true

    // API Key TMDB 
    private val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = ArrayList<HomePageList>()
        val url = "https://api.themoviedb.org/3/movie/popular?api_key=$tmdbApiKey&language=en-US&page=$page"
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return newHomePageResponse(emptyList())

        val filmList = response.results.map { movie ->
            newMovieSearchResponse(movie.title, movie.id.toString(), TvType.Movie) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }
        homePageLists.add(HomePageList("Populer di VidSrc", filmList))
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val safeQuery = query.replace(" ", "%20")
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$safeQuery&language=en-US"
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return emptyList()

        return response.results.map { movie ->
            newMovieSearchResponse(movie.title, movie.id.toString(), TvType.Movie) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val tmdbId = url.substringAfterLast("/") 
        val detailUrl = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=en-US"
        val movieDetail = app.get(detailUrl).parsedSafe<TmdbDetailResponse>() ?: throw ErrorLoadingException("Gagal TMDB")

        return newMovieLoadResponse(movieDetail.title, url, TvType.Movie, tmdbId) {
            this.posterUrl = "https://image.tmdb.org/t/p/w500${movieDetail.posterPath}"
            this.backgroundPosterUrl = "https://image.tmdb.org/t/p/w1280${movieDetail.backdropPath}"
            this.year = movieDetail.releaseDate?.take(4)?.toIntOrNull()
            this.plot = movieDetail.overview
            this.duration = movieDetail.runtime
            this.score = Score.from10(movieDetail.voteAverage)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbId = data.substringAfterLast("/") 
        val baseUrl = "$mainUrl/embed/movie/$tmdbId"

        // --- 1. AMBIL SUBTITLE INDONESIA DARI OPENSUBTITLES + CLOUDNESTRA ---
        // Kita bungkus try-catch agar jika server subtitle down, video tetap bisa diputar
        try {
            // A. Dapatkan IMDB ID dari TMDB
            val extUrl = "https://api.themoviedb.org/3/movie/$tmdbId/external_ids?api_key=$tmdbApiKey"
            val extRes = app.get(extUrl).parsedSafe<TmdbExternalIds>()
            val imdbIdFull = extRes?.imdbId
            
            if (imdbIdFull != null) {
                val imdbIdNum = imdbIdFull.removePrefix("tt") // Hapus 'tt' di awal
                
                // B. Cari Subtitle Indonesia di OpenSubtitles
                val opsUrl = "https://rest.opensubtitles.org/search/imdbid-$imdbIdNum/sublanguageid-ind"
                val opsHeaders = mapOf("User-Agent" to "Mozilla/5.0", "X-User-Agent" to "trailers.to-UA")
                val opsRes = app.get(opsUrl, headers = opsHeaders).parsedSafe<List<OpsSub>>()
                
                if (!opsRes.isNullOrEmpty()) {
                    val sub = opsRes.first()
                    val subUrl = sub.subDownloadLink
                    val subId = sub.idSubtitleFile
                    val subEnc = sub.subEncoding ?: "UTF-8"
                    
                    if (subUrl != null && subId != null) {
                        // C. Download file .gz biner
                        val gzBytes = app.get(subUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).okhttpResponse.body?.bytes()
                        
                        if (gzBytes != null) {
                            // D. Upload ke Cloudnestra untuk diekstrak (Jurus Rahasia)
                            val requestBody = MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("sub_data", "blob", gzBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                                .addFormDataPart("sub_id", subId)
                                .addFormDataPart("sub_enc", subEnc)
                                .addFormDataPart("sub_src", "ops")
                                .addFormDataPart("subformat", "srt")
                                .build()
                                
                            val extractRes = app.post(
                                "https://cloudnestra.com/get_sub_url",
                                headers = mapOf("Origin" to "https://cloudnestra.com", "Referer" to "https://cloudnestra.com/"),
                                requestBody = requestBody
                            ).text
                            
                            // E. Menangkap hasil /sub/ops-...srt dan mengirim ke Cloudstream
                            if (extractRes.startsWith("/sub/")) {
                                val finalSubUrl = "https://cloudnestra.com$extractRes"
                                subtitleCallback.invoke(
                                    newSubtitleFile("Indonesia", finalSubUrl)
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace() // Abaikan jika error agar video tetap jalan
        }

        // --- 2. BUKA LAPISAN VIDEO (VidSrc) ---
        val response1 = app.get(baseUrl).text
        var iframeUrl1 = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1)
        if (iframeUrl1 == null) iframeUrl1 = Jsoup.parse(response1).selectFirst("iframe")?.attr("src")
        if (iframeUrl1.isNullOrEmpty()) throw ErrorLoadingException("Server Error: Iframe VidSrc (Lapis 1) tidak ditemukan.")
        iframeUrl1 = if (iframeUrl1.startsWith("//")) "https:$iframeUrl1" else iframeUrl1

        var finalHtml = ""
        var finalReferer = baseUrl

        // --- 3. DETEKSI PINTAR: VSEMBED ATAU LANGSUNG CLOUDNESTRA? ---
        if (iframeUrl1.contains("cloudnestra") || iframeUrl1.contains("rcp")) {
            finalReferer = iframeUrl1
            finalHtml = app.get(iframeUrl1, referer = baseUrl).text
        } else {
            val response2 = app.get(iframeUrl1, referer = baseUrl).text
            var iframeUrl2 = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response2)?.groupValues?.get(1)
            if (iframeUrl2 == null) iframeUrl2 = Jsoup.parse(response2).selectFirst("iframe")?.attr("src")

            if (!iframeUrl2.isNullOrEmpty()) {
                iframeUrl2 = if (iframeUrl2.startsWith("//")) "https:$iframeUrl2" else iframeUrl2
                finalReferer = iframeUrl2
                finalHtml = app.get(iframeUrl2, referer = iframeUrl1).text
            } else {
                finalHtml = response2
                finalReferer = iframeUrl1
            }
        }

        // --- 4. BONGKAR JEBAKAN TOMBOL PLAY (JAVASCRIPT LAZY LOAD) ---
        if (!finalHtml.contains("H4sI")) {
            val hiddenPathMatch = Regex("""['"](/prorcp/[^'"]+|/rcp/[^'"]+)['"]""").find(finalHtml)
            if (hiddenPathMatch != null) {
                val hiddenPath = hiddenPathMatch.groupValues[1]
                val domain = finalReferer.substringBefore("/rcp").substringBefore("/prorcp")
                val hiddenUrl = domain + hiddenPath
                finalReferer = hiddenUrl
                finalHtml = app.get(hiddenUrl, referer = finalReferer).text
            } else {
                val hiddenUrl = Regex("""(https?://[^"'\s]*(?:cloudnestra|rcp|prorcp)[^"'\s]*)""").find(finalHtml)?.groupValues?.get(1)
                if (hiddenUrl != null) {
                    finalReferer = hiddenUrl
                    finalHtml = app.get(hiddenUrl, referer = finalReferer).text
                }
            }
        }

        // Subtitle Inggris Bawaan (Opsional)
        val subRegex = Regex("""(https?://[^"'\s]+\.vtt[^"'\s]*)""")
        subRegex.findAll(finalHtml).forEach { match ->
            subtitleCallback.invoke(newSubtitleFile("English (Auto)", match.value.replace("\\/", "/")))
        }

        // --- 5. EKSTRAKSI VIDEO (H4sI...m3u8) ---
        val hashRegex = Regex("""(H4sI[a-zA-Z0-9+/=_\.\-\\]+m3u8)""")
        val matchResult = hashRegex.find(finalHtml)
        if (matchResult == null) throw ErrorLoadingException("Error: Sandi Video (H4sI) tidak ditemukan.")

        val teksRahasia = matchResult.value.replace("\\/", "/")
        var dynamicHost = Regex("""(https?://[^"'\s]+/pl/)""").find(finalHtml)?.groupValues?.get(1) ?: "https://tmstr2.neonhorizonworkshops.com/pl/"
        if (dynamicHost.contains("{v1}")) dynamicHost = "https://tmstr2.neonhorizonworkshops.com/pl/"
        
        val m3u8Url = "$dynamicHost$teksRahasia"

        // KIRIM VIDEO KE CLOUDSTREAM!
        callback.invoke(
            newExtractorLink(this.name, "VidSrc HD", m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = finalReferer
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}

// ==========================================
// DATA CLASS (CETAKAN JSON)
// ==========================================
data class TmdbResponse(@JsonProperty("results") val results: List<TmdbMovie>)
data class TmdbMovie(@JsonProperty("id") val id: Int, @JsonProperty("title") val title: String, @JsonProperty("poster_path") val posterPath: String?)
data class TmdbDetailResponse(
    @JsonProperty("title") val title: String, @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?, @JsonProperty("overview") val overview: String?,
    @JsonProperty("release_date") val releaseDate: String?, @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("vote_average") val voteAverage: Double?
)

// Data Class Baru untuk Konversi ID & OpenSubtitles
data class TmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String?)
data class OpsSub(
    @JsonProperty("SubDownloadLink") val subDownloadLink: String?,
    @JsonProperty("IDSubtitleFile") val idSubtitleFile: String?,
    @JsonProperty("SubEncoding") val subEncoding: String?
)
