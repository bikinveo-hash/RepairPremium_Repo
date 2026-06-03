package com.LayarKacaProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv10.lk21official.cc"
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = ArrayList<HomePageList>()

        suspend fun addWidget(sectionTitle: String, selector: String) {
            val elements = document.select(selector).toList()
            val list = coroutineScope {
                elements.map { async { toSearchResult(it) } }.awaitAll().filterNotNull()
            }
            if (list.isNotEmpty()) items.add(HomePageList(sectionTitle, list))
        }

        addWidget("Film Terbaru",     "div.widget[data-type='latest-movies'] li.slider article")
        addWidget("Series Unggulan",  "div.widget[data-type='top-series-today'] li.slider article")
        addWidget("Horror Terbaru",   "div.widget[data-type='latest-horror'] li.slider article")
        addWidget("Daftar Lengkap",   "div#post-container article")

        return newHomePageResponse(items)
    }

    private suspend fun toSearchResult(element: Element): SearchResponse? {
        val rawTitle = element.select("h3.poster-title, h2.entry-title, h1.page-title, div.title").text().trim()
        if (rawTitle.isEmpty()) return null
        val href = fixUrl(element.select("a").first()?.attr("href") ?: return null)

        val imgElement = element.select("img").first()
        val rawPoster = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")
        val fallbackPoster = fixPosterUrl(rawPoster)

        val cleanTitle = getCleanTitle(rawTitle)
        val yearText = element.select("div.year, span.year").text()
        val year = yearText.toIntOrNull()
            ?: Regex("\\b(\\d{4})\\b").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        var hdPoster: String? = null
        try {
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val tmdbRes = app.get(
                "https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$encodedTitle"
            ).parsedSafe<TmdbSearchResponse>()
            val match = tmdbRes?.results?.firstOrNull {
                val resYear = (it.release_date ?: it.first_air_date)?.take(4)?.toIntOrNull()
                year == null || resYear == null || resYear == year
            } ?: tmdbRes?.results?.firstOrNull()
            hdPoster = match?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        } catch (e: Exception) {}

        val posterUrl = hdPoster ?: fallbackPoster
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
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        )
        
        try {
            val response = app.get(searchUrl, headers = headers).parsedSafe<LkSearchResponse>() ?: return null

            val results = coroutineScope {
                response.data?.mapNotNull { item ->
                    async {
                        val rawTitle = item.title ?: return@async null
                        val slug = item.slug ?: return@async null

                        val cleanTitle = getCleanTitle(rawTitle)
                        val href = fixUrl(slug)
                        
                        val rawPoster = item.poster?.let { "https://poster.showcdnx.com/wp-content/uploads/$it" }
                        val fallbackPoster = fixPosterUrl(rawPoster)

                        var hdPoster: String? = null
                        try {
                            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                            val tmdbRes = app.get(
                                "https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$encodedTitle"
                            ).parsedSafe<TmdbSearchResponse>()
                            
                            val match = tmdbRes?.results?.firstOrNull {
                                val resYear = (it.release_date ?: it.first_air_date)?.take(4)?.toIntOrNull()
                                item.year == null || resYear == null || resYear == item.year
                            } ?: tmdbRes?.results?.firstOrNull()
                            
                            hdPoster = match?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        } catch (e: Exception) {}

                        val posterUrl = hdPoster ?: fallbackPoster
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
                    }
                }?.awaitAll()?.filterNotNull() ?: emptyList()
            }

            val totalPages = response.totalPages ?: 1
            return newSearchResponseList(results, page < totalPages)
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var cleanUrl = fixUrl(url)
        var response = app.get(cleanUrl)
        var document = response.document

        if (document.title().contains("Loading", ignoreCase = true) || document.select("#loading").isNotEmpty()) {
            val path = try { URI(cleanUrl).path } catch (e: Exception) { "" }
            cleanUrl = if (path.contains("season") || path.contains("episode")) {
                "https://series.lk21.de$path"
            } else {
                "https://tv10.lk21official.cc$path"
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
            val slugs  = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()
            val titles = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").findAll(jsonScript).map { it.groupValues[1] }.toList()
            val epNos  = Regex("\"episode_no\"\\s*:\\s*(\\d+)").findAll(jsonScript).map { it.groupValues[1].toIntOrNull() }.toList()
            val sNos   = Regex("\"s\"\\s*:\\s*(\\d+)").findAll(jsonScript).map { it.groupValues[1].toIntOrNull() }.toList()
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
        val ytIdRegex      = Regex("(?:youtube\\.com/(?:watch\\?v=|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})")
        val ytId           = ytIdRegex.find(trailerUrl)?.groupValues?.get(1) ?: trailerUrl.takeIf { it.length == 11 }
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
        
        allSources.forEach { url ->
            // Routing TurboVIP
            if (url.contains("/iframe/turbovip/")) {
                val id = url.substringAfter("/iframe/turbovip/").substringBefore("/")
                Lk21TurboExtractor().getUrl("https://turbovidhls.com/t/$id", currentUrl)
                    ?.forEach { callback.invoke(it) }
            } 
            // Routing HowNetwork (P2P)
            else if (url.contains("/iframe/p2p/")) {
                val id = url.substringAfter("/iframe/p2p/").substringBefore("/")
                HowNetworkExtractor().getUrl("https://cloud.hownetwork.xyz/video.php?id=$id", currentUrl)
                    ?.forEach { callback.invoke(it) }
            } 
            // Routing CAST
            else if (url.contains("/iframe/cast/")) {
                val id = url.substringAfter("/iframe/cast/").substringBefore("/")
                val castUrl = "https://weneverbeenfree.com/e/$id"
                try {
                    CastExtractor().getUrl(castUrl, null)?.forEach { callback.invoke(it) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Routing HYDRAX (Menangkap URL Interceptor)
            else if (url.contains("/iframe/hydrax/")) {
                val id = url.substringAfter("/iframe/hydrax/").substringBefore("/")
                val hydraxUrl = "https://abyssplayer.com/?v=$id"
                try {
                    AbyssExtractor().getUrl(hydraxUrl, currentUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return true
    }

    // =========================================================================
    // VIDEO INTERCEPTOR (THE 64KB DECRYPTER)
    // =========================================================================
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"

        return Interceptor { chain ->
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()

            // ── BYPASS HYDRAX (Presisi 64KB) ──
            if (url.contains("hydrax_proxy")) {
                val uri = android.net.Uri.parse(url)
                val srcUrl = uri.getQueryParameter("src")!!
                val keyHex = uri.getQueryParameter("key")!!

                val rangeHeader = originalRequest.header("Range")
                var reqStart = 0L
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    reqStart = rangeHeader.substring(6).split("-")[0].toLongOrNull() ?: 0L
                }

                // Meneruskan permintaan ExoPlayer persis apa adanya ke URL Source
                val newRequest = originalRequest.newBuilder()
                    .url(srcUrl)
                    .header("Origin", "https://abyssplayer.com")
                    .header("Referer", "https://abyssplayer.com/")
                    .header("User-Agent", mobileUA)
                    .build()

                val response = chain.proceed(newRequest)
                if (!response.isSuccessful) return@Interceptor response

                val body = response.body ?: return@Interceptor response

                // Jika ExoPlayer meminta data yang letaknya melebihi 64KB, berikan langsung tanpa sentuh AES!
                if (reqStart >= 65536) {
                    return@Interceptor response
                }

                // Jika meminta dari awal, kita intersepsi dan dekripsi 64KB pertamanya
                val decBody = object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    
                    override fun source(): BufferedSource {
                        return object : ForwardingSource(body.source()) {
                            var currentOffset = reqStart
                            val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            val secretKey = SecretKeySpec(keyBytes, "AES")
                            val ivSpec = IvParameterSpec(keyBytes.copyOfRange(0, 16))
                            
                            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                                init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                                // Sinkronisasi mesin AES-CTR jika ExoPlayer melompat ke tengah 64KB
                                if (reqStart > 0) {
                                    val skipBuffer = ByteArray(4096)
                                    var skipped = 0L
                                    while (skipped < reqStart) {
                                        val toSkip = minOf(4096L, reqStart - skipped).toInt()
                                        update(skipBuffer, 0, toSkip)
                                        skipped += toSkip
                                    }
                                }
                            }

                            override fun read(sink: Buffer, byteCount: Long): Long {
                                val buffer = Buffer()
                                val bytesRead = super.read(buffer, byteCount)
                                
                                if (bytesRead != -1L) {
                                    if (currentOffset < 65536) {
                                        // Berapa byte yang harus didekripsi di dalam bongkahan ini?
                                        val bytesToDecrypt = minOf(bytesRead, 65536 - currentOffset).toInt()
                                        val dataToDecrypt = buffer.readByteArray(bytesToDecrypt.toLong())
                                        
                                        val decrypted = cipher.update(dataToDecrypt)
                                        if (decrypted != null) {
                                            sink.write(decrypted)
                                        }
                                        
                                        // Sisa byte yang tidak dienkripsi ditulis murni
                                        if (buffer.size > 0) {
                                            sink.write(buffer, buffer.size)
                                        }
                                    } else {
                                        // 100% murni (sudah lewat blok enkripsi 64KB)
                                        sink.write(buffer, buffer.size)
                                    }
                                    currentOffset += bytesRead
                                }
                                return bytesRead
                            }
                        }.buffer()
                    }
                }
                return@Interceptor response.newBuilder().body(decBody).build()
            }
            
            when {
                // ── Turbovidhls & etvp & hownetwork ──────
                url.contains("turbovidhls.com") || url.contains("etvp.cc") || url.contains("hownetwork.xyz") -> {
                    val newRequest = originalRequest.newBuilder()
                        .header("User-Agent", mobileUA)
                        .header("Origin",  "https://${try { URI(url).host } catch (e: Exception) { "" }}")
                        .header("Referer", "https://${try { URI(url).host } catch (e: Exception) { "" }}/")
                        .build()
                    chain.proceed(newRequest)
                }
                // ── Google Drive CDN ───────
                url.contains("googleusercontent.com") -> {
                    var response  = chain.proceed(originalRequest)
                    var retries   = 0
                    val maxRetries = 4
                    val baseDelay  = 600L

                    while (response.code == 429 && retries < maxRetries) {
                        response.close()
                        val delay = baseDelay * (retries + 1)
                        Thread.sleep(delay)
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
