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
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.lagradost.api.Log
import android.webkit.CookieManager

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv10.lk21official.cc"
    override var name = "LayarKaca21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    override val usesWebView = true 

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
                val resYear = (it.release_date ?: it.first_air_date)?.take(4)?.toIntOrNull()
                year == null || resYear == null || resYear == year
            } ?: tmdbRes?.results?.firstOrNull()
            
            hdPoster = match?.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        } catch(e: Exception) {}
        
        val posterUrl = hdPoster ?: fallbackPoster
        val quality = getQualityFromString(element.select("span.label").text())
        val isSeries = element.select("span.episode").isNotEmpty() || element.select("span.duration").text().contains("S.")

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "https://gudangvape.com/search.php?s=$query&page=1"
        try {
            val response = app.get(searchUrl).text
            val json = tryParseJson<Lk21SearchResponse>(response)
            return coroutineScope {
                json?.data?.map { item ->
                    async {
                        val cleanTitle = getCleanTitle(item.title)
                        val href = fixUrl(item.slug)
                        val posterUrl = if (item.poster != null) "https://poster.lk21.party/wp-content/uploads/${item.poster}" else null
                        if (item.type?.contains("series", true) == true) {
                            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) { this.posterUrl = posterUrl }
                        } else {
                            newMovieSearchResponse(cleanTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
                        }
                    }
                }?.awaitAll()?.filterNotNull() ?: emptyList()
            }
        } catch (e: Exception) { return emptyList() }
    }

    data class Lk21SearchResponse(val data: List<Lk21SearchItem>?)
    data class Lk21SearchItem(val title: String, val slug: String, val poster: String?, val type: String?, val year: Int?, val quality: String?)
    data class NontonDramaEpisode(val s: Int? = null, val episode_no: Int? = null, val title: String? = null, val slug: String? = null)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = getCleanTitle(document.select("h1.entry-title").text())
        val poster = fixPosterUrl(document.select("meta[property='og:image']").attr("content"))
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = document.select("div.synopsis").text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val currentUrl = data
        try {
            val interceptRegex1 = Regex("(?i)playeriframe\\.sbs/iframe/([a-zA-Z0-9_-]+)/([a-zA-Z0-9_-]+)")
            val res1 = app.get(currentUrl, interceptor = WebViewResolver(interceptRegex1))
            val capturedUrl = res1.url
            val match = interceptRegex1.find(capturedUrl)

            if (match != null) {
                val server = match.groupValues[1].lowercase()
                val id = match.groupValues[2]

                when (server) {
                    "p2p" -> {
                        val apiUrl = "https://cloud.hownetwork.xyz/api2.php?id=$id"
                        val p2pRes = app.post(apiUrl, data = mapOf("r" to "https://playeriframe.sbs/", "d" to "cloud.hownetwork.xyz")).text
                        val videoUrl = tryParseJson<Map<String, String>>(p2pRes)?.get("file")
                        
                        if (videoUrl != null) {
                            callback.invoke(
                                newExtractorLink("P2P VIP", "P2P HD", videoUrl, "https://cloud.hownetwork.xyz/", Qualities.Unknown.value, true)
                            )
                        }
                    }

                    "cast", "f16", "turbovip", "emturbovid", "hydrax" -> {
                        val targetUrl = when (server) {
                            "cast", "f16" -> "https://f16px.com/e/$id"
                            "hydrax" -> "https://abyssplayer.com/$id"
                            else -> "https://turbovidhls.com/t/$id"
                        }
                        val refererUrl = when (server) {
                            "cast", "f16" -> "https://playeriframe.sbs/"
                            "hydrax" -> "https://abysscdn.com/"
                            else -> "https://turbovidhls.com/"
                        }

                        val videoRegex = Regex("(?i)\\.(m3u8|mp4|mkv)|googleusercontent\\.com")
                        val videoRes = app.get(targetUrl, referer = "https://playeriframe.sbs/", interceptor = WebViewResolver(videoRegex))
                        val finalUrl = videoRes.url

                        if (videoRegex.containsMatchIn(finalUrl)) {
                            val cfCookies = CookieManager.getInstance().getCookie(targetUrl) ?: ""
                            callback.invoke(
                                newExtractorLink(
                                    source = "${server.uppercase()} VIP",
                                    name = "${server.uppercase()} HD",
                                    url = finalUrl,
                                    referer = refererUrl, // Parameter Referer diletakkan di sini (posisi ke-4)
                                    quality = Qualities.Unknown.value, // Parameter Quality (posisi ke-5)
                                    isM3u8 = finalUrl.contains(".m3u8", true)
                                ) {
                                    this.headers = mapOf(
                                        "Cookie" to cfCookies,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                if (capturedUrl.contains(Regex("(?i)\\.(m3u8|mp4|mkv)"))) {
                    callback.invoke(
                        newExtractorLink("LK21 Auto", "LK21 Auto", capturedUrl, currentUrl, Qualities.Unknown.value, capturedUrl.contains(".m3u8", true))
                    )
                }
            }
        } catch (e: Exception) { Log.e("LK21", "Error: ${e.message}") }
        return true
    }
}
