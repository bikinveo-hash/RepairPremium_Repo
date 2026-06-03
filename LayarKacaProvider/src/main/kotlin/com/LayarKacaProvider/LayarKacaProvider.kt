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
    override var name    = "LayarKaca21"
    override val hasMainPage = true
    override var lang    = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "latest/"                   to "Film Terbaru",
        "top-series-today/"         to "Series Unggulan",
        "latest-series/"            to "Series Update",
        "populer/"                  to "Top Bulan Ini",
        "nonton-bareng-keluarga/"   to "Nonton Bareng Keluarga",
        "genre/action/"             to "Action Terbaru",
        "genre/romance/"            to "Romance Terbaru",
        "genre/comedy/"             to "Comedy Terbaru",
        "genre/horror/"             to "Horror Terbaru",
        "country/south-korea/"      to "Korea Terbaru",
        "country/thailand/"         to "Thailand Terbaru",
        "country/india/"            to "India Terbaru"
    )

    override var sequentialMainPage      = true
    override var sequentialMainPageDelay = 250L

    // =========================================================================
    // HOMEPAGE
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}"
                  else           "$mainUrl/${request.data}page/$page/"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept"     to "*/*",
            "Referer"    to "$mainUrl/"
        )

        val document = app.get(url, headers = headers).document
        val elements = document.select(
            "div#post-container article, div.grid-archive article, div.widget article, article.item"
        )
        val list = elements.mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request, list, list.isNotEmpty())
    }

    // =========================================================================
    // RC4 DECRYPT
    // =========================================================================
    private fun decryptRC4(key: String, encryptedBase64: String): String {
        return try {
            val cipher   = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val s        = IntArray(256) { it }
            var j        = 0
            for (i in 0..255) {
                j = (j + s[i] + keyBytes[i % keyBytes.size].toInt()) % 256
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            var i = 0; j = 0
            val result = ByteArray(cipher.size)
            for (k in cipher.indices) {
                i = (i + 1) % 256
                j = (j + s[i]) % 256
                val t = s[i]; s[i] = s[j]; s[j] = t
                result[k] = ((cipher[k].toInt() and 0xFF) xor s[(s[i] + s[j]) % 256]).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    private fun getCleanTitle(title: String): String {
        var clean = title.replace(
            Regex("(?i)(nonton serial|nonton film|nonton|sub indo|di lk21|lk21|layarkaca21)"), ""
        )
        clean = clean.replace(Regex("(?i)\\bseason\\s*\\d+.*"), "")
        clean = clean.replace(Regex("\\(\\d{4}\\)"), "")
        return clean.trim()
    }

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var clean = if (url.startsWith("//")) "https:$url" else url
        clean = clean.substringBefore("?")
        return clean.replace(Regex("-\\d{2,4}x\\d{2,4}"), "")
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
        @JsonProperty("data")       val data: List<LkSearchData>?
    )
    data class LkSearchData(
        @JsonProperty("title")   val title: String?,
        @JsonProperty("slug")    val slug: String?,
        @JsonProperty("type")    val type: String?,
        @JsonProperty("poster")  val poster: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("year")    val year: Int?
    )

    // =========================================================================
    // PARSING ITEM FILM
    // =========================================================================
    private fun toSearchResult(element: Element): SearchResponse? {
        val rawTitle = element
            .select("h3.poster-title, h2.entry-title, h1.page-title, div.title")
            .text().trim()
        if (rawTitle.isEmpty()) return null

        val href      = fixUrl(element.select("a").first()?.attr("href") ?: return null)
        val imgEl     = element.select("img").first()
        val rawPoster = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: imgEl?.attr("src")

        val posterUrl  = fixPosterUrl(rawPoster)
        val cleanTitle = getCleanTitle(rawTitle)
        val yearText   = element.select("div.year, span.year").text()
        val year       = yearText.toIntOrNull()
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

    // =========================================================================
    // SEARCH
    // =========================================================================
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val searchUrl = "https://gudangvape.com/search.php?s=${
            URLEncoder.encode(query, "UTF-8")
        }&page=$page"
        val headers = mapOf(
            "Origin"     to mainUrl,
            "Referer"    to "$mainUrl/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )

        val resp = tryParseJson<LkSearchResponse>(
            app.get(searchUrl, headers = headers).text
        ) ?: return null

        val items = resp.data?.mapNotNull { item ->
            val title = item.title ?: return@mapNotNull null
            val slug  = item.slug  ?: return@mapNotNull null
            val url   = fixUrl("$mainUrl/$slug/")
            val type  = if (item.type?.contains("series", ignoreCase = true) == true)
                TvType.TvSeries else TvType.Movie

            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, url, type) {
                    this.posterUrl = item.poster
                    this.quality   = getQualityFromString(item.quality)
                    this.year      = item.year
                }
            } else {
                newMovieSearchResponse(title, url, type) {
                    this.posterUrl = item.poster
                    this.quality   = getQualityFromString(item.quality)
                    this.year      = item.year
                }
            }
        } ?: emptyList()

        return newSearchResponseList(items, (resp.totalPages ?: 1) > page)
    }

    // =========================================================================
    // LOAD (DETAIL HALAMAN)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.trimEnd('/')
        val headers  = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer"    to "$mainUrl/"
        )

        val document = app.get("$cleanUrl/", headers = headers).document

        val rawTitle = document.select("h1.entry-title, h1.page-title, h1").first()?.text()?.trim()
            ?: return null
        val title = getCleanTitle(rawTitle)

        val fallbackPoster = fixPosterUrl(
            document.select("div.poster img, div.thumb img").first()
                ?.let { it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src") }
        )

        val plot = document.select("div.entry-content p, div.synopsis p, div.plot p")
            .firstOrNull()?.text()?.trim()

        val year = document.select("span.year, div.year, a[href*='/tahun/']")
            .text().trim()
            .let { Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val ratingText  = document.select("span.imdb-rating, div.rating span, span.rating").text()
        val ratingScore = Regex("([\\d.]+)").find(ratingText)?.groupValues?.get(1)?.toDoubleOrNull()

        val tags = document.select("div.genre a, a[href*='/genre/']").map { it.text().trim() }.distinct()

        val actors = document.select("div.cast a, div.actor a").map {
            ActorData(Actor(it.text().trim(), it.select("img").attr("src").takeIf { s -> s.isNotBlank() }))
        }

        val recommendations = document.select("div.related-post article, div.related article").mapNotNull {
            toSearchResult(it)
        }

        // Episodes (TvSeries)
        val episodes = document.select("ul.lst li, div.eps-list a, ul.episode-list li a").mapNotNull { el ->
            val epHref = el.attr("href").takeIf { h -> h.isNotBlank() } ?: return@mapNotNull null
            val epName = el.text().trim().takeIf { t -> t.isNotBlank() }
            val season = Regex("season[- ](\\d+)", RegexOption.IGNORE_CASE)
                .find(el.text() + epHref)?.groupValues?.get(1)?.toIntOrNull()
            val epNum  = Regex("(?:episode|ep)[- ](\\d+)", RegexOption.IGNORE_CASE)
                .find(el.text() + epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)").find(el.text())?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(fixUrl(epHref)) {
                this.name    = epName
                this.season  = season
                this.episode = epNum
            }
        }

        // TMDB enrichment
        var tmdbPoster: String?   = null
        var tmdbBackdrop: String? = null
        try {
            val tmdbQuery = URLEncoder.encode(title, "UTF-8")
            val isSeries  = episodes.isNotEmpty()
            val tmdbType  = if (isSeries) "tv" else "movie"
            val tmdbUrl   = "https://api.themoviedb.org/3/search/$tmdbType?query=$tmdbQuery" +
                            (if (year != null) "&year=$year" else "") +
                            "&api_key=5b9e4e38a7b67c2d3b8e6f1c4a0d9f2a"

            val tmdbResp = tryParseJson<TmdbSearchResponse>(
                app.get(tmdbUrl, headers = headers).text
            )
            val match = tmdbResp?.results?.firstOrNull()
            if (match != null) {
                tmdbPoster   = match.poster_path?.let   { "https://image.tmdb.org/t/p/original$it" }
                tmdbBackdrop = match.backdrop_path?.let { "https://image.tmdb.org/t/p/original$it" }
            }
        } catch (_: Exception) {}

        // Trailer
        var trailerUrl = document.select("iframe[src*='youtube.com']").attr("src")
        if (trailerUrl.isNullOrEmpty())
            trailerUrl = document.select("a.btn-trailer, a:contains(Trailer)").attr("href")
        if (trailerUrl.isNullOrEmpty())
            trailerUrl = Regex("youtube\\.com/embed/([a-zA-Z0-9_-]+)")
                .find(document.html())?.groupValues?.get(1) ?: ""
        val ytId = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})")
            .find(trailerUrl)?.groupValues?.get(1)
            ?: trailerUrl.takeIf { it.length == 11 }
        val finalTrailerUrl = if (!ytId.isNullOrEmpty())
            "https://www.youtube.com/watch?v=$ytId" else null

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl           = tmdbPoster ?: fallbackPoster
                this.backgroundPosterUrl = tmdbBackdrop ?: tmdbPoster ?: fallbackPoster
                this.plot  = plot
                this.year  = year
                this.score = Score.from(ratingScore, 10)
                this.tags  = tags
                this.actors = actors
                this.recommendations = recommendations
                if (!finalTrailerUrl.isNullOrEmpty())
                    this.trailers.add(TrailerData(extractorUrl = finalTrailerUrl, referer = null, raw = false))
            }
        } else {
            newMovieLoadResponse(title, cleanUrl, TvType.Movie, cleanUrl) {
                this.posterUrl           = tmdbPoster ?: fallbackPoster
                this.backgroundPosterUrl = tmdbBackdrop ?: tmdbPoster ?: fallbackPoster
                this.plot  = plot
                this.year  = year
                this.score = Score.from(ratingScore, 10)
                this.tags  = tags
                this.actors = actors
                this.recommendations = recommendations
                if (!finalTrailerUrl.isNullOrEmpty())
                    this.trailers.add(TrailerData(extractorUrl = finalTrailerUrl, referer = null, raw = false))
            }
        }
    }

    // =========================================================================
    // LOAD LINKS
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var currentUrl = data
        var document   = app.get(currentUrl).document

        // Fallback ke nontondrama jika halaman loading
        if (document.title().contains("Loading", ignoreCase = true) ||
            document.select("#loading").isNotEmpty()
        ) {
            val path   = try { URI(currentUrl).path } catch (_: Exception) { "" }
            currentUrl = "https://tv4.nontondrama.my$path"
            document   = app.get(currentUrl).document
        }

        // Ikuti tombol redirect jika ada
        val redirectButton = document
            .select("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)")
            .first()
        if (redirectButton != null && redirectButton.attr("href").isNotEmpty()) {
            currentUrl = fixUrl(redirectButton.attr("href"))
            if (currentUrl.contains("series") || currentUrl.contains("nontondrama")) {
                val path   = try { URI(currentUrl).path } catch (_: Exception) { "" }
                currentUrl = "https://tv4.nontondrama.my$path"
            }
            document = app.get(currentUrl).document
        }

        val playerLinks = document.select("ul#player-list li a")
            .mapNotNull { it.attr("data-url").takeIf { u -> u.isNotBlank() } }

        val host       = try { URI(currentUrl).host ?: "" } catch (_: Exception) { "" }
        val baseDomain = host.split(".").takeLast(2).joinToString(".")

        val possibleKeys = listOfNotNull(
            host.takeIf { it.isNotEmpty() },
            baseDomain.takeIf { it.isNotEmpty() },
            "tv1.lk21official.cc",  "tv2.lk21official.cc",  "tv3.lk21official.cc",
            "tv4.lk21official.cc",  "tv5.lk21official.cc",  "tv6.lk21official.cc",
            "tv7.lk21official.cc",  "tv8.lk21official.cc",  "tv9.lk21official.cc",
            "tv10.lk21official.cc", "lk21official.cc",
            "tv1.nontondrama.my",   "tv2.nontondrama.my",   "tv3.nontondrama.my",
            "tv4.nontondrama.my",   "nontondrama.my",
            "series.lk21.de", "lk21.de", "lk21.party", "gudangvape.com"
        ).distinct()

        // FIX #6: RC4 brute-force dengan key caching agar tidak brute-force ulang
        var cachedKey: String? = null
        val rawSources = mutableListOf<String>()

        playerLinks.forEach { encryptedString ->
            if (encryptedString.startsWith("http") || encryptedString.startsWith("//")) {
                rawSources.add(encryptedString)
                return@forEach
            }

            // Coba key yang terakhir berhasil dulu
            if (cachedKey != null) {
                val attempt = decryptRC4(cachedKey!!, encryptedString)
                if (attempt.startsWith("http") || attempt.startsWith("//")) {
                    rawSources.add(attempt)
                    return@forEach
                } else {
                    cachedKey = null   // key tidak cocok, reset cache
                }
            }

            // Brute-force key
            for (key in possibleKeys) {
                val attempt = decryptRC4(key, encryptedString)
                if (attempt.startsWith("http") || attempt.startsWith("//")) {
                    cachedKey = key   // simpan key yang berhasil
                    rawSources.add(attempt)
                    break
                }
            }
        }

        val allSources = rawSources.distinct().map { fixUrl(it) }

        // FIX #1 & #5: Panggil semua extractor via getSafeUrl (bukan getUrl + forEach)
        allSources.forEach { url ->
            when {
                url.contains("/iframe/turbovip/") -> {
                    val id = url.substringAfter("/iframe/turbovip/").substringBefore("/")
                    Lk21TurboExtractor().getSafeUrl(
                        "https://turbovidhls.com/t/$id",
                        currentUrl,
                        subtitleCallback,
                        callback
                    )
                }
                url.contains("/iframe/p2p/") -> {
                    val id = url.substringAfter("/iframe/p2p/").substringBefore("/")
                    HowNetworkExtractor().getSafeUrl(
                        "https://cloud.hownetwork.xyz/video.php?id=$id",
                        currentUrl,
                        subtitleCallback,
                        callback
                    )
                }
                url.contains("/iframe/cast/") -> {
                    val id = url.substringAfter("/iframe/cast/").substringBefore("/")
                    CastExtractor().getSafeUrl(
                        "https://weneverbeenfree.com/e/$id",
                        currentUrl,
                        subtitleCallback,
                        callback
                    )
                }
                url.contains("/iframe/hydrax/") -> {
                    val id = url.substringAfter("/iframe/hydrax/").substringBefore("/")
                    AbyssExtractor().getSafeUrl(
                        "https://abyssplayer.com/?v=$id",
                        currentUrl,
                        subtitleCallback,
                        callback
                    )
                }
            }
        }

        return allSources.isNotEmpty()
    }

    // =========================================================================
    // VIDEO INTERCEPTOR
    // =========================================================================
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"

        return Interceptor { chain ->
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()

            // Bypass localhost (proxy Hydrax)
            if (url.contains("127.0.0.1")) {
                return@Interceptor chain.proceed(originalRequest)
            }

            when {
                url.contains("turbovidhls.com") ||
                url.contains("etvp.cc")         ||
                url.contains("hownetwork.xyz")  -> {
                    val host = try { URI(url).host ?: "" } catch (_: Exception) { "" }
                    chain.proceed(
                        originalRequest.newBuilder()
                            .header("User-Agent", mobileUA)
                            .header("Origin",     "https://$host")
                            .header("Referer",    "https://$host/")
                            .build()
                    )
                }

                // Retry 429 untuk Google CDN (googleusercontent)
                url.contains("googleusercontent.com") -> {
                    var response   = chain.proceed(originalRequest)
                    var retries    = 0
                    val maxRetries = 4
                    val baseDelay  = 600L

                    while (response.code == 429 && retries < maxRetries) {
                        response.close()
                        Thread.sleep(baseDelay * (retries + 1))
                        response = chain.proceed(originalRequest)
                        retries++
                    }
                    response
                }

                else -> chain.proceed(originalRequest)
            }
        }
    }
}
