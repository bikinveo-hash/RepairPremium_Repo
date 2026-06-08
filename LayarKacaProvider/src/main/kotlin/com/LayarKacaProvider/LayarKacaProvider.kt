package com.LayarKacaProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv10.lk21official.cc"
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // =========================================================================
    // KATEGORI LENGKAP & ANTI-DDOS (SESUAI WEB LK21)
    // =========================================================================
    override val mainPage = mainPageOf(
        "latest/" to "Film Terbaru",
        "top-series-today/" to "Series Unggulan",
        "latest-series/" to "Series Update",
        "populer/" to "Top Bulan Ini",
        "nonton-bareng-keluarga/" to "Nonton Bareng Keluarga",
        "genre/action/" to "Action Terbaru",
        "genre/romance/" to "Romance Terbaru",
        "genre/comedy/" to "Comedy Terbaru",
        "genre/horror/" to "Horror Terbaru",
        "country/south-korea/" to "Korea Terbaru",
        "country/thailand/" to "Thailand Terbaru",
        "country/india/" to "India Terbaru"
    )

    // Fitur wajib agar server LK21 tidak memblokir koneksi kita
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L

    // =========================================================================
    // INFINITE SCROLL HOME PAGE
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Aturan Path LK21: Halaman 1 tanpa "page/1", Halaman 2 dst menggunakan "page/x/"
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Referer" to "$mainUrl/"
        )

        val document = app.get(url, headers = headers).document
        val elements = document.select("div#post-container article, div.grid-archive article, div.widget article, article.item")

        val list = elements.mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request, list, list.isNotEmpty())
    }

    // =========================================================================
    // RC4 DECRYPT
    // =========================================================================
    private fun decryptRC4(key: String, encryptedBase64: String): String {
        return try {
            val cipher = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0..255) {
                j = (j + s[i] + key[i % key.length].code) % 256
                val temp = s[i]; s[i] = s[j]; s[j] = temp
            }
            var i = 0; j = 0
            val result = ByteArray(cipher.size)
            for (k in cipher.indices) {
                i = (i + 1) % 256
                j = (j + s[i]) % 256
                val temp = s[i]; s[i] = s[j]; s[j] = temp
                val kStream = s[(s[i] + s[j]) % 256]
                result[k] = ((cipher[k].toInt() and 0xFF) xor kStream).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    private fun getCleanTitle(title: String): String {
        var clean = title.replace(Regex("(?i)(nonton serial|nonton film|nonton|sub indo|di lk21|lk21|layarkaca21)"), "")
        clean = clean.replace(Regex("(?i)\\bseason\\s*\\d+.*"), "")
        clean = clean.replace(Regex("\\(\\d{4}\\)"), "")
        return clean.trim()
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var cleanUrl = url
        if (cleanUrl.startsWith("//")) cleanUrl = "https:$cleanUrl"
        cleanUrl = cleanUrl.substringBefore("?")
        // Menghapus ukuran thumbnail agar mendapatkan poster HD murni dari LK21
        return cleanUrl.replace(Regex("-\\d{2,4}x\\d{2,4}"), "")
    }

    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(
        val backdrop_path: String?,
        val poster_path: String?,
        val release_date: String?,
        val first_air_date: String?
    )

    data class LkSearchResponse(
        @JsonProperty("totalPages") val totalPages: Int?,
        @JsonProperty("data") val data: List<LkSearchData>?
    )

    data class LkSearchData(
        @JsonProperty("title") val title: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("year") val year: Int?
    )

    // =========================================================================
    // PARSING ITEM FILM INSTAN (BEBAS TMDB LIMIT)
    // =========================================================================
    private fun toSearchResult(element: Element): SearchResponse? {
        val rawTitle = element.select("h3.poster-title, h2.entry-title, h1.page-title, div.title").text().trim()
        if (rawTitle.isEmpty()) return null
        val href = fixUrl(element.select("a").first()?.attr("href") ?: return null)

        val imgElement = element.select("img").first()
        val rawPoster = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")

        val posterUrl = fixPosterUrl(rawPoster)
        val cleanTitle = getCleanTitle(rawTitle)
        val yearText = element.select("div.year, span.year").text()
        val year = yearText.toIntOrNull()
            ?: Regex("\\b(\\d{4})\\b").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val quality  = getQualityFromString(element.select("span.label").text())
        val isSeries = element.select("span.episode").isNotEmpty()
            || element.select("span.duration").text().contains("S.")

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl; this.quality = quality; this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl; this.quality = quality; this.year = year
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchUrl = "https://gudangvape.com/search.php?s=$query&page=$page"
        val headers = mapOf(
            "Origin"     to mainUrl,
            "Referer"    to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
        )

        return try {
            val response = app.get(searchUrl, headers = headers).parsedSafe<LkSearchResponse>() ?: return null

            val results = response.data?.mapNotNull { item ->
                val rawTitle = item.title ?: return@mapNotNull null
                val slug = item.slug ?: return@mapNotNull null

                val cleanTitle = getCleanTitle(rawTitle)
                val href = fixUrl(slug)

                val rawPoster = item.poster?.let { "https://poster.showcdnx.com/wp-content/uploads/$it" }
                val posterUrl = fixPosterUrl(rawPoster)

                val quality = getQualityFromString(item.quality)
                val type = if (item.type?.contains("series", ignoreCase = true) == true) TvType.TvSeries else TvType.Movie

                if (type == TvType.TvSeries) {
                    newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl; this.quality = quality; this.year = item.year
                    }
                } else {
                    newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                        this.posterUrl = posterUrl; this.quality = quality; this.year = item.year
                    }
                }
            } ?: emptyList()

            val totalPages = response.totalPages ?: 1
            newSearchResponseList(results, page < totalPages)
        } catch (e: Exception) {
            null
        }
    }

    // FIX #1: return type harus nullable LoadResponse?
    override suspend fun load(url: String): LoadResponse? {
        var cleanUrl = fixUrl(url)
        var response = app.get(cleanUrl)
        var document = response.document

        if (document.title().contains("Loading", ignoreCase = true) || document.select("#loading").isNotEmpty()) {
            val path = try { URI(cleanUrl).path } catch (e: Exception) { "" }
            // FIX #8: fallback series tetap pakai mainUrl agar ikut override setting
            cleanUrl = if (path.contains("season") || path.contains("episode")) {
                "https://series.lk21.de$path"
            } else {
                "$mainUrl$path"
            }
            response = app.get(cleanUrl)
            document = response.document
        }

        val redirectButton = document.select("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)").first()
        if (redirectButton != null) {
            val newUrl = redirectButton.attr("href")
            if (newUrl.isNotEmpty()) {
                cleanUrl = fixUrl(newUrl)
                if (cleanUrl.contains("series") || cleanUrl.contains("nontondrama")) {
                    val path = try { URI(cleanUrl).path } catch (e: Exception) { "" }
                    cleanUrl = "https://series.lk21.de$path"
                }
                response = app.get(cleanUrl)
                document = response.document
            }
        }

        val rawTitle     = document.select("h1.entry-title, h1.page-title, div.movie-info h1").text().trim()
        val title        = getCleanTitle(rawTitle)
        val plot         = document.select("div.synopsis, div.entry-content p").text().trim()
        val rawPoster    = document.select("meta[property='og:image']").attr("content")
            .ifEmpty { document.select("div.poster img").attr("src") }
        val fallbackPoster = fixPosterUrl(rawPoster)
        val ratingText   = document.select("span.rating-value").text()
            .ifEmpty { document.select("div.info-tag").text() }
        val ratingScore  = Regex("(\\d\\.\\d)").find(ratingText)?.value
        val year         = document.select("span.year").text().toIntOrNull()
            ?: Regex("(\\d{4})").find(document.select("div.info-tag").text())?.value?.toIntOrNull()
            ?: Regex("\\b(\\d{4})\\b").find(rawTitle)?.value?.toIntOrNull()
        val tags         = document.select("div.tag-list a, div.genre a").map { it.text() }
        val actors       = document.select("div.detail p:contains(Bintang Film) a, div.cast a")
            .map { ActorData(Actor(it.text(), "")) }
        val recommendations = document.select(
            "div.related-video li.slider article, div.mob-related-series li.slider article"
        ).mapNotNull { toSearchResult(it) }

        val episodes   = ArrayList<Episode>()
        val jsonScript = document.select("script#season-data").html()

        if (jsonScript.isNotBlank()) {
            val slugs   = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()
            val titles  = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()
            val epNos   = Regex("\"episode_no\"\\s*:\\s*(\\d+)").findAll(jsonScript).map { it.groupValues[1].toIntOrNull() }.toList()
            val sNos    = Regex("\"s\"\\s*:\\s*(\\d+)").findAll(jsonScript).map { it.groupValues[1].toIntOrNull() }.toList()
            val posters = Regex("\"poster\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()
            val plots   = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()
            val dates   = Regex("\"release_date\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()

            for (i in slugs.indices) {
                episodes.add(newEpisode(fixUrl(slugs[i])) {
                    this.name        = titles.getOrNull(i) ?: "Episode ${i + 1}"
                    this.season      = sNos.getOrNull(i)
                    this.episode     = epNos.getOrNull(i)
                    this.posterUrl   = posters.getOrNull(i)?.takeIf { it.isNotBlank() } ?: fallbackPoster
                    this.description = plots.getOrNull(i)
                    addDate(dates.getOrNull(i), format = "yyyy-MM-dd")
                })
            }
        }

        if (episodes.isEmpty()) {
            document.select("ul.episodes li a, div.mob-list-eps a, .movie-action a[href*='episode']").forEach {
                val href = it.attr("href")
                if (href.isNotBlank() && href.contains("episode", ignoreCase = true)) {
                    episodes.add(newEpisode(fixUrl(href)) {
                        this.name    = it.text().trim().ifEmpty { "Play Episode" }
                        this.episode = Regex("(?i)Episode\\s+(\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                        this.posterUrl = fallbackPoster
                    })
                }
            }
        }

        // TMDB dipanggil di Load untuk Banner Background (Aman, hanya 1 request)
        var tmdbPoster: String? = null
        var tmdbBackdrop: String? = null
        try {
            val encodedTitle  = URLEncoder.encode(title, "UTF-8")
            val tmdbSearchUrl = "https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$encodedTitle"
            val tmdbRes       = app.get(tmdbSearchUrl).parsedSafe<TmdbSearchResponse>()
            val match         = tmdbRes?.results?.firstOrNull {
                val resYear = (it.release_date ?: it.first_air_date)?.take(4)?.toIntOrNull()
                year == null || resYear == null || resYear == year
            } ?: tmdbRes?.results?.firstOrNull()
            if (match != null) {
                tmdbPoster   = match.poster_path?.let   { "https://image.tmdb.org/t/p/original$it" }
                tmdbBackdrop = match.backdrop_path?.let { "https://image.tmdb.org/t/p/original$it" }
            }
        } catch (e: Exception) {}

        var trailerUrl = document.select("iframe[src*='youtube.com']").attr("src")
        if (trailerUrl.isNullOrEmpty()) trailerUrl = document.select("a.btn-trailer, a:contains(Trailer)").attr("href")
        if (trailerUrl.isNullOrEmpty()) trailerUrl = Regex("youtube\\.com/embed/([a-zA-Z0-9_-]+)").find(document.html())?.groupValues?.get(1) ?: ""
        val ytIdRegex       = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})")
        val ytId            = ytIdRegex.find(trailerUrl)?.groupValues?.get(1) ?: trailerUrl.takeIf { it.length == 11 }
        val finalTrailerUrl = if (!ytId.isNullOrEmpty()) "https://www.youtube.com/watch?v=$ytId" else null

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl           = tmdbPoster ?: fallbackPoster
                this.backgroundPosterUrl = tmdbBackdrop ?: tmdbPoster ?: fallbackPoster
                this.plot = plot; this.year = year
                this.score = Score.from(ratingScore, 10)
                this.tags = tags; this.actors = actors; this.recommendations = recommendations
                if (!finalTrailerUrl.isNullOrEmpty())
                    this.trailers.add(TrailerData(extractorUrl = finalTrailerUrl, referer = null, raw = false))
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
                this.posterUrl           = tmdbPoster ?: fallbackPoster
                this.backgroundPosterUrl = tmdbBackdrop ?: tmdbPoster ?: fallbackPoster
                this.plot = plot; this.year = year
                this.score = Score.from(ratingScore, 10)
                this.tags = tags; this.actors = actors; this.recommendations = recommendations
                if (!finalTrailerUrl.isNullOrEmpty())
                    this.trailers.add(TrailerData(extractorUrl = finalTrailerUrl, referer = null, raw = false))
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var currentUrl = data
        var response   = app.get(currentUrl)
        var document   = response.document

        if (document.title().contains("Loading", ignoreCase = true) || document.select("#loading").isNotEmpty()) {
            val path   = try { URI(currentUrl).path } catch (e: Exception) { "" }
            currentUrl = "https://tv4.nontondrama.my$path"
            response   = app.get(currentUrl)
            document   = response.document
        }

        val redirectButton = document.select("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)").first()
        if (redirectButton != null && redirectButton.attr("href").isNotEmpty()) {
            currentUrl = fixUrl(redirectButton.attr("href"))
            if (currentUrl.contains("series") || currentUrl.contains("nontondrama")) {
                val path   = try { URI(currentUrl).path } catch (e: Exception) { "" }
                currentUrl = "https://tv4.nontondrama.my$path"
            }
            document = app.get(currentUrl).document
        }

        val playerLinks = document.select("ul#player-list li a")
            .mapNotNull { it.attr("data-url").takeIf { u -> u.isNotBlank() } }

        val host       = try { URI(currentUrl).host } catch (e: Exception) { "tv4.nontondrama.my" }
        val baseDomain = host?.split(".")?.takeLast(2)?.joinToString(".")

        val possibleKeys = listOfNotNull(
            host, baseDomain,
            "tv1.lk21official.cc", "tv2.lk21official.cc", "tv3.lk21official.cc",
            "tv4.lk21official.cc", "tv5.lk21official.cc", "tv6.lk21official.cc",
            "tv7.lk21official.cc", "tv8.lk21official.cc", "tv9.lk21official.cc",
            "tv10.lk21official.cc", "lk21official.cc",
            "tv1.nontondrama.my",  "tv2.nontondrama.my",  "tv3.nontondrama.my",
            "tv4.nontondrama.my",  "nontondrama.my",
            "series.lk21.de", "lk21.de", "lk21.party", "gudangvape.com"
        ).distinct()

        val rawSources = mutableListOf<String>()
        playerLinks.forEach { encryptedString ->
            var decoded = ""
            if (encryptedString.startsWith("http") || encryptedString.startsWith("//")) {
                decoded = encryptedString
            } else {
                for (key in possibleKeys) {
                    val attempt = decryptRC4(key, encryptedString)
                    if (attempt.startsWith("http") || attempt.startsWith("//")) {
                        decoded = attempt; break
                    }
                }
            }
            if (decoded.isNotBlank()) rawSources.add(decoded)
        }

        val allSources = rawSources.distinct().map { fixUrl(it) }

        // FIX #7: kalau tidak ada sumber yang berhasil di-decode, return false
        if (allSources.isEmpty()) return false

        // FIX #2: semua extractor sekarang konsisten pakai new-style callback
        allSources.forEach { url ->
            when {
                url.contains("/iframe/turbovip/") -> {
                    val id = url.substringAfter("/iframe/turbovip/").substringBefore("/")
                    try {
                        Lk21TurboExtractor().getUrl(
                            "https://turbovidhls.com/t/$id", currentUrl, subtitleCallback, callback
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                url.contains("/iframe/p2p/") -> {
                    val id = url.substringAfter("/iframe/p2p/").substringBefore("/")
                    try {
                        HowNetworkExtractor().getUrl(
                            "https://cloud.hownetwork.xyz/video.php?id=$id", currentUrl, subtitleCallback, callback
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                url.contains("/iframe/cast/") -> {
                    val id = url.substringAfter("/iframe/cast/").substringBefore("/")
                    try {
                        CastExtractor().getUrl(
                            "https://weneverbeenfree.com/e/$id", currentUrl, subtitleCallback, callback
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                url.contains("/iframe/hydrax/") -> {
                    val id = url.substringAfter("/iframe/hydrax/").substringBefore("/")
                    try {
                        AbyssExtractor().getUrl(
                            "https://abyssplayer.com/?v=$id", currentUrl, subtitleCallback, callback
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return true
    }

    // FIX #9: getVideoInterceptor return nullable Interceptor? (sesuai signature MainAPI)
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"

        return Interceptor { chain ->
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()

            // Bypass Localhost — langsung lanjut tanpa modifikasi
            if (url.contains("127.0.0.1")) {
                return@Interceptor chain.proceed(originalRequest)
            }

            when {
                url.contains("turbovidhls.com") || url.contains("etvp.cc") || url.contains("hownetwork.xyz") -> {
                    val host = try { URI(url).host ?: "" } catch (e: Exception) { "" }
                    val newRequest = originalRequest.newBuilder()
                        .header("User-Agent", mobileUA)
                        .header("Origin",  "https://$host")
                        .header("Referer", "https://$host/")
                        .build()
                    chain.proceed(newRequest)
                }
                url.contains("googleusercontent.com") -> {
                    val response = chain.proceed(originalRequest)
                    if (response.code == 429) {
                        response.close()
                        Thread.sleep(1000L)
                        chain.proceed(originalRequest)
                    } else {
                        response
                    }
                }
                else -> chain.proceed(originalRequest)
            }
        }
    }
}
