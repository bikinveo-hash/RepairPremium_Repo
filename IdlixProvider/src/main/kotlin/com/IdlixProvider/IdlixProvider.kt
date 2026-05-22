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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/homepage" to "Beranda",
        "$mainUrl/api/movies?page=1&limit=36&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?page=1&limit=36&sort=createdAt" to "Series Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val responseText = app.get(request.data.replace("page=1", "page=$page"), headers = commonHeaders).text
        val items = mutableListOf<SearchResponse>()
        try {
            if (request.data.contains("homepage")) {
                val parsed = AppUtils.parseJson<IdlixHomepageResponse>(responseText)
                (parsed.above ?: emptyList()).plus(parsed.below ?: emptyList()).forEach { section ->
                    section.data?.forEach { item ->
                        val content = item.getActualContent()
                        val isSeries = (item.contentType ?: content.contentType ?: "").contains("series", true)
                        val href = "$mainUrl/${if (isSeries) "series" else "movie"}/${content.slug}"
                        val poster = "https://image.tmdb.org/t/p/w342${content.posterPath}"
                        if (isSeries) items.add(newTvSeriesSearchResponse(content.title ?: "", href, TvType.TvSeries) { this.posterUrl = poster })
                        else items.add(newMovieSearchResponse(content.title ?: "", href, TvType.Movie) { this.posterUrl = poster })
                    }
                }
            } else {
                val parsed = AppUtils.parseJson<IdlixPaginatedResponse>(responseText)
                parsed.data?.forEach { content ->
                    val isSeries = request.data.contains("series") || (content.contentType ?: "").contains("series", true)
                    val href = "$mainUrl/${if (isSeries) "series" else "movie"}/${content.slug}"
                    val poster = "https://image.tmdb.org/t/p/w342${content.posterPath}"
                    if (isSeries) items.add(newTvSeriesSearchResponse(content.title ?: "", href, TvType.TvSeries) { this.posterUrl = poster })
                    else items.add(newMovieSearchResponse(content.title ?: "", href, TvType.Movie) { this.posterUrl = poster })
                }
            }
        } catch (e: Exception) {}
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search?q=${java.net.URLEncoder.encode(query, "utf-8")}"
        val response = app.get(url, headers = commonHeaders).parsedSafe<IdlixSearchResponse>()
        return (response?.data ?: response?.results ?: emptyList()).map { content ->
            val isSeries = (content.contentType ?: "").contains("series", true)
            val href = "$mainUrl/${if (isSeries) "series" else "movie"}/${content.slug}"
            if (isSeries) newTvSeriesSearchResponse(content.title ?: "", href, TvType.TvSeries) { this.posterUrl = "https://image.tmdb.org/t/p/w342${content.posterPath}" }
            else newMovieSearchResponse(content.title ?: "", href, TvType.Movie) { this.posterUrl = "https://image.tmdb.org/t/p/w342${content.posterPath}" }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val slug = url.split("/").last()
        
        // LANGKAH PENTING: Kunjungi URL asli untuk inisialisasi Cookie/Session
        app.get(url, headers = commonHeaders)
        
        val apiUrl = "$mainUrl/api/${if (isSeries) "series" else "movies"}/$slug"
        val response = app.get(apiUrl, headers = commonHeaders).parsedSafe<IdlixDetailResponse>() 
            ?: throw Exception("Gagal memuat detail API")

        val title = response.title ?: response.name ?: ""
        val poster = "https://image.tmdb.org/t/p/w500${response.posterPath}"
        val year = (response.releaseDate ?: response.firstAirDate)?.split("-")?.firstOrNull()?.toIntOrNull()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            coroutineScope {
                (1..(response.numberOfSeasons ?: 1)).map { seasonNum ->
                    async {
                        val sRes = app.get("$mainUrl/api/series/$slug/season/$seasonNum", headers = commonHeaders).parsedSafe<IdlixSeasonApiResponse>()
                        sRes?.season?.episodes?.forEach { ep ->
                            if (ep.hasVideo == true) {
                                episodes.add(newEpisode("episode|${ep.id}|$url") {
                                    this.name = ep.name; this.season = seasonNum; this.episode = ep.episodeNumber
                                    this.posterUrl = "https://image.tmdb.org/t/p/w500${ep.stillPath}"
                                })
                            }
                        }
                    }
                }.awaitAll()
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }.sortedBy { it.season }) {
                this.posterUrl = poster; this.year = year; this.plot = response.overview
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "movie|${response.id ?: slug}|$url") {
                this.posterUrl = poster; this.year = year; this.plot = response.overview
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val parts = data.split("|")
            val contentType = parts.getOrNull(0) ?: "movie"
            val contentId = parts.getOrNull(1) ?: return false
            val refererUrl = parts.getOrNull(2) ?: "$mainUrl/"

            val headers = commonHeaders + mapOf("Referer" to refererUrl, "Origin" to mainUrl)

            // LANGKAH 1: Bingo Tracking (Sesuai simulasi Termux)
            app.post("$mainUrl/api/views/track", headers = headers, json = mapOf("contentType" to contentType.replace("episode", "movie"), "contentId" to contentId))

            // LANGKAH 2: Ambil Gate Token
            val playInfo = app.get("$mainUrl/api/watch/play-info/$contentType/$contentId", headers = headers).parsedSafe<PlayInfoResponse>() ?: return false
            val token = playInfo.gateToken ?: playInfo.claim ?: return false

            // LANGKAH 3: Tunggu Timer & Tukar Token
            val finalClaim = if (playInfo.kind == "gate") {
                val waitTime = (playInfo.unlockAt ?: 0L) - (playInfo.serverNow ?: 0L)
                if (waitTime > 0) delay(waitTime + 2000L) // Jeda 2 detik ekstra agar aman

                val sessionRes = app.post("$mainUrl/api/watch/session/claim", headers = headers, json = mapOf("gateToken" to token)).parsedSafe<SessionClaimResponse>()
                sessionRes?.claim ?: return false
            } else { token }

            return loadExtractor("https://e2e.majorplay.net/play?claim=$finalClaim", refererUrl, subtitleCallback, callback)
        } catch (e: Exception) { return false }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionClaimResponse(@JsonProperty("claim") val claim: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayInfoResponse(@JsonProperty("kind") val kind: String? = null, @JsonProperty("gateToken") val gateToken: String? = null, @JsonProperty("claim") val claim: String? = null, @JsonProperty("serverNow") val serverNow: Long? = null, @JsonProperty("unlockAt") val unlockAt: Long? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixPaginatedResponse(@JsonProperty("data") val data: List<ContentData>? = null, @JsonProperty("pagination") val pagination: PaginationData? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PaginationData(@JsonProperty("totalPages") val totalPages: Int? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixHomepageResponse(@JsonProperty("above") val above: List<HomepageSection>? = null, @JsonProperty("below") val below: List<HomepageSection>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class HomepageSection(@JsonProperty("data") val data: List<HomepageItem>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class HomepageItem(@JsonProperty("contentType") val contentType: String? = null, @JsonProperty("content") val content: ContentData? = null, @JsonProperty("id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("originalTitle") val originalTitle: String? = null, @JsonProperty("slug") val slug: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null) {
    fun getActualContent(): ContentData = content ?: ContentData(id, title ?: originalTitle, slug, posterPath, contentType)
}
@JsonIgnoreProperties(ignoreUnknown = true)
data class ContentData(@JsonProperty("id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("originalTitle") val originalTitle: String? = null, @JsonProperty("slug") val slug: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("contentType") val contentType: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixSearchResponse(@JsonProperty("data") val data: List<ContentData>? = null, @JsonProperty("results") val results: List<ContentData>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixDetailResponse(@JsonProperty("id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("backdropPath") val backdropPath: String? = null, @JsonProperty("firstAirDate") val firstAirDate: String? = null, @JsonProperty("releaseDate") val releaseDate: String? = null, @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null, @JsonProperty("genres") val genres: List<Genre>? = null, @JsonProperty("cast") val cast: List<Cast>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class IdlixSeasonApiResponse(@JsonProperty("season") val season: SeasonDetail? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonDetail(@JsonProperty("episodes") val episodes: List<EpisodeDetail>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeDetail(@JsonProperty("id") val id: String? = null, @JsonProperty("episodeNumber") val episodeNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("stillPath") val stillPath: String? = null, @JsonProperty("hasVideo") val hasVideo: Boolean? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(@JsonProperty("name") val name: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Cast(@JsonProperty("name") val name: String? = null, @JsonProperty("profilePath") val profilePath: String? = null)
