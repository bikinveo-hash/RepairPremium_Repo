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
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries) // Sekarang support Series!
    override var lang = "en"
    override val hasMainPage = true

    private val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"

    // Helper fungsi untuk memanggil API TMDB berdasarkan kategori
    private suspend fun getTmdbContent(type: String, country: String? = null, network: Int? = null, page: Int): List<SearchResponse> {
        val typePath = if (type == "movie") "movie" else "tv"
        var url = "https://api.themoviedb.org/3/discover/$typePath?api_key=$tmdbApiKey&page=$page&sort_by=popularity.desc"
        
        if (country != null) url += "&with_origin_country=$country"
        if (network != null) url += "&with_networks=$network"
        
        val res = app.get(url).parsedSafe<TmdbResponse>()
        return res?.results?.map { movie ->
            val name = movie.title ?: movie.name ?: "Unknown"
            if (type == "movie") {
                newMovieSearchResponse(name, movie.id.toString(), TvType.Movie) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
                }
            } else {
                newTvSeriesSearchResponse(name, movie.id.toString(), TvType.TvSeries) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
                }
            }
        } ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()

        // Menambahkan Baris Kategori Sesuai Permintaan
        items.add(HomePageList("Netflix Indonesia (Movies)", getTmdbContent("movie", "ID", 213, page)))
        items.add(HomePageList("Netflix Indonesia (Series)", getTmdbContent("tv", "ID", 213, page)))
        
        items.add(HomePageList("Netflix Korea (Movies)", getTmdbContent("movie", "KR", 213, page)))
        items.add(HomePageList("Netflix Korea (Series)", getTmdbContent("tv", "KR", 213, page)))
        
        items.add(HomePageList("Disney+ Originals", getTmdbContent("movie", null, 2739, page)))
        items.add(HomePageList("Disney+ Series", getTmdbContent("tv", null, 2739, page)))
        
        items.add(HomePageList("HBO Collection", getTmdbContent("movie", null, 49, page)))
        
        items.add(HomePageList("Viu Indonesia (Series)", getTmdbContent("tv", "ID", 1595, page)))
        
        items.add(HomePageList("WeTV Indonesia", getTmdbContent("tv", "ID", 3354, page)))
        items.add(HomePageList("WeTV Korea", getTmdbContent("tv", "KR", 3354, page)))

        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val movieRes = app.get("https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=${query.replace(" ", "%20")}").parsedSafe<TmdbResponse>()
        val tvRes = app.get("https://api.themoviedb.org/3/search/tv?api_key=$tmdbApiKey&query=${query.replace(" ", "%20")}").parsedSafe<TmdbResponse>()
        
        val results = ArrayList<SearchResponse>()
        movieRes?.results?.forEach { 
            results.add(newMovieSearchResponse(it.title ?: "Movie", it.id.toString(), TvType.Movie) { this.posterUrl = "https://image.tmdb.org/t/p/w500${it.posterPath}" })
        }
        tvRes?.results?.forEach { 
            results.add(newTvSeriesSearchResponse(it.name ?: "Series", it.id.toString(), TvType.TvSeries) { this.posterUrl = "https://image.tmdb.org/t/p/w500${it.posterPath}" })
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val tmdbId = url.substringAfterLast("/")
        // Cek apakah ini film atau series
        val isMovie = !url.contains("tv") // Sederhana: jika tidak ada tag tv, anggap movie

        return if (url.contains("movie") || !url.contains("tv")) {
            val movieDetail = app.get("https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetailResponse>()!!
            newMovieLoadResponse(movieDetail.title ?: "", url, TvType.Movie, "movie/$tmdbId") {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movieDetail.posterPath}"
                this.plot = movieDetail.overview
                this.year = movieDetail.releaseDate?.take(4)?.toIntOrNull()
            }
        } else {
            val tvDetail = app.get("https://api.themoviedb.org/3/tv/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetailResponse>()!!
            val seasons = tvDetail.seasons?.map { s ->
                val epRes = app.get("https://api.themoviedb.org/3/tv/$tmdbId/season/${s.seasonNumber}?api_key=$tmdbApiKey").parsedSafe<TmdbSeasonResponse>()
                epRes?.episodes?.map { e ->
                    Episode("tv/$tmdbId/${s.seasonNumber}/${e.episodeNumber}", e.name, s.seasonNumber, e.episodeNumber)
                } ?: emptyList()
            }?.flatten() ?: emptyList()

            newTvSeriesLoadResponse(tvDetail.name ?: "", url, TvType.TvSeries, seasons) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${tvDetail.posterPath}"
                this.plot = tvDetail.overview
                this.year = tvDetail.firstAirDate?.take(4)?.toIntOrNull()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data berbentuk: "movie/ID" atau "tv/ID/S/E"
        val baseUrl = "$mainUrl/embed/$data"

        // --- 1. TAHAP SUBTITLE (TIDAK DIOTAK-ATIK) ---
        try {
            val idOnly = data.substringAfter("/").substringBefore("/")
            val extRes = app.get("https://api.themoviedb.org/3/movie/$idOnly/external_ids?api_key=$tmdbApiKey").text
            val imdbId = Regex(""""imdb_id"\s*:\s*"([^"]+)"""").find(extRes)?.groupValues?.get(1)?.removePrefix("tt")
            if (imdbId != null) {
                val opsRes = app.get("https://rest.opensubtitles.org/search/imdbid-$imdbId/sublanguageid-ind", headers = mapOf("X-User-Agent" to "trailers.to-UA")).text
                val subUrl = Regex(""""SubDownloadLink"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)?.replace("\\/", "/")
                val subId = Regex(""""IDSubtitleFile"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)
                if (subUrl != null && subId != null) {
                    val gzBytes = app.get(subUrl).okhttpResponse.body?.bytes()
                    if (gzBytes != null) {
                        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("sub_data", "blob", gzBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                            .addFormDataPart("sub_id", subId).addFormDataPart("sub_enc", "UTF-8")
                            .addFormDataPart("sub_src", "ops").addFormDataPart("subformat", "srt").build()
                        val extractRes = app.post("https://cloudnestra.com/get_sub_url", requestBody = requestBody).text
                        if (extractRes.startsWith("/sub/")) subtitleCallback.invoke(newSubtitleFile("Indonesia", "https://cloudnestra.com$extractRes"))
                    }
                }
            }
        } catch (e: Exception) { }

        // --- 2. TAHAP VIDEO (TIDAK DIOTAK-ATIK - DENGAN WEBVIEW BYPASS) ---
        val universalBypass = WebViewResolver(Regex("""vidsrc|cloudnestra|vsembed|rcp"""))
        val response1 = app.get(baseUrl, interceptor = universalBypass).text
        var iframeUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1) ?: Jsoup.parse(response1).selectFirst("iframe")?.attr("src") ?: throw ErrorLoadingException("Link Gagal")
        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"

        var finalHtml = app.get(iframeUrl, referer = baseUrl, interceptor = universalBypass).text
        var finalReferer = iframeUrl

        if (!finalHtml.contains("H4sI")) {
            val hiddenMatch = Regex("""['"](/prorcp/[^'"]+|/rcp/[^'"]+)['"]""").find(finalHtml)
            if (hiddenMatch != null) {
                val domain = "https://" + (if (finalReferer.contains("vsembed.ru")) "cloudnestra.com" else finalReferer.substringAfter("://").substringBefore("/"))
                val hiddenUrl = domain + hiddenMatch.groupValues[1]
                finalHtml = app.get(hiddenUrl, referer = finalReferer, interceptor = universalBypass).text
                finalReferer = hiddenUrl
            }
        }

        val hashMatch = Regex("""(H4sI[a-zA-Z0-9+/=_\.\-\\]+m3u8)""").find(finalHtml) ?: throw ErrorLoadingException("Gagal Cloudflare")
        val teksRahasia = hashMatch.groupValues[1].replace("\\/", "/")
        callback.invoke(newExtractorLink(this.name, "VidSrc HD", "https://tmstr2.neonhorizonworkshops.com/pl/$teksRahasia", ExtractorLinkType.M3U8) { this.referer = finalReferer })
        return true
    }
}

// DATA CLASSES
data class TmdbResponse(@JsonProperty("results") val results: List<TmdbItem>)
data class TmdbItem(@JsonProperty("id") val id: Int, @JsonProperty("title") val title: String?, @JsonProperty("name") val name: String?, @JsonProperty("poster_path") val posterPath: String?)
data class TmdbDetailResponse(
    @JsonProperty("title") val title: String?, @JsonProperty("name") val name: String?, @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("overview") val overview: String?, @JsonProperty("release_date") val releaseDate: String?, @JsonProperty("first_air_date") val firstAirDate: String?,
    @JsonProperty("seasons") val seasons: List<TmdbSeason>?
)
data class TmdbSeason(@JsonProperty("season_number") val seasonNumber: Int, @JsonProperty("episode_count") val episodeCount: Int)
data class TmdbSeasonResponse(@JsonProperty("episodes") val episodes: List<TmdbEpisode>)
data class TmdbEpisode(@JsonProperty("episode_number") val episodeNumber: Int, @JsonProperty("name") val name: String)
