package com.LayarKacaProvider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv10.lk21official.cc"
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun decryptRC4(key: String, encryptedBase64: String): String {
        return try {
            val cipher = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0..255) {
                j = (j + s[i] + key[i % key.length].code) % 256
                val temp = s[i]
                s[i] = s[j]
                s[j] = temp
            }
            var i = 0
            j = 0
            val result = ByteArray(cipher.size)
            for (k in cipher.indices) {
                i = (i + 1) % 256
                j = (j + s[i]) % 256
                val temp = s[i]
                s[i] = s[j]
                s[j] = temp
                val kStream = s[(s[i] + s[j]) % 256]
                result[k] = ((cipher[k].toInt() and 0xFF) xor kStream).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            "" 
        }
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

        addWidget("Film Terbaru", "div.widget[data-type='latest-movies'] li.slider article")
        addWidget("Series Unggulan", "div.widget[data-type='top-series-today'] li.slider article")
        addWidget("Horror Terbaru", "div.widget[data-type='latest-horror'] li.slider article")
        addWidget("Daftar Lengkap", "div#post-container article")

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
        val year = yearText.toIntOrNull() ?: Regex("\\b(\\d{4})\\b").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        var hdPoster: String? = null
        try {
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val tmdbRes = app.get("https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$encodedTitle").parsedSafe<TmdbSearchResponse>()
            val match = tmdbRes?.results?.firstOrNull { 
                val relDate = it.release_date ?: it.first_air_date
                val relYear = relDate?.substringBefore("-")?.toIntOrNull()
                relYear == year || year == null
            }
            val path = match?.poster_path ?: match?.backdrop_path
            if (path != null) {
                hdPoster = "https://image.tmdb.org/t/p/w500$path"
            }
        } catch (e: Exception) { }

        val finalPoster = hdPoster ?: fallbackPoster ?: ""
        val ratingText = element.select("div.rating, span.rating").text().replace(",", ".").trim()
        val ratingScore = ratingText.toDoubleOrNull()

        return if (href.contains("/series/") || element.select("span.label-TV").isNotEmpty()) {
            newAnimeSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = finalPoster
                addYear(year)
                if (ratingScore != null) {
                    this.score = Score.from(ratingScore, 10)
                }
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = finalPoster
                addYear(year)
                if (ratingScore != null) {
                    this.score = Score.from(ratingScore, 10)
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search?s=$encodedQuery"
        val document = app.get(searchUrl).document
        val elements = document.select("div#post-container article, div.search-item, div.poster-wrapper").toList()
        
        return coroutineScope {
            elements.map { async { toSearchResult(it) } }.awaitAll().filterNotNull()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val rawTitle = document.select("h1.entry-title, h1.page-title, div.movie-info h1").text().trim()
        val cleanTitle = getCleanTitle(rawTitle)
        
        val posterElement = document.select("div.poster img, div.detail img").first()
        val rawPoster = posterElement?.attr("data-src") ?: posterElement?.attr("src")
        val poster = fixPosterUrl(rawPoster) ?: ""

        val yearText = document.select("div.info-tag span:contains(20), div.meta-info p:contains(Release)").text()
        val year = Regex("\\b(\\d{4})\\b").find(yearText ?: rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val ratingText = document.select("div.info-tag span:contains(.), div.rating").text().replace(",", ".").trim()
        val ratingScore = Regex("(\\d+\\.\\d+|\\d+)").find(ratingText)?.groupValues?.get(1)?.toDoubleOrNull()

        val synopsis = document.select("div.synopsis, div.entry-content p, div.meta-info div.synopsis").text().trim()
        
        val tags = document.select("div.tag-list span.tag a, div.genre a").map { it.text().trim() }
        val actors = document.select("div.detail p:contains(Bintang) a, div.meta-info p:contains(Actors) a").map { it.text().trim() }

        val recommendations = document.select("ul#you-may-also-like li, div.mob-related-video li.slider").mapNotNull { recElement ->
            val recTitle = recElement.select("h3, span.video-title").text().trim()
            val recHref = recElement.select("a").first()?.attr("href") ?: return@mapNotNull null
            val recImg = recElement.select("img").first()?.attr("src") ?: ""
            newMovieSearchResponse(getCleanTitle(recTitle), fixUrl(recHref), TvType.Movie) {
                this.posterUrl = fixPosterUrl(recImg)
            }
        }

        val trailerButton = document.select("a.yt-lightbox, div.player-action a:contains(Trailer)").first()
        val finalTrailerUrl = trailerButton?.attr("data-url") ?: trailerButton?.attr("href")

        return if (url.contains("/series/") || document.select("select#episode-select").isNotEmpty()) {
            val episodes = ArrayList<Episode>()
            val episodeElements = document.select("select#episode-select option, div.episode-list a")
            
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEach { ep ->
                    val epHref = ep.attr("value").takeIf { it.isNotBlank() } ?: ep.attr("href")
                    val epTitle = ep.text().trim()
                    val epNum = epTitle.replace(Regex("\\D+"), "").toIntOrNull() ?: 1
                    if (!epHref.isNullOrBlank()) {
                        episodes.add(Episode(data = fixUrl(epHref), name = epTitle, episode = epNum))
                    }
                }
            } else {
                episodes.add(Episode(data = url, name = "Episode 1", episode = 1))
            }

            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.score = Score.from(ratingScore, 10)
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
                if (!finalTrailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(extractorUrl = finalTrailerUrl, referer = null, raw = false))
                }
            }
        } else {
            newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
                this.score = Score.from(ratingScore, 10)
                this.tags = tags
                this.actors = actors
                this.recommendations = recommendations
                if (!finalTrailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(extractorUrl = finalTrailerUrl, referer = null, raw = false))
                }
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
        var response = app.get(currentUrl)
        var document = response.document

        if (document.title().contains("Loading", ignoreCase = true) || document.select("#loading").isNotEmpty()) {
            val path = try { URI(currentUrl).path } catch(e: Exception) { "" }
            currentUrl = "https://tv4.nontondrama.my$path"
            response = app.get(currentUrl)
            document = response.document
        }

        val redirectButton = document.select("a:contains(Buka Sekarang), a.btn:contains(Nontondrama)").first()
        if (redirectButton != null && redirectButton.attr("href").isNotEmpty()) {
            currentUrl = fixUrl(redirectButton.attr("href"))
            if (currentUrl.contains("series") || currentUrl.contains("nontondrama")) {
                val path = try { URI(currentUrl).path } catch(e: Exception) { "" }
                currentUrl = "https://tv4.nontondrama.my$path"
            }
            document = app.get(currentUrl).document
        }

        val rawSources = mutableListOf<String>()

        // 1. TANGKAP RC4 + DETEKSI LINK STRING POLOS (Supaya Server Cast Tidak Hilang)
        val playerLinks = document.select("ul#player-list li a").mapNotNull { it.attr("data-url").takeIf { u -> u.isNotBlank() } }
        val host = try { URI(currentUrl).host } catch(e: Exception) { "tv4.nontondrama.my" }
        val baseDomain = host?.split(".")?.takeLast(2)?.joinToString(".")
        
        val possibleKeys = listOfNotNull(
            host, baseDomain, 
            "tv1.lk21official.cc", "tv2.lk21official.cc", "tv3.lk21official.cc", "tv4.lk21official.cc", "tv5.lk21official.cc",
            "tv6.lk21official.cc", "tv7.lk21official.cc", "tv8.lk21official.cc", "tv9.lk21official.cc", "tv10.lk21official.cc",
            "lk21official.cc", "tv1.nontondrama.my", "tv2.nontondrama.my", "tv3.nontondrama.my", "tv4.nontondrama.my", "nontondrama.my",
            "series.lk21.de", "lk21.de", "lk21.party", "gudangvape.com"
        ).distinct()

        playerLinks.forEach { encryptedString ->
            var decoded = ""
            val trimmed = encryptedString.trim()
            
            // Aturan Main Baru: Jika data-url ternyata teks biasa/path tanpa enkripsi, langsung loloskan
            if (trimmed.startsWith("http") || trimmed.startsWith("//") || trimmed.contains("playeriframe.sbs") || trimmed.contains("iframe")) {
                decoded = trimmed
            } else {
                for (key in possibleKeys) {
                    var attempt = decryptRC4(key, trimmed).trim()
                    // Jika hasil dekripsi mengandung domain target atau format path iframe, loloskan langsung
                    if (attempt.startsWith("http") || attempt.startsWith("//") || attempt.contains("playeriframe.sbs") || attempt.contains("iframe")) {
                        decoded = attempt
                        break
                    }
                }
            }
            
            if (decoded.isNotBlank()) {
                // Standarisasi URL: Jika tidak diawali skema protokol HTTP, tambahkan secara manual
                if (!decoded.startsWith("http") && !decoded.startsWith("//")) {
                    decoded = if (decoded.startsWith("/")) "https://playeriframe.sbs$decoded" else "https://$decoded"
                }
                rawSources.add(decoded)
            }
        }

        // 2. TANGKAP PAKAI WEBVIEWRESOLVER SEBAGAI BACKUP AKHIR
        if (rawSources.isEmpty()) {
            try {
                val interceptorRegex = Regex("(?i)playeriframe\\.sbs/iframe")
                val webResponse = app.get(currentUrl, interceptor = WebViewResolver(interceptorRegex))
                val interceptedUrl = webResponse.url
                
                if (interceptedUrl.isNotBlank() && interceptorRegex.containsMatchIn(interceptedUrl)) {
                    rawSources.add(interceptedUrl)
                }
            } catch (e: Exception) {}
        }

        val allSources = rawSources.distinct().map { fixUrl(it) }

        allSources.forEach { url ->
            var finalUrl = url
            
            // 3. BYPASS SEMUA SERVER SECARA PARALEL (P2P, TurboVIP, Cast Terbaca Semua)
            if (finalUrl.contains("playeriframe.sbs/iframe/p2p/")) {
                val id = finalUrl.substringAfter("p2p/").substringBefore("/")
                P2PExtractor().getUrl("https://cloud.hownetwork.xyz/video.php?id=$id", currentUrl)?.forEach { callback.invoke(it) }
            } 
            else if (finalUrl.contains("playeriframe.sbs/iframe/turbovip/")) {
                val id = finalUrl.substringAfter("turbovip/").substringBefore("/")
                EmturbovidExtractor().getUrl("https://turbovidhls.com/t/$id", currentUrl)?.forEach { callback.invoke(it) }
            } 
            else if (finalUrl.contains("playeriframe.sbs/iframe/cast/")) {
                val id = finalUrl.substringAfter("cast/").substringBefore("/")
                F16Extractor().getUrl("https://f16px.com/e/$id", currentUrl)?.forEach { callback.invoke(it) }
            } 
            else {
                val directLoaded = loadExtractor(finalUrl, currentUrl, subtitleCallback, callback)
                if (!directLoaded) {
                    try {
                        val res = app.get(finalUrl, referer = currentUrl)
                        val scriptHtml = res.document.html().replace("\\/", "/")
                        Regex("(?i)https?://[^\"]+\\.(m3u8|mp4)(?:\\?[^\"']*)?").findAll(scriptHtml).forEach { match ->
                            val streamUrl = match.value
                            val isM3u8 = streamUrl.contains("m3u8", ignoreCase = true)
                            callback.invoke(
                                newExtractorLink(
                                    source = "LK21 VIP",
                                    name = "LK21 VIP",
                                    url = streamUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = finalUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    } catch (e: Exception) {}
                }
            }
        }
        return true
    }
}
