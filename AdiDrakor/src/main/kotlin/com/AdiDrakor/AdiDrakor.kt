package com.AdiDrakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.AdiDrakor.AdiDrakorExtractor.invokeKisskh 
import com.AdiDrakor.AdiDrakorExtractor.invokeMoviebox
import com.AdiDrakor.AdiDrakorIdlix.invokeIdlix
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Interceptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class AdiDrakor : TmdbProvider() {
    override var name = "AdiDrakor"
    override val hasMainPage = true
    override var lang = "en"
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true

    // [AUDIT-A1] MainAPI: "Set false if links require referer or for some reason cant be
    // played on a chromecast". Link MovieBox wajib membawa header Cookie hasil signCookie
    // lewat getVideoInterceptor(), dan Kisskh wajib membawa Referer. Chromecast tidak
    // memakai interceptor provider sehingga CDN membalas 403. Kembalikan ke true hanya
    // bila nanti ada sumber yang benar-benar bisa di-cast.
    override val hasChromecastSupport = false

    // [AUDIT-A3] load() dapat mengembalikan TvType.Anime (isAnime), sedangkan supportedTypes
    // sebelumnya hanya Movie + TvSeries. LoadResponse yang tipenya di luar supportedTypes
    // membuat provider tersaring dari filter tipe di UI.
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
    )

    val wpRedisInterceptor by lazy { CloudflareKiller() }

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        const val gdbot = "https://gdtot.pro"
        const val anilistAPI = "https://graphql.anilist.co"
        const val malsyncAPI = "https://api.malsync.moe"
        const val jikanAPI = "https://api.jikan.moe/v4"

        private const val apiKey = "b030404650f279792a8d3287232358e3"

        // Dihitung sekali saat provider dibuat. Dipakai oleh kategori Korea yang
        // memfilter tanggal (first_air_date.lte / primary_release_date.lte / air_date).
        private val today: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        fun getType(t: String?): TvType = when (t) {
            "movie" -> TvType.Movie
            else -> TvType.TvSeries
        }

        fun getStatus(t: String?): ShowStatus = when (t) {
            "Returning Series" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&sort_by=first_air_date.desc&first_air_date.lte=$today&vote_count.gte=1" to "Drama Korea Terbaru",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&sort_by=primary_release_date.desc&primary_release_date.lte=$today&vote_count.gte=1" to "Movie Korea Terbaru",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&sort_by=popularity.desc" to "Popular K-Dramas",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&sort_by=popularity.desc" to "Popular Korean Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&sort_by=vote_average.desc&vote_count.gte=100" to "Top Rated K-Dramas",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&sort_by=vote_average.desc&vote_count.gte=100" to "Top Rated Korean Movies",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&with_genres=10749&air_date.lte=$today&air_date.gte=$today" to "Airing Today K-Dramas",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&with_genres=10749" to "Romance K-Dramas",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&with_genres=28" to "Action Korean Movies",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val adultQuery =
            if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        
        val home = app.get("${request.data}$adultQuery&page=$page")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    // [AUDIT-A4] Sebelumnya SEMUA hasil dibungkus newMovieSearchResponse + TvType.Movie,
    // termasuk serial. MainAPI memakai tipe ini untuk ikon, filter tipe, dan sinkronisasi
    // watch-list, jadi serial ikut terdaftar sebagai film. Payload "Data" tidak diubah,
    // sehingga load() tetap menerima data yang sama persis.
    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        val label = title ?: name ?: originalTitle ?: return null
        val mediaKind = mediaType ?: type
        val payload = Data(id = id, type = mediaKind).toJson()

        return if (mediaKind == "tv") {
            newTvSeriesSearchResponse(label, payload, TvType.TvSeries) {
                this.posterUrl = getImageUrl(posterPath)
                this.score = Score.from10(voteAverage)
            }
        } else {
            newMovieSearchResponse(label, payload, TvType.Movie) {
                this.posterUrl = getImageUrl(posterPath)
                this.score = Score.from10(voteAverage)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.get("$tmdbAPI/search/multi?api_key=$apiKey&language=en-US&query=$query&page=1&include_adult=${settingsForProvider.enableAdult}")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse()
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = try {
            if (url.startsWith("https://www.themoviedb.org/")) {
                val segments = url.removeSuffix("/").split("/")
                val id = segments.lastOrNull()?.toIntOrNull()
                val type = when {
                    url.contains("/movie/") -> "movie"
                    url.contains("/tv/") -> "tv"
                    else -> null
                }
                Data(id = id, type = type)
            } else {
                parseJson<Data>(url)
            }
        } catch (e: Exception) {
            throw ErrorLoadingException("Invalid URL or JSON data: ${e.message}")
        }

        val type = getType(data.type)
        val append = "alternative_titles,credits,external_ids,keywords,videos,recommendations"
        
        val resUrlEn = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&append_to_response=$append&include_video_language=en&language=en-US"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&append_to_response=$append&include_video_language=en&language=en-US"
        }
        
        val res = app.get(resUrlEn).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val plot = res.overview

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
        
        val genres = res.genres?.mapNotNull { it.name }
        val isCartoon = genres?.contains("Animation") ?: false
        val isAnime = isCartoon && (res.originalLanguage == "zh" || res.originalLanguage == "ja")
        val isAsian = !isAnime && (res.originalLanguage == "zh" || res.originalLanguage == "ko")
        val isBollywood = res.productionCountries?.any { it.name == "India" } ?: false

        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
             ActorData(
                Actor(
                    cast.name ?: cast.originalName ?: return@mapNotNull null, 
                    getImageUrl(cast.profilePath)
                ), roleString = cast.character
            )
        } ?: return null

        val recommendations = res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }
        val trailer = res.videos?.results
            ?.filter { it.site == "YouTube" && it.key?.isNotBlank() == true && it.type == "Trailer" }
            ?.sortedByDescending { it.type == "Trailer" }
            ?.map { "https://www.youtube.com/watch?v=${it.key}" }
            ?.firstOrNull()

        val jpTitle = res.alternativeTitles?.results?.find { it.iso31661 == "JP" }?.title
        val idTitle = res.alternativeTitles?.results?.find { it.iso31661 == "ID" }?.title

        return if (type == TvType.TvSeries) {
            val lastSeason = res.lastEpisodeToAir?.seasonNumber
            val episodes = res.seasons?.mapNotNull { season ->
                val seasonUrlEn = "$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey&language=en-US"
                val seasonRes = app.get(seasonUrlEn).parsedSafe<MediaDetailEpisodes>()

                seasonRes?.episodes?.map { eps ->
                    newEpisode(
                        data = LinkData(
                            data.id,
                            res.externalIds?.imdbId,
                            res.externalIds?.tvdbId,
                            data.type,
                            eps.seasonNumber,
                            eps.episodeNumber,
                            title = title,
                            year = season.airDate?.split("-")?.firstOrNull()?.toIntOrNull(),
                            orgTitle = orgTitle,
                            isAnime = isAnime,
                            airedYear = year,
                            lastSeason = lastSeason,
                            epsTitle = eps.name,
                            jpTitle = jpTitle,
                            altTitle = idTitle,
                            date = season.airDate,
                            airedDate = res.releaseDate ?: res.firstAirDate,
                            isAsian = isAsian,
                            isBollywood = isBollywood,
                            isCartoon = isCartoon
                        ).toJson()
                    ) {
                        this.name = eps.name + if (isUpcoming(eps.airDate)) " • [UPCOMING]" else ""
                        this.season = eps.seasonNumber
                        this.episode = eps.episodeNumber
                        this.posterUrl = getImageUrl(eps.stillPath)
                        this.score = Score.from10(eps.voteAverage) 
                        this.description = eps.overview
                    }.apply {
                        this.addDate(eps.airDate)
                    }
                }
            }?.flatten() ?: listOf()

            newTvSeriesLoadResponse(
                title,
                url,
                if (isAnime) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = plot
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(res.voteAverage?.toString())
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.externalIds?.imdbId)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    data.id,
                    res.externalIds?.imdbId,
                    res.externalIds?.tvdbId,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                    jpTitle = jpTitle,
                    altTitle = idTitle,
                    airedDate = res.releaseDate ?: res.firstAirDate,
                    isAsian = isAsian,
                    isBollywood = isBollywood
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.comingSoon = isUpcoming(releaseDate)
                this.year = year
                this.plot = plot
                this.duration = res.runtime
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = Score.from10(res.voteAverage?.toString())
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.externalIds?.imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<LinkData>(data)
        runAllAsync(
            { invokeMoviebox(res.title ?: return@runAllAsync, res.orgTitle, res.altTitle, res.year, res.airedYear, res.season, res.episode, subtitleCallback, callback) },
            { invokeKisskh(res.title ?: return@runAllAsync, res.orgTitle, res.altTitle, res.year, res.season, res.episode, subtitleCallback, callback) },
            { invokeIdlix(res.title ?: return@runAllAsync, res.orgTitle, res.altTitle, res.year, res.season, res.episode, subtitleCallback, callback) }
        )
        return true
    }

    /**
     * Dipindahkan dari MovieBoxProvider.
     *
     * Stream MovieBox memakai signed cookie; tanpa interceptor ini ExoPlayer
     * tidak mengirim header Cookie ke CDN dan semua request balas 403.
     *
     * Return null bila link tidak punya header Cookie, sehingga Kisskh sama
     * sekali tidak terpengaruh.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val cookie = extractorLink.headers["Cookie"]
        if (cookie.isNullOrBlank()) return null
        val userAgent = extractorLink.headers["User-Agent"]

        return Interceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder().header("Cookie", cookie)
            if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
            chain.proceed(builder.build())
        }
    }

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val tvdbId: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val aniId: String? = null,
        val animeId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val orgTitle: String? = null,
        val isAnime: Boolean = false,
        val airedYear: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
        val jpTitle: String? = null,
        val altTitle: String? = null,
        val date: String? = null,
        val airedDate: String? = null,
        val isAsian: Boolean = false,
        val isBollywood: Boolean = false,
        val isCartoon: Boolean = false,
    )

    // Data class lainnya tetap dengan penambahan @param:
    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Results(
        @param:JsonProperty("results") val results: List<Media>? = emptyList(),
    )

    data class Media(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("original_title") val originalTitle: String? = null,
        @param:JsonProperty("media_type") val mediaType: String? = null,
        @param:JsonProperty("poster_path") val posterPath: String? = null,
        @param:JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class Genres(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @param:JsonProperty("results") val results: List<Keywords>? = emptyList(),
        @param:JsonProperty("keywords") val keywords: List<Keywords>? = emptyList(),
    )

    data class Seasons(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("season_number") val seasonNumber: Int? = null,
        @param:JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("original_name") val originalName: String? = null,
        @param:JsonProperty("character") val character: String? = null,
        @param:JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @param:JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("air_date") val airDate: String? = null,
        @param:JsonProperty("still_path") val stillPath: String? = null,
        @param:JsonProperty("vote_average") val voteAverage: Double? = null,
        @param:JsonProperty("episode_number") val episodeNumber: Int? = null,
        @param:JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @param:JsonProperty("episodes") val episodes: List<Episodes>? = emptyList(),
    )

    data class Trailers(
        @param:JsonProperty("key") val key: String? = null,
        @param:JsonProperty("site") val site: String? = null,
        @param:JsonProperty("type") val type: String? = null,
    )

    data class ResultsTrailer(
        @param:JsonProperty("results") val results: List<Trailers>? = null,
    )

    data class AltTitles(
        @param:JsonProperty("iso_3166_1") val iso31661: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("type") val type: String? = null,
    )

    data class ResultsAltTitles(
        @param:JsonProperty("results") val results: List<AltTitles>? = emptyList(),
    )

    data class ExternalIds(
        @param:JsonProperty("imdb_id") val imdbId: String? = null,
        @param:JsonProperty("tvdb_id") val tvdbId: Int? = null,
    )

    data class Credits(
        @param:JsonProperty("cast") val cast: List<Cast>? = emptyList(),
    )

    data class ResultsRecommendations(
        @param:JsonProperty("results") val results: List<Media>? = emptyList(),
    )

    data class LastEpisodeToAir(
        @param:JsonProperty("episode_number") val episodeNumber: Int? = null,
        @param:JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class ProductionCountries(
        @param:JsonProperty("name") val name: String? = null,
    )

    data class MediaDetail(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("imdb_id") val imdbId: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("original_title") val originalTitle: String? = null,
        @param:JsonProperty("original_name") val originalName: String? = null,
        @param:JsonProperty("poster_path") val posterPath: String? = null,
        @param:JsonProperty("backdrop_path") val backdropPath: String? = null,
        @param:JsonProperty("release_date") val releaseDate: String? = null,
        @param:JsonProperty("first_air_date") val firstAirDate: String? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("runtime") val runtime: Int? = null,
        @param:JsonProperty("vote_average") val voteAverage: Any? = null,
        @param:JsonProperty("original_language") val originalLanguage: String? = null,
        @param:JsonProperty("status") val status: String? = null,
        @param:JsonProperty("genres") val genres: List<Genres>? = emptyList(),
        @param:JsonProperty("keywords") val keywords: KeywordResults? = null,
        @param:JsonProperty("last_episode_to_air") val lastEpisodeToAir: LastEpisodeToAir? = null,
        @param:JsonProperty("seasons") val seasons: List<Seasons>? = emptyList(),
        @param:JsonProperty("videos") val videos: ResultsTrailer? = null,
        @param:JsonProperty("external_ids") val externalIds: ExternalIds? = null,
        @param:JsonProperty("credits") val credits: Credits? = null,
        @param:JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @param:JsonProperty("alternative_titles") val alternativeTitles: ResultsAltTitles? = null,
        @param:JsonProperty("production_countries") val productionCountries: List<ProductionCountries>? = emptyList(),
    )
}
