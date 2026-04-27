package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
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
                            homeItems.add(
                                newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                                    this.posterUrl = posterUrl
                                    this.quality = getQualityFromString(content.quality ?: "")
                                    this.score = Score.from10(content.voteAverage)
                                }
                            )
                        } else {
                            homeItems.add(
                                newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                                    this.posterUrl = posterUrl
                                    this.quality = getQualityFromString(content.quality ?: "")
                                    this.score = Score.from10(content.voteAverage)
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("adixtream", "Error: ${e.message}")
            }
            return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, hasNext = false)
        } 
        else {
            val apiUrl = url.replace("page=1", "page=$page")
            val responseText = app.get(apiUrl, headers = mapOf("Accept" to "application/json")).text
            val categoryItems = mutableListOf<SearchResponse>()
            var hasNextPage = false

            try {
                val parsed = AppUtils.parseJson<IdlixPaginatedResponse>(responseText)
                val items = parsed.data ?: emptyList()
                
                val currentPage = parsed.pagination?.page ?: page
                val totalPages = parsed.pagination?.totalPages ?: 1
                hasNextPage = currentPage < totalPages

                for (item in items) {
                    val rawTitle = item.title ?: item.originalTitle ?: continue
                    val slug = item.slug ?: continue
                    
                    val typeRaw = item.contentType ?: ""
                    val isSeries = typeRaw.contains("series", true) || url.contains("series")
                    
                    val displayTitle = formatTitle(rawTitle, item.numberOfSeasons)
                    val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
                    val posterPath = item.posterPath
                    val posterUrl = if (posterPath.isNullOrEmpty() || posterPath == "null") "" 
                                    else "https://image.tmdb.org/t/p/w342$posterPath"

                    if (isSeries) {
                        categoryItems.add(
                            newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                                this.quality = getQualityFromString(item.quality ?: "")
                                this.score = Score.from10(item.voteAverage)
                            }
                        )
                    } else {
                        categoryItems.add(
                            newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                                this.posterUrl = posterUrl
                                this.quality = getQualityFromString(item.quality ?: "")
                                this.score = Score.from10(item.voteAverage)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("adixtream", "Error: ${e.message}")
            }
            return newHomePageResponse(request.name, categoryItems.distinctBy { it.url }, hasNext = hasNextPage)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "utf-8")
        val url = "$mainUrl/api/search?q=$encodedQuery"
        
        val responseText = app.get(url).text
        val searchItems = mutableListOf<SearchResponse>()
        
        try {
            val parsed = AppUtils.parseJson<IdlixSearchResponse>(responseText)
            val items = parsed.data ?: parsed.results ?: emptyList()
            
            for (item in items) {
                val rawTitle = item.title ?: item.originalTitle ?: continue
                val slug = item.slug ?: continue
                
                val typeRaw = item.contentType ?: ""
                val isSeries = typeRaw.contains("series", true)
            
                val displayTitle = formatTitle(rawTitle, item.numberOfSeasons)
                val href = "$mainUrl/${if (isSeries) "series" else "movie"}/$slug"
                val posterPath = item.posterPath
                val posterUrl = if (posterPath.isNullOrEmpty() || posterPath == "null") "" 
                                else "https://image.tmdb.org/t/p/w342$posterPath"

                if (isSeries) {
                    searchItems.add(
                        newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.quality = getQualityFromString(item.quality ?: "")
                            this.score = Score.from10(item.voteAverage)
                        }
                    )
                } else {
                    searchItems.add(
                        newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.quality = getQualityFromString(item.quality ?: "")
                            this.score = Score.from10(item.voteAverage)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("adixtream", "Error: ${e.message}")
        }
        return searchItems
    }

    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        
        val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
        val responseText = app.get(apiUrl).text
        val response = AppUtils.parseJson<IdlixDetailResponse>(responseText)
        
        val title = response.title ?: response.name ?: ""
        val poster = if (response.posterPath.isNullOrEmpty() || response.posterPath == "null") "" else "https://image.tmdb.org/t/p/w500${response.posterPath}"
        val background = if (response.backdropPath.isNullOrEmpty() || response.backdropPath == "null") "" else "https://image.tmdb.org/t/p/w1280${response.backdropPath}"
        val plot = response.overview
        val year = (response.releaseDate ?: response.firstAirDate)?.split("-")?.firstOrNull()?.toIntOrNull()
        
        val ratingStr = response.voteAverage?.toFloatOrNull()?.times(10)?.toInt()?.toString()
        val trailer = response.trailerUrl
        val tags = response.genres?.mapNotNull { it.name }
        
        val actors = response.cast?.mapNotNull {
            val actorName = it.name ?: return@mapNotNull null
            val pPath = it.profilePath
            val profile = if (pPath.isNullOrEmpty() || pPath == "null") null else "https://image.tmdb.org/t/p/w185$pPath"
            Actor(actorName, profile)
        }

        if (isSeries) {
            val episodes = arrayListOf<Episode>()
            val seasonNamesList = mutableListOf<SeasonData>()
            val totalSeasons = response.numberOfSeasons ?: 1 
            
            for (seasonNum in 1..totalSeasons) {
                val seasonApiUrl = "$mainUrl/api/series/$slug/season/$seasonNum"
                try {
                    val seasonResText = app.get(seasonApiUrl).text
                    val parsedSeason = AppUtils.parseJson<IdlixSeasonApiResponse>(seasonResText)
                    val epList = parsedSeason.season?.episodes
                    
                    if (!epList.isNullOrEmpty()) {
                        seasonNamesList.add(SeasonData(seasonNum, "Season $seasonNum"))
                        epList.forEach { ep ->
                            if (ep.hasVideo == true) {
                                val epId = ep.id ?: return@forEach
                                val still = ep.stillPath
                                val epPoster = if (still.isNullOrEmpty() || still == "null") null else "https://image.tmdb.org/t/p/w500$still"
                                val loadData = "episode|$epId|$url"
                            
                                episodes.add(newEpisode(loadData) {
                                    this.name = ep.name
                                    this.season = seasonNum
                                    this.episode = ep.episodeNumber
                                    this.posterUrl = epPoster
                                    this.description = ep.overview
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("adixtream", "Error: ${e.message}")
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingStr)
                addSeasonNames(seasonNamesList) 
                if (actors != null) addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val movieId = response.id ?: slug
            val loadData = "movie|$movieId|$url"
            
            return newMovieLoadResponse(title, url, TvType.Movie, loadData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(ratingStr)
                if (actors != null) addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var contentType = "movie"
            var contentId = data
            var refererUrl = "$mainUrl/"

            // Sistem Fallback: Mengecek apakah 'data' berupa format "tipe|id|url" atau hanya URL mentah karena Cache
            if (data.contains("|")) {
                val parts = data.split("|")
                val rawContentType = parts.getOrNull(0) ?: "movie"
                contentType = rawContentType.substringAfterLast("/")
                contentId = parts.getOrNull(1) ?: data 
                refererUrl = parts.getOrNull(2) ?: "$mainUrl/"
            } else if (data.startsWith("http")) {
                // Jika aplikasi mengirimkan URL mentah (sering terjadi jika movie dibuka dari Cache)
                refererUrl = data
                val isSeries = data.contains("/series/")
                val slug = data.split("/").last()
                val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
                
                val responseText = app.get(apiUrl).text
                val detail = AppUtils.parseJson<IdlixDetailResponse>(responseText)
                
                contentId = detail.id ?: slug
                contentType = if (isSeries) "episode" else "movie"
            }

            val headers = mapOf(
                "Referer" to refererUrl, 
                "Origin" to mainUrl, 
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            )

            // Meminta token claim dari endpoint play-info terbaru
            val playInfoRes = app.get(
                url = "$mainUrl/api/watch/play-info/$contentType/$contentId",
                headers = headers
            ).parsedSafe<PlayInfoResponse>() ?: return false

            val claim = playInfoRes.claim ?: return false
            
            // Melempar URL palsu berisi token claim ke file Majorplay.kt
            val fakeUrl = "https://e2e.majorplay.net/play?claim=$claim"
            loadExtractor(fakeUrl, refererUrl, subtitleCallback, callback)
            
            return true
        } catch (e: Exception) {
            Log.e("adixtream", "Error di loadLinks: ${e.message}")
            return false
        }
    }
}

// ============================================================================
// DATA CLASSES 
// ============================================================================

data class IdlixPaginatedResponse(
    @JsonProperty("data") val data: List<ContentData>? = null,
    @JsonProperty("pagination") val pagination: PaginationData? = null
)

data class PaginationData(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("totalPages") val totalPages: Int? = null
)

data class IdlixHomepageResponse(
    @JsonProperty("above") val above: List<HomepageSection>? = null,
    @JsonProperty("below") val below: List<HomepageSection>? = null
)

data class HomepageSection(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("data") val data: List<HomepageItem>? = null
)

data class HomepageItem(
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("content") val content: ContentData? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("originalTitle") val originalTitle: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("voteAverage") val voteAverage: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null
) {
    fun getActualContent(): ContentData {
        return content ?: ContentData(
            id = id,
            title = title ?: originalTitle,
            slug = slug,
            posterPath = posterPath,
            contentType = contentType,
            quality = quality,
            voteAverage = voteAverage,
            numberOfSeasons = numberOfSeasons
        )
    }
}

data class ContentData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("originalTitle") val originalTitle: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("voteAverage") val voteAverage: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null
)

data class IdlixSearchResponse(
    @JsonProperty("data") val data: List<ContentData>? = null,
    @JsonProperty("results") val results: List<ContentData>? = null
)

data class IdlixDetailResponse(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("backdropPath") val backdropPath: String? = null,
    @JsonProperty("voteAverage") val voteAverage: String? = null,
    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("trailerUrl") val trailerUrl: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null,
    @JsonProperty("genres") val genres: List<Genre>? = null,
    @JsonProperty("cast") val cast: List<Cast>? = null
)

data class IdlixSeasonApiResponse(
    @JsonProperty("season") val season: SeasonDetail? = null
)

data class SeasonDetail(
    @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeDetail>? = null
)

data class EpisodeDetail(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("stillPath") val stillPath: String? = null,
    @JsonProperty("hasVideo") val hasVideo: Boolean? = null
)

data class Genre(@JsonProperty("name") val name: String? = null)

data class Cast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profilePath") val profilePath: String? = null
)

data class PlayInfoResponse(
    @JsonProperty("claim") val claim: String? = null,
    @JsonProperty("redeemUrl") val redeemUrl: String? = null,
    @JsonProperty("videoId") val videoId: String? = null
)
