package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

// FIX #9: Tag log diganti ke nama yang relevan
private const val TAG = "IdlixProvider"

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
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

    // FIX #8: Helper untuk posterUrl — kembalikan null jika kosong/invalid
    private fun buildPosterUrl(path: String?, size: String = "w342"): String? {
        return if (path.isNullOrEmpty() || path == "null") null
        else "https://image.tmdb.org/t/p/$size$path"
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
                        // FIX #8: pakai null bukan ""
                        val posterUrl = buildPosterUrl(content.posterPath)

                        if (isSeries) {
                            homeItems.add(
                                newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                                    this.posterUrl = posterUrl
                                    this.quality = getQualityFromString(content.quality ?: "")
                                    // FIX #7: set score
                                    this.score = Score.from10(content.voteAverage)
                                }
                            )
                        } else {
                            homeItems.add(
                                newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                                    this.posterUrl = posterUrl
                                    this.quality = getQualityFromString(content.quality ?: "")
                                    // FIX #7: set score
                                    this.score = Score.from10(content.voteAverage)
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing homepage: ${e.message}")
            }
            return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, hasNext = false)
        } else {
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
                    // FIX #8: pakai null bukan ""
                    val posterUrl = buildPosterUrl(item.posterPath)

                    if (isSeries) {
                        categoryItems.add(
                            newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                                this.posterUrl = posterUrl
                                this.quality = getQualityFromString(item.quality ?: "")
                                // FIX #7: set score
                                this.score = Score.from10(item.voteAverage)
                            }
                        )
                    } else {
                        categoryItems.add(
                            newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                                this.posterUrl = posterUrl
                                this.quality = getQualityFromString(item.quality ?: "")
                                // FIX #7: set score
                                this.score = Score.from10(item.voteAverage)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing category page: ${e.message}")
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
                // FIX #8: pakai null bukan ""
                val posterUrl = buildPosterUrl(item.posterPath)

                if (isSeries) {
                    searchItems.add(
                        newTvSeriesSearchResponse(displayTitle, href, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.quality = getQualityFromString(item.quality ?: "")
                            // FIX #7: set score
                            this.score = Score.from10(item.voteAverage)
                        }
                    )
                } else {
                    searchItems.add(
                        newMovieSearchResponse(displayTitle, href, TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.quality = getQualityFromString(item.quality ?: "")
                            // FIX #7: set score
                            this.score = Score.from10(item.voteAverage)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing search results: ${e.message}")
        }
        return searchItems
    }

    // FIX #1 + #2: Return type nullable + seluruh body dibungkus try-catch
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val isSeries = url.contains("/series/")
            val slug = url.split("/").last()

            val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
            val responseText = app.get(apiUrl).text
            val response = AppUtils.parseJson<IdlixDetailResponse>(responseText)

            val title = response.title ?: response.name ?: return null
            // FIX #8: pakai helper buildPosterUrl, null bukan ""
            val poster = buildPosterUrl(response.posterPath, "w500")
            val background = buildPosterUrl(response.backdropPath, "w1280")
            val plot = response.overview
            val year = (response.releaseDate ?: response.firstAirDate)
                ?.split("-")?.firstOrNull()?.toIntOrNull()

            val trailer = response.trailerUrl
            val tags = response.genres?.mapNotNull { it.name }

            val actors = response.cast?.mapNotNull {
                val actorName = it.name ?: return@mapNotNull null
                Actor(actorName, buildPosterUrl(it.profilePath, "w185"))
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
                                    val epPoster = buildPosterUrl(ep.stillPath, "w500")
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
                        Log.e(TAG, "Error loading season $seasonNum: ${e.message}")
                    }
                }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    // FIX #7: set score
                    this.score = Score.from10(response.voteAverage)
                    addSeasonNames(seasonNamesList)
                    if (actors != null) addActors(actors)
                    addTrailer(trailer)
                }
            } else {
                val movieId = response.id ?: slug
                val loadData = "movie|$movieId|$url"

                newMovieLoadResponse(title, url, TvType.Movie, loadData) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    // FIX #7: set score
                    this.score = Score.from10(response.voteAverage)
                    if (actors != null) addActors(actors)
                    addTrailer(trailer)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading detail page: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var stage = "input"
        var mediaCallbacks = 0
        IdlixPlayback.trace("loadLinks.enter", "build=${IdlixPlayback.BUILD_MARKER} casting=$isCasting")
        try {
            var contentType = "movie"
            var contentId = data
            var refererUrl = "$mainUrl/"

            if (data.contains("|")) {
                val parts = data.split("|", limit = 3)
                val rawContentType = parts.getOrNull(0) ?: "movie"
                contentType = rawContentType.substringAfterLast("/")
                contentId = parts.getOrNull(1) ?: data
                refererUrl = parts.getOrNull(2) ?: "$mainUrl/"
            } else if (data.startsWith("http")) {
                refererUrl = data
                val path = data.toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() }
                    ?: return IdlixPlayback.reject(stage, "invalid_page_url")
                if (path.size == 6 && path[0] == "series" && path[2] == "season" && path[4] == "episode") {
                    val season = path[3].toIntOrNull()?.takeIf { it > 0 } ?: return IdlixPlayback.reject(stage, "invalid_season")
                    val episode = path[5].toIntOrNull()?.takeIf { it > 0 } ?: return IdlixPlayback.reject(stage, "invalid_episode")
                    stage = "episode.lookup"
                    val seasonResponse = app.get("$mainUrl/api/series/${path[1]}/season/$season")
                    if (seasonResponse.code != 200) return IdlixPlayback.reject(stage, "http_${seasonResponse.code}")
                    val detail = AppUtils.parseJson<IdlixSeasonApiResponse>(seasonResponse.text).season ?: return IdlixPlayback.reject(stage, "missing_season")
                    if (detail.seasonNumber != season) return IdlixPlayback.reject(stage, "season_mismatch")
                    contentId = detail.episodes?.singleOrNull { it.episodeNumber == episode && it.hasVideo == true }?.id ?: return IdlixPlayback.reject(stage, "episode_unavailable")
                    contentType = "episode"
                } else if (path.size == 2 && path[0] == "movie") {
                    stage = "movie.lookup"
                    val response = app.get("$mainUrl/api/movies/${path[1]}")
                    if (response.code != 200) return IdlixPlayback.reject(stage, "http_${response.code}")
                    contentId = AppUtils.parseJson<IdlixDetailResponse>(response.text).id ?: return IdlixPlayback.reject(stage, "missing_movie_id")
                } else return IdlixPlayback.reject(stage, "no_exact_episode_or_movie")
            }
            if (contentType !in setOf("movie", "episode") || !contentId.matches(Regex("[A-Za-z0-9_-]+"))) return IdlixPlayback.reject(stage, "invalid_type_or_id")
            val page = refererUrl.toHttpUrlOrNull() ?: return IdlixPlayback.reject(stage, "invalid_referer")
            val base = mainUrl.toHttpUrlOrNull() ?: return IdlixPlayback.reject(stage, "invalid_origin")
            if (page.scheme != base.scheme || page.host != base.host || page.port != base.port ||
                page.username.isNotEmpty() || page.password.isNotEmpty()) return IdlixPlayback.reject(stage, "referer_origin_mismatch")
            IdlixPlayback.trace("loadLinks.selected", "type=$contentType id=$contentId")
            val headers = mapOf(
                "Referer" to refererUrl,
                "Origin" to mainUrl,
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to IdlixPlayback.USER_AGENT
            )

            val targetPlayInfoUrl = "$mainUrl/api/watch/play-info/$contentType/$contentId"

            stage = "play_info"
            val playInfoHttp = app.get(targetPlayInfoUrl, headers = headers, allowRedirects = false, timeout = 15)
            IdlixPlayback.trace(stage, "http=${playInfoHttp.code}")
            if (playInfoHttp.code != 200) {
                return IdlixPlayback.reject(stage, "http_${playInfoHttp.code}")
            }
            val playInfoRes = AppUtils.parseJson<PlayInfoResponse>(playInfoHttp.text)
            // play-info sets the device/session cookie used to bind the gate and claim.
            // Carry the server-issued values locally, even if the app has no CookieJar.
            val sessionCookies = playInfoHttp.cookies
            val gateToken = playInfoRes.gateToken?.takeIf { it.isNotBlank() } ?: return IdlixPlayback.reject(stage, "missing_gate")
            val finalWaitMs = IdlixPlayback.waitMillis(playInfoRes.serverNow, playInfoRes.unlockAt,
                playInfoRes.preroll?.countdownSec) ?: return IdlixPlayback.reject(stage, "wait_outside_budget")
            stage = "gate.wait"
            IdlixPlayback.trace(stage, "waitMs=$finalWaitMs")
            delay(finalWaitMs)

            // 4. Klaim token streaming
            val jsonMediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
            val requestBodyData = mapOf("gateToken" to gateToken).toJson().toRequestBody(jsonMediaType)

            stage = "claim"
            val claimHttp = app.post(
                url = "$mainUrl/api/watch/session/claim",
                headers = headers,
                cookies = sessionCookies,
                requestBody = requestBodyData,
                allowRedirects = false,
                timeout = 15
            )
            IdlixPlayback.trace(stage, "http=${claimHttp.code}")
            if (claimHttp.code != 200) return IdlixPlayback.reject(stage, "http_${claimHttp.code}")
            val claimParsed = AppUtils.parseJson<SessionClaimResponse>(claimHttp.text)
            if (claimParsed.videoId.isNullOrBlank()) return IdlixPlayback.reject(stage, "missing_video_id")
            IdlixPlayback.trace("claim.identity", "videoId=${claimParsed.videoId} durationSec=${claimParsed.durationSec}")
            stage = "redeem_or_manifest"
            return Majorplay().resolveClaim(claimParsed, refererUrl, subtitleCallback, { link ->
                callback(link)
                mediaCallbacks++
                IdlixPlayback.trace("callback.media", "count=$mediaCallbacks source=${link.source} type=${link.type}")
            },
                sessionCookies + claimHttp.cookies, providerName = name)
        } catch (e: CancellationException) {
            IdlixPlayback.trace("loadLinks.cancelled", "stage=$stage callbacks=$mediaCallbacks")
            throw e
        } catch (e: Exception) {
            // Exceptions may contain signed URLs: log the class, never session values.
            IdlixPlayback.trace("loadLinks.exception", "stage=$stage exception=${e.javaClass.simpleName}")
            return false
        } finally {
            IdlixPlayback.trace("loadLinks.complete", "stage=$stage callbacks=$mediaCallbacks")
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        IdlixPlayback.trace("player.route", "source=${extractorLink.source} provider=$name type=${extractorLink.type}")
        return IdlixPlayback.interceptor(extractorLink.url)
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
    @JsonProperty("gateToken") val gateToken: String? = null,
    @JsonProperty("serverNow") val serverNow: Long? = null,
    @JsonProperty("unlockAt") val unlockAt: Long? = null,
    @JsonProperty("preroll") val preroll: PrerollData? = null
)

data class PrerollData(
    @JsonProperty("countdownSec") val countdownSec: Long? = null
)

data class SessionClaimResponse(
    @JsonProperty("claim") val claim: String? = null,
    @JsonProperty("redeemUrl") val redeemUrl: String? = null,
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("durationSec") val durationSec: Long? = null
)
