package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.get
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class VidSrcProvider : MainAPI() {
    override var name = "VidSrc AdiXtream"
    override var mainUrl = "https://vidsrc.net"
    override var supportedTypes = setOf(TvType.Movie) // Fokus ke film dulu untuk contoh ini
    override var lang = "en"
    override val hasMainPage = true

    // API Key TMDB milikmu
    private val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"

    /**
     * FUNGSI 1: HALAMAN DEPAN
     * Memanggil daftar film populer dari TMDB saat aplikasi dibuka
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homePageLists = ArrayList<HomePageList>()
        val url = "https://api.themoviedb.org/3/movie/popular?api_key=$tmdbApiKey&language=en-US&page=$page"

        // Mengambil dan membaca JSON dari TMDB
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return newHomePageResponse(emptyList())

        val filmList = response.results.map { movie ->
            newMovieSearchResponse(
                name = movie.title,
                url = movie.id.toString(), // Menyimpan ID TMDB ke dalam URL
                type = TvType.Movie
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }

        homePageLists.add(HomePageList("Populer di VidSrc", filmList))
        return newHomePageResponse(homePageLists)
    }

    /**
     * FUNGSI 2: PENCARIAN
     * Mencari film berdasarkan ketikan user di TMDB
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val safeQuery = query.replace(" ", "%20")
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$safeQuery&language=en-US"
        
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return emptyList()

        return response.results.map { movie ->
            newMovieSearchResponse(
                name = movie.title,
                url = movie.id.toString(), // Menyimpan ID TMDB
                type = TvType.Movie
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }
    }

    /**
     * FUNGSI 3: HALAMAN DETAIL (LOAD)
     * Menampilkan sinopsis, rating, dan durasi dari TMDB
     */
    override suspend fun load(url: String): LoadResponse {
        val tmdbId = url // URL berisi ID TMDB dari fungsi sebelumnya
        val detailUrl = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=en-US"
        
        val movieDetail = app.get(detailUrl).parsedSafe<TmdbDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil detail film")

        return newMovieLoadResponse(
            name = movieDetail.title,
            url = tmdbId, // Oper ID TMDB ke loadLinks
            type = TvType.Movie,
            dataUrl = tmdbId
        ) {
            this.posterUrl = "https://image.tmdb.org/t/p/w500${movieDetail.posterPath}"
            this.backgroundPosterUrl = "https://image.tmdb.org/t/p/w1280${movieDetail.backdropPath}"
            this.year = movieDetail.releaseDate?.take(4)?.toIntOrNull()
            this.plot = movieDetail.overview
            this.duration = movieDetail.runtime
            this.score = Score.from10(movieDetail.voteAverage)
        }
    }

    /**
     * FUNGSI 4: EKSTRAKSI VIDEO (LOAD LINKS)
     * Ini adalah inti pembongkaran keamanan web VidSrc
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbId = data
        val baseUrl = "$mainUrl/embed/movie?tmdb=$tmdbId"

        // --- BUKA LAPISAN 1 (VidSrc) ---
        val response1 = app.get(baseUrl).text
        val iframe1 = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1) ?: return false
        val iframeUrl1 = if (iframe1.startsWith("//")) "https:$iframe1" else iframe1

        // --- BUKA LAPISAN 2 (vsembed.ru) ---
        val response2 = app.get(iframeUrl1, referer = baseUrl).text
        val iframe2 = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response2)?.groupValues?.get(1)
        
        // --- BUKA LAPISAN 3 (cloudnestra.com) ---
        var finalReferer = iframeUrl1
        val finalHtml = if (iframe2 != null) {
            val iframeUrl2 = if (iframe2.startsWith("//")) "https:$iframe2" else iframe2
            finalReferer = iframeUrl2
            app.get(iframeUrl2, referer = iframeUrl1).text
        } else {
            response2 
        }

        // --- EKSTRAK SUBTITLE (.vtt) MENGGUNAKAN BUILDER BARU ---
        val subRegex = Regex("""(https?://[^"'\s]+\.vtt[^"'\s]*)""")
        subRegex.findAll(finalHtml).forEach { match ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = "English",
                    url = match.value
                )
            )
        }

        // --- EKSTRAK VIDEO (H4sI...m3u8) MENGGUNAKAN BUILDER BARU ---
        val hashRegex = Regex("""(H4sI[^"'\s]+m3u8)""")
        val matchResult = hashRegex.find(finalHtml)

        if (matchResult != null) {
            val teksRahasia = matchResult.value
            val host = "https://tmstr2.neonhorizonworkshops.com/pl/"
            val m3u8Url = "$host$teksRahasia"

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "VidSrc HD",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = finalReferer
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }
}

// ==========================================
// DATA CLASS UNTUK MEMBACA JSON DARI TMDB
// ==========================================

data class TmdbResponse(
    @JsonProperty("results") val results: List<TmdbMovie>
)

data class TmdbMovie(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("poster_path") val posterPath: String?
)

data class TmdbDetailResponse(
    @JsonProperty("title") val title: String,
    @JsonProperty("poster_path") val posterPath: String?,
    @JsonProperty("backdrop_path") val backdropPath: String?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("release_date") val releaseDate: String?,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("vote_average") val voteAverage: Double?
)
