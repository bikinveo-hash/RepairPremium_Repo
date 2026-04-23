package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup // Digunakan sebagai alat bantu baca HTML

class VidSrcProvider : MainAPI() {
    override var name = "VidSrc AdiXtream"
    override var mainUrl = "https://vidsrc.net"
    override var supportedTypes = setOf(TvType.Movie) // Fokus pada film
    override var lang = "en"
    override val hasMainPage = true

    // API Key TMDB (Jangan sampai hilang ya!)
    private val tmdbApiKey = "422bcadf9cfb5ff5b6951cef66b4a0b6"

    /**
     * ==========================================
     * FUNGSI 1: HALAMAN DEPAN
     * ==========================================
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homePageLists = ArrayList<HomePageList>()
        val url = "https://api.themoviedb.org/3/movie/popular?api_key=$tmdbApiKey&language=en-US&page=$page"

        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return newHomePageResponse(emptyList())

        val filmList = response.results.map { movie ->
            newMovieSearchResponse(
                name = movie.title,
                url = movie.id.toString(), // Simpan ID TMDB di URL
                type = TvType.Movie
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }

        homePageLists.add(HomePageList("Populer di VidSrc", filmList))
        return newHomePageResponse(homePageLists)
    }

    /**
     * ==========================================
     * FUNGSI 2: PENCARIAN
     * ==========================================
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val safeQuery = query.replace(" ", "%20")
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$tmdbApiKey&query=$safeQuery&language=en-US"
        
        val response = app.get(url).parsedSafe<TmdbResponse>() ?: return emptyList()

        return response.results.map { movie ->
            newMovieSearchResponse(
                name = movie.title,
                url = movie.id.toString(), // Simpan ID TMDB
                type = TvType.Movie
            ) {
                this.posterUrl = "https://image.tmdb.org/t/p/w500${movie.posterPath}"
            }
        }
    }

    /**
     * ==========================================
     * FUNGSI 3: HALAMAN DETAIL (SINOPSIS)
     * ==========================================
     */
    override suspend fun load(url: String): LoadResponse {
        // Memotong awalan "https://vidsrc.net/" jika ada, mengambil ID aslinya saja
        val tmdbId = url.substringAfterLast("/") 
        
        val detailUrl = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&language=en-US"
        
        val movieDetail = app.get(detailUrl).parsedSafe<TmdbDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil detail film dari TMDB")

        return newMovieLoadResponse(
            name = movieDetail.title,
            url = url, // Biarkan utuh untuk riwayat tontonan Cloudstream
            type = TvType.Movie,
            dataUrl = tmdbId // ID MURNI yang akan dioper ke fungsi loadLinks
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
     * ==========================================
     * FUNGSI 4: PEMUTARAN VIDEO (INTI PLUGIN)
     * ==========================================
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data berisi ID TMDB dari dataUrl fungsi load
        val tmdbId = data.substringAfterLast("/") 
        val baseUrl = "$mainUrl/embed/movie/$tmdbId"

        // --- 1. BUKA LAPISAN AWAL (VidSrc) ---
        val response1 = app.get(baseUrl).text
        var iframeUrl1 = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1)
        if (iframeUrl1 == null) {
            iframeUrl1 = Jsoup.parse(response1).selectFirst("iframe")?.attr("src")
        }
        
        if (iframeUrl1.isNullOrEmpty()) throw ErrorLoadingException("Server Error: Iframe VidSrc (Lapis 1) tidak ditemukan.")
        iframeUrl1 = if (iframeUrl1.startsWith("//")) "https:$iframeUrl1" else iframeUrl1

        var finalHtml = ""
        var finalReferer = baseUrl

        // --- 2. DETEKSI PINTAR: VSEMBED ATAU LANGSUNG CLOUDNESTRA? ---
        if (iframeUrl1.contains("cloudnestra") || iframeUrl1.contains("rcp")) {
            finalReferer = iframeUrl1
            finalHtml = app.get(iframeUrl1, referer = baseUrl).text
        } else {
            val response2 = app.get(iframeUrl1, referer = baseUrl).text
            var iframeUrl2 = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response2)?.groupValues?.get(1)
            if (iframeUrl2 == null) {
                iframeUrl2 = Jsoup.parse(response2).selectFirst("iframe")?.attr("src")
            }

            if (!iframeUrl2.isNullOrEmpty()) {
                iframeUrl2 = if (iframeUrl2.startsWith("//")) "https:$iframeUrl2" else iframeUrl2
                finalReferer = iframeUrl2
                finalHtml = app.get(iframeUrl2, referer = iframeUrl1).text
            } else {
                finalHtml = response2
                finalReferer = iframeUrl1
            }
        }

        // --- 3. BONGKAR JEBAKAN TOMBOL PLAY (JAVASCRIPT LAZY LOAD) ---
        if (!finalHtml.contains("H4sI")) {
            val hiddenPathMatch = Regex("""['"](/prorcp/[^'"]+|/rcp/[^'"]+)['"]""").find(finalHtml)
            
            if (hiddenPathMatch != null) {
                val hiddenPath = hiddenPathMatch.groupValues[1]
                val domain = finalReferer.substringBefore("/rcp").substringBefore("/prorcp")
                val hiddenUrl = domain + hiddenPath
                
                finalReferer = hiddenUrl
                finalHtml = app.get(hiddenUrl, referer = finalReferer).text
            } else {
                // Jaga-jaga kalau bentuknya link full (http...)
                val hiddenUrl = Regex("""(https?://[^"'\s]*(?:cloudnestra|rcp|prorcp)[^"'\s]*)""").find(finalHtml)?.groupValues?.get(1)
                if (hiddenUrl != null) {
                    finalReferer = hiddenUrl
                    finalHtml = app.get(hiddenUrl, referer = finalReferer).text
                }
            }
        }

        // --- 4. EKSTRAKSI SUBTITLE (.vtt) ---
        val subRegex = Regex("""(https?://[^"'\s]+\.vtt[^"'\s]*)""")
        subRegex.findAll(finalHtml).forEach { match ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = "English", // Bahasa bisa disesuaikan nanti
                    url = match.value.replace("\\/", "/")
                )
            )
        }

        // --- 5. EKSTRAKSI VIDEO (H4sI...m3u8) ---
        val hashRegex = Regex("""(H4sI[a-zA-Z0-9+/=_\.\-\\]+m3u8)""")
        val matchResult = hashRegex.find(finalHtml)

        if (matchResult == null) {
            throw ErrorLoadingException("Error: Sandi Video (H4sI) tidak ditemukan. Coba film lain.")
        }

        val teksRahasia = matchResult.value.replace("\\/", "/")
        
        // Perbaikan Host Dinamis: Ganti {v1} menjadi domain asli
        var dynamicHost = Regex("""(https?://[^"'\s]+/pl/)""").find(finalHtml)?.groupValues?.get(1) 
            ?: "https://tmstr2.neonhorizonworkshops.com/pl/"
            
        if (dynamicHost.contains("{v1}")) {
            dynamicHost = "https://tmstr2.neonhorizonworkshops.com/pl/"
        }
        
        val m3u8Url = "$dynamicHost$teksRahasia"

        // --- 6. KIRIM VIDEO KE CLOUDSTREAM! ---
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "VidSrc HD",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = finalReferer // Penting agar video tidak diblokir server
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}

/**
 * ==========================================
 * DATA CLASS (CETAKAN) UNTUK JSON TMDB
 * ==========================================
 */
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
