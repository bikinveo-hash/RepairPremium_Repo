package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
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

    private val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "https://api.themoviedb.org/3/movie/popular?api_key=$tmdbApiKey&language=en-US&page=$page"
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return newHomePageResponse(emptyList())
        val filmList = response.results.map { movie ->
            newMovieSearchResponse(movie.title, movie.id.toString(), TvType.Movie) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }
        return newHomePageResponse(listOf(HomePageList("Populer di VidSrc", filmList)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=${query.replace(" ", "%20")}&language=en-US"
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return emptyList()
        return response.results.map { movie ->
            newMovieSearchResponse(movie.title, movie.id.toString(), TvType.Movie) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val tmdbId = url.substringAfterLast("/") 
        val movieDetail = app.get("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal TMDB")

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

        // --- 1. TAHAP SUBTITLE (INI SUDAH BERHASIL DI LOG!) ---
        try {
            val extRes = app.get("https://api.themoviedb.org/3/movie/$tmdbId/external_ids?api_key=$tmdbApiKey").text
            val imdbId = Regex(""""imdb_id"\s*:\s*"([^"]+)"""").find(extRes)?.groupValues?.get(1)?.removePrefix("tt")
            
            if (imdbId != null) {
                val opsRes = app.get("https://rest.opensubtitles.org/search/imdbid-$imdbId/sublanguageid-ind", 
                    headers = mapOf("X-User-Agent" to "trailers.to-UA")).text
                val subUrl = Regex(""""SubDownloadLink"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)?.replace("\\/", "/")
                val subId = Regex(""""IDSubtitleFile"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)

                if (subUrl != null && subId != null) {
                    val gzBytes = app.get(subUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).okhttpResponse.body?.bytes()
                    if (gzBytes != null) {
                        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("sub_data", "blob", gzBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                            .addFormDataPart("sub_id", subId).addFormDataPart("sub_enc", "UTF-8")
                            .addFormDataPart("sub_src", "ops")
                            .addFormDataPart("subformat", "srt").build()
                        val extractRes = app.post("https://cloudnestra.com/get_sub_url", requestBody = requestBody).text
                        if (extractRes.startsWith("/sub/")) subtitleCallback.invoke(newSubtitleFile("Indonesia", "https://cloudnestra.com$extractRes"))
                    }
                }
            }
        } catch (e: Exception) { }

        // --- 2. TAHAP VIDEO (SISTEM BYPASS CLOUDFLARE DIPERKUAT) ---
        // Regex diperluas agar mencakup vidsrc, cloudnestra, vsembed, dan rcp
        val universalBypass = WebViewResolver(Regex("""vidsrc|cloudnestra|vsembed|rcp"""))

        // Request pertama ke vidsrc.net sekarang JALAN lewat WebView Interceptor
        val response1 = app.get(baseUrl, interceptor = universalBypass).text
        
        var iframeUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1)
            ?: Jsoup.parse(response1).selectFirst("iframe")?.attr("src")
            ?: throw ErrorLoadingException("Server sibuk. Coba klik play lagi dalam 5 detik.")

        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"

        // Buka Iframe Cloudnestra/Vsembed
        var finalHtml = app.get(iframeUrl, referer = baseUrl, interceptor = universalBypass).text
        var finalReferer = iframeUrl

        // --- 3. BONGKAR JEBAKAN JAVASCRIPT ---
        if (!finalHtml.contains("H4sI")) {
            val hiddenMatch = Regex("""['"](/prorcp/[^'"]+|/rcp/[^'"]+)['"]""").find(finalHtml)
            if (hiddenMatch != null) {
                val domain = "https://" + (if (finalReferer.contains("vsembed.ru")) "cloudnestra.com" else finalReferer.substringAfter("://").substringBefore("/"))
                val hiddenUrl = domain + hiddenMatch.groupValues[1]
                
                // Tembak link tersembunyi
                finalHtml = app.get(hiddenUrl, referer = finalReferer, interceptor = universalBypass).text
                finalReferer = hiddenUrl
            }
        }

        // --- 4. EKSTRAKSI HASIL AKHIR ---
        val hashMatch = Regex("""(H4sI[a-zA-Z0-9+/=_\.\-\\]+m3u8)""").find(finalHtml)
            ?: throw ErrorLoadingException("Gagal memuat video. Pastikan internet stabil dan coba lagi.")

        val teksRahasia = hashMatch.groupValues[1].replace("\\/", "/")
        val host = "https://tmstr2.neonhorizonworkshops.com/pl/" 
        val m3u8Url = "$host$teksRahasia"

        callback.invoke(
            newExtractorLink(this.name, "VidSrc HD", m3u8Url, ExtractorLinkType.M3U8) {
                this.referer = finalReferer
            }
        )
        return true
    }
}

// DATA CLASSES UTAMA
data class TmdbResponse(@JsonProperty("results") val results: List<TmdbMovie>)
data class TmdbMovie(@JsonProperty("id") val id: Int, @JsonProperty("title") val title: String, @JsonProperty("poster_path") val posterPath: String?)
data class TmdbDetailResponse(
    @JsonProperty("title") val title: String, @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?, @JsonProperty("overview") val overview: String?,
    @JsonProperty("release_date") val releaseDate: String?, @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("vote_average") val voteAverage: Double?
)
