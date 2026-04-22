package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import java.security.MessageDigest

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z1.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // --- DAFTAR MENU ---
    override val mainPage = mainPageOf(
        "$mainUrl/api/homepage" to "Beranda",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&network=netflix" to "Netflix",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&network=prime-video" to "Prime Video",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
        "$mainUrl/api/movies?page=1&limit=36&sort=createdAt" to "Movie",
        "$mainUrl/api/series?page=1&limit=36&sort=createdAt" to "Series",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&genre=horror" to "Horror",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&genre=drama" to "Drama",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&genre=mystery" to "Mystery",
        "$mainUrl/api/browse?page=1&limit=36&sort=latest&genre=thriller" to "Thriller"
    )

    private fun formatTitle(title: String, season: Int?): String {
        return if (season != null && season > 0) "$title (S$season)" else title
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        if (url.contains("/api/homepage")) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val responseText = app.get(url).text
            val homeItems = mutableListOf<SearchResponse>()
            try {
                val parsed = AppUtils.parseJson<IdlixHomepageResponse>(responseText)
                val allSections = mutableListOf<HomepageSection>()
                parsed.above?.let { allSections.addAll(it) }
                parsed.below?.let { allSections.addAll(it) }
                for (section in allSections) {
                    val sectionData = section.data ?: continue
                    if (section.type == "latest_episodes") continue 
                    for (item in sectionData) {
                        val content = item.getActualContent()
                        val rawTitle = content.title ?: continue
                        val slug = content.slug ?: continue
                        val typeRaw = item.contentType ?: content.contentType ?: ""
                        val isSeries = typeRaw.contains("series", true) || typeRaw.contains("episode", true)
                        val displayTitle = formatTitle(rawTitle, item.numberOfSeasons ?: content.numberOfSeasons)
                        val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
                        val posterPath = content.posterPath
                        val posterUrl = if (posterPath.isNullOrEmpty() || posterPath == "null") "" 
                                        else "https://image.tmdb.org/t/p/w342$posterPath"
                        if (isSeries) {
                            homeItems.add(newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                                this.quality = getQualityFromString(content.quality ?: "")
                                this.score = Score.from10(content.voteAverage)
                            })
                        } else {
                            homeItems.add(newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                                this.posterUrl = posterUrl
                                this.quality = getQualityFromString(content.quality ?: "")
                                this.score = Score.from10(content.voteAverage)
                            })
                        }
                    }
                }
            } catch (e: Exception) { Log.e("adixtream", "Error: ${e.message}") }
            return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, hasNext = false)
        } else {
            val apiUrl = url.replace("page=1", "page=$page")
            val responseText = app.get(apiUrl, headers = mapOf("Accept" to "application/json")).text
            val items = AppUtils.parseJson<IdlixPaginatedResponse>(responseText).data ?: emptyList()
            val categoryItems = items.map { item ->
                val isSeries = (item.contentType ?: "").contains("series") || url.contains("series")
                val poster = "https://image.tmdb.org/t/p/w342${item.posterPath}"
                val href = "$mainUrl/${if (isSeries) "series" else "movie"}/${item.slug}"
                if (isSeries) newTvSeriesSearchResponse(formatTitle(item.title ?: "", item.numberOfSeasons), href, TvType.TvSeries) { this.posterUrl = poster }
                else newMovieSearchResponse(item.title ?: "", href, TvType.Movie) { this.posterUrl = poster }
            }
            return newHomePageResponse(request.name, categoryItems, hasNext = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=${java.net.URLEncoder.encode(query, "utf-8")}"
        val response = app.get(url).parsedSafe<IdlixSearchResponse>()
        return (response?.data ?: emptyList()).map { item ->
            val isSeries = (item.contentType ?: "").contains("series")
            val poster = "https://image.tmdb.org/t/p/w342${item.posterPath}"
            val href = "$mainUrl/${if (isSeries) "series" else "movie"}/${item.slug}"
            if (isSeries) newTvSeriesSearchResponse(formatTitle(item.title ?: "", item.numberOfSeasons), href, TvType.TvSeries) { this.posterUrl = poster }
            else newMovieSearchResponse(item.title ?: "", href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        val response = app.get("$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug").parsedSafe<IdlixDetailResponse>()!!
        val title = response.title ?: response.name ?: ""
        val poster = "https://image.tmdb.org/t/p/w500${response.posterPath}"
        
        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            for (s in 1..(response.numberOfSeasons ?: 1)) {
                val sRes = app.get("$mainUrl/api/series/$slug/season/$s").parsedSafe<IdlixSeasonApiResponse>()
                sRes?.season?.episodes?.forEach { ep ->
                    episodes.add(newEpisode("episode|${ep.id}|$url") {
                        this.name = ep.name; this.season = s; this.episode = ep.episodeNumber
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster; this.plot = response.overview }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "movie|${response.id}|$url") { this.posterUrl = poster; this.plot = response.overview }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val parts = data.split("|")
            val type = parts[0].substringAfterLast("/")
            val id = parts[1]
            val headers = mapOf("Referer" to (parts.getOrNull(2) ?: "$mainUrl/"), "User-Agent" to "Mozilla/5.0")

            val challenge = app.post("$mainUrl/api/watch/challenge", json = mapOf("contentType" to type, "contentId" to id), headers = headers).parsedSafe<ChallengeResponse>()!!
            val nonce = mineNonce(challenge.challenge!!, challenge.difficulty ?: 3)!!
            val solve = app.post("$mainUrl/api/watch/solve", json = mapOf("challenge" to challenge.challenge, "signature" to challenge.signature, "nonce" to nonce), headers = headers).parsedSafe<SolveResponse>()!!

            val playerRegex = """((?:majorplay\.net|jeniusplay\.com)/(?:embed|video|player)/[a-zA-Z0-9]+)""".toRegex()
            val embedRes = app.get(if (solve.embedUrl!!.startsWith("/")) "$mainUrl${solve.embedUrl}" else solve.embedUrl!!, headers = headers, interceptor = WebViewResolver(playerRegex))
            
            if (embedRes.url.contains("majorplay.net") || embedRes.url.contains("jeniusplay.com")) {
                loadExtractor(embedRes.url, embedRes.url, subtitleCallback, callback)
                return true
            }
            return false
        } catch (e: Exception) { return false }
    }

    private fun mineNonce(challenge: String, difficulty: Int): Int? {
        val md = MessageDigest.getInstance("SHA-256")
        for (nonce in 0..2000000) {
            val text = challenge + nonce
            val bytes = md.digest(text.toByteArray())
            var isValid = true
            for (i in 0 until difficulty) {
                val nibble = if (i % 2 == 0) (bytes[i/2].toInt() ushr 4) and 0x0F else bytes[i/2].toInt() and 0x0F
                if (nibble != 0) { isValid = false; break }
            }
            if (isValid) return nonce
        }
        return null
    }
}

[span_3](start_span)[span_4](start_span)[span_5](start_span)// DATA CLASSES[span_3](end_span)[span_4](end_span)[span_5](end_span)
data class IdlixPaginatedResponse(@JsonProperty("data") val data: List<ContentData>? = null, @JsonProperty("pagination") val pagination: PaginationData? = null)
data class PaginationData(@JsonProperty("page") val page: Int? = null, @JsonProperty("totalPages") val totalPages: Int? = null)
data class IdlixHomepageResponse(@JsonProperty("above") val above: List<HomepageSection>? = null, @JsonProperty("below") val below: List<HomepageSection>? = null)
data class HomepageSection(@JsonProperty("type") val type: String? = null, @JsonProperty("data") val data: List<HomepageItem>? = null)
data class HomepageItem(@JsonProperty("contentType") val contentType: String? = null, @JsonProperty("content") val content: ContentData? = null, @JsonProperty("id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("originalTitle") val originalTitle: String? = null, @JsonProperty("slug") val slug: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null) {
    fun getActualContent(): ContentData = content ?: ContentData(id=id, title=title ?: originalTitle, slug=slug, posterPath=posterPath, contentType=contentType, numberOfSeasons=numberOfSeasons)
}
data class ContentData(@JsonProperty("id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("originalTitle") val originalTitle: String? = null, @JsonProperty("slug") val slug: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("contentType") val contentType: String? = null, @JsonProperty("quality") val quality: String? = null, @JsonProperty("voteAverage") val voteAverage: String? = null, @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null)
data class IdlixSearchResponse(@JsonProperty("data") val data: List<ContentData>? = null)
data class IdlixDetailResponse(@JsonProperty("id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("backdropPath") val backdropPath: String? = null, @JsonProperty("voteAverage") val voteAverage: String? = null, @JsonProperty("releaseDate") val releaseDate: String? = null, @JsonProperty("firstAirDate") val firstAirDate: String? = null, @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null, @JsonProperty("genres") val genres: List<Genre>? = null, @JsonProperty("cast") val cast: List<Cast>? = null)
data class IdlixSeasonApiResponse(@JsonProperty("season") val season: SeasonDetail? = null)
data class SeasonDetail(@JsonProperty("episodes") val episodes: List<EpisodeDetail>? = null)
data class EpisodeDetail(@JsonProperty("id") val id: String? = null, @JsonProperty("episodeNumber") val episodeNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("hasVideo") val hasVideo: Boolean? = null)
data class Genre(@JsonProperty("name") val name: String? = null)
data class Cast(@JsonProperty("name") val name: String? = null, @JsonProperty("profilePath") val profilePath: String? = null)
data class ChallengeResponse(@JsonProperty("challenge") val challenge: String? = null, @JsonProperty("signature") val signature: String? = null, @JsonProperty("difficulty") val difficulty: Int? = 3)
data class SolveResponse(@JsonProperty("embedUrl") val embedUrl: String? = null)
