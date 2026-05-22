package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z1.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama
    )

    private val UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    
    // PENTING: Header ini mencegah server mengembalikan HTML (Error '<')
    private val defaultHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to UA
    )

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
        val homeItems = mutableListOf<SearchResponse>()
        var hasNextPage = false

        try {
            if (url.contains("/api/homepage")) {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                
                val responseText = app.get(url, headers = defaultHeaders).text
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
                        val isSeries = (item.contentType ?: content.contentType ?: "").contains("series", true)
                        val displayTitle = formatTitle(rawTitle, item.numberOfSeasons ?: content.numberOfSeasons)
                        val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
                        val posterUrl = if (content.posterPath.isNullOrEmpty()) "" else "https://image.tmdb.org/t/p/w342${content.posterPath}"

                        if (isSeries) {
                            homeItems.add(newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                            })
                        } else {
                            homeItems.add(newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                                this.posterUrl = posterUrl
                            })
                        }
                    }
                }
            } else {
                val apiUrl = url.replace("page=1", "page=$page")
                val responseText = app.get(apiUrl, headers = defaultHeaders).text
                val parsed = AppUtils.parseJson<IdlixPaginatedResponse>(responseText)
                
                val currentPage = parsed.pagination?.page ?: page
                val totalPages = parsed.pagination?.totalPages ?: 1
                hasNextPage = currentPage < totalPages

                parsed.data?.forEach { item ->
                    val rawTitle = item.title ?: item.originalTitle ?: return@forEach
                    val slug = item.slug ?: return@forEach
                    val isSeries = (item.contentType ?: "").contains("series", true) || url.contains("series")
                    val displayTitle = formatTitle(rawTitle, item.numberOfSeasons)
                    val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
                    val posterUrl = if (item.posterPath.isNullOrEmpty()) "" else "https://image.tmdb.org/t/p/w342${item.posterPath}"

                    if (isSeries) {
                        homeItems.add(newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                        })
                    } else {
                        homeItems.add(newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("adixtream", "Error getMainPage: ${e.message}")
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, hasNext = hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "utf-8")
        val responseText = app.get("$mainUrl/api/search?q=$encodedQuery", headers = defaultHeaders).text
        val searchItems = mutableListOf<SearchResponse>()
        
        try {
            val parsed = AppUtils.parseJson<IdlixSearchResponse>(responseText)
            val items = parsed.data ?: parsed.results ?: emptyList()
            
            for (item in items) {
                val rawTitle = item.title ?: item.originalTitle ?: continue
                val slug = item.slug ?: continue
                val isSeries = (item.contentType ?: "").contains("series", true)
                val displayTitle = formatTitle(rawTitle, item.numberOfSeasons)
                val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
                val posterUrl = if (item.posterPath.isNullOrEmpty()) "" else "https://image.tmdb.org/t/p/w342${item.posterPath}"

                if (isSeries) {
                    searchItems.add(newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    })
                } else {
                    searchItems.add(newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        } catch (e: Exception) {}
        
        return searchItems
    }

    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
        
        // PENTING: Header untuk menghindari Error '<' (HTML Parsing)
        val responseText = app.get(apiUrl, headers = defaultHeaders).text
        val response = AppUtils.tryParseJson<IdlixDetailResponse>(responseText) ?: throw Exception("Gagal memuat detail")
        
        val title = response.title ?: response.name ?: ""
        val poster = if (response.posterPath.isNullOrEmpty()) "" else "https://image.tmdb.org/t/p/w500${response.posterPath}"
        val background = if (response.backdropPath.isNullOrEmpty()) "" else "https://image.tmdb.org/t/p/w1280${response.backdropPath}"
        val year = (response.releaseDate ?: response.firstAirDate)?.split("-")?.firstOrNull()?.toIntOrNull()
        val tags = response.genres?.mapNotNull { it.name }
        val actors = response.cast?.mapNotNull { 
            Actor(it.name ?: return@mapNotNull null, if (it.profilePath.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/w185${it.profilePath}") 
        }

        if (isSeries) {
            val episodes = arrayListOf<Episode>()
            val seasonNamesList = mutableListOf<SeasonData>()
            
            coroutineScope {
                (1..(response.numberOfSeasons ?: 1)).map { seasonNum ->
                    async {
                        try {
                            val seasonApiUrl = "$mainUrl/api/series/$slug/season/$seasonNum"
                            val epText = app.get(seasonApiUrl, headers = defaultHeaders).text
                            val parsedSeason = AppUtils.parseJson<IdlixSeasonApiResponse>(epText)
                            
                            val epList = parsedSeason.season?.episodes
                            if (!epList.isNullOrEmpty()) {
                                synchronized(seasonNamesList) {
                                    seasonNamesList.add(SeasonData(seasonNum, "Season $seasonNum"))
                                }
                                epList.forEach { ep ->
                                    if (ep.hasVideo == true) {
                                        val epId = ep.id ?: return@forEach
                                        val epPoster = if (ep.stillPath.isNullOrEmpty()) null else "https://image.tmdb.org/t/p/w500${ep.stillPath}"
                                        
                                        // PENTING: data untuk loadLinks
                                        val loadData = "episode|$epId|$url"
                                        val episodeObj = newEpisode(loadData) {
                                            this.name = ep.name; this.season = seasonNum; this.episode = ep.episodeNumber
                                            this.posterUrl = epPoster; this.description = ep.overview
                                        }
                                        synchronized(episodes) { episodes.add(episodeObj) }
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }.awaitAll()
            }

            episodes.sortBy { it.episode }
            episodes.sortBy { it.season }
            seasonNamesList.sortBy { it.season }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.backgroundPosterUrl = background; this.year = year
                this.plot = response.overview; this.tags = tags; addSeasonNames(seasonNamesList) 
                if (actors != null) addActors(actors); addTrailer(response.trailerUrl)
            }
        } else {
            val movieId = response.id ?: slug
            val loadData = "movie|$movieId|$url"
            
            return newMovieLoadResponse(title, url, TvType.Movie, loadData) {
                this.posterUrl = poster; this.backgroundPosterUrl = background; this.year = year
                this.plot = response.overview; this.tags = tags
                if (actors != null) addActors(actors); addTrailer(response.trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val parts = data.split("|")
            val contentType = parts.getOrNull(0) ?: "movie"
            val contentId = parts.getOrNull(1) ?: return false
            val refererUrl = parts.getOrNull(2) ?: "$mainUrl/"

            val headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "Referer" to refererUrl, 
                "Origin" to mainUrl, 
                "User-Agent" to UA
            )

            // LANGKAH 0: Buka Halaman Film (Simpan Sesi)
            app.get(refererUrl, headers = mapOf("User-Agent" to UA))

            // LANGKAH 1: Mengirim Tracking View (Penting!)
            app.post(
                url = "$mainUrl/api/views/track",
                headers = headers,
                json = mapOf("contentType" to contentType, "contentId" to contentId)
            )

            // LANGKAH 2: Mengambil Play Info (Gate Token)
            val playInfoRes = app.get(
                url = "$mainUrl/api/watch/play-info/$contentType/$contentId",
                headers = mapOf("Accept" to "application/json", "Referer" to refererUrl, "User-Agent" to UA)
            ).text
            
            val playInfo = AppUtils.tryParseJson<PlayInfoResponse>(playInfoRes) ?: return false
            val gateToken = playInfo.gateToken
            
            val finalClaim = if (!gateToken.isNullOrEmpty()) {
                // Kalkulasi Jeda persis seperti skrip Termux
                val serverNow = playInfo.serverNow ?: 0L
                val unlockAt = playInfo.unlockAt ?: 0L
                val waitSec = ((unlockAt - serverNow) / 1000L) + 2L
                
                if (waitSec > 0) {
                    delay(waitSec * 1000L)
                }

                // LANGKAH 3: Menukar Gate Token (Session Claim)
                val claimRes = app.post(
                    url = "$mainUrl/api/watch/session/claim",
                    headers = headers,
                    json = mapOf("gateToken" to gateToken)
                ).text
                
                AppUtils.tryParseJson<SessionClaimResponse>(claimRes)?.claim
            } else {
                playInfo.claim
            }

            if (finalClaim.isNullOrEmpty()) return false
            
            // LANGKAH 4 & 5: Kirim claim ke Majorplay
            return loadExtractor("https://e2e.majorplay.net/play?claim=$finalClaim", refererUrl, subtitleCallback, callback)
            
        } catch (e: Exception) {
            Log.e("adixtream", "Error loadLinks: ${e.message}")
            return false
        }
    }
}

// ============================================================================
// DATA CLASSES (100% Bebas Error Parsing HTML)
// ============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionClaimResponse(@JsonProperty("claim") val claim: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayInfoResponse(
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("gateToken") val gateToken: String? = null,
    @JsonProperty("claim") val claim: String? = null,
    @JsonProperty("serverNow") val serverNow: Long? = null,
    @JsonProperty("unlockAt") val unlockAt: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixPaginatedResponse(@JsonProperty("data") val data: List<ContentData>? = null, @JsonProperty("pagination") val pagination: PaginationData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaginationData(@JsonProperty("page") val page: Int? = null, @JsonProperty("totalPages") val totalPages: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixHomepageResponse(@JsonProperty("above") val above: List<HomepageSection>? = null, @JsonProperty("below") val below: List<HomepageSection>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HomepageSection(@JsonProperty("type") val type: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("data") val data: List<HomepageItem>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HomepageItem(
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("content") val content: ContentData? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("originalTitle") val originalTitle: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null
) {
    fun getActualContent(): ContentData = content ?: ContentData(id, title ?: originalTitle, slug, posterPath, contentType, numberOfSeasons = numberOfSeasons)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ContentData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("originalTitle") val originalTitle: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixSearchResponse(@JsonProperty("data") val data: List<ContentData>? = null, @JsonProperty("results") val results: List<ContentData>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixDetailResponse(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("backdropPath") val backdropPath: String? = null,
    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("trailerUrl") val trailerUrl: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null,
    @JsonProperty("genres") val genres: List<Genre>? = null,
    @JsonProperty("cast") val cast: List<Cast>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixSeasonApiResponse(@JsonProperty("season") val season: SeasonDetail? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonDetail(@JsonProperty("seasonNumber") val seasonNumber: Int? = null, @JsonProperty("episodes") val episodes: List<EpisodeDetail>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeDetail(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("stillPath") val stillPath: String? = null,
    @JsonProperty("hasVideo") val hasVideo: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(@JsonProperty("name") val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Cast(@JsonProperty("name") val name: String? = null, @JsonProperty("profilePath") val profilePath: String? = null)
