package com.michat88

import com.fasterxml.jackson.annotation.JsonProperty
import com.michat88.AdiFilmSemiExtractor.invokeMoviebox
import com.michat88.AdiFilmSemiIdlix.invokeIdlix
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink

open class AdiFilmSemi : TmdbProvider() {
    override var name = "AdiFilmSemi"
    override val hasMainPage = true
    override var lang = "id"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    /** AUTHOR : Hexated & AdiFilmSemi (Modified) */
    companion object {
        /** TOOLS */
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        const val gdbot = "https://gdtot.pro"
        const val anilistAPI = "https://graphql.anilist.co"
        const val malsyncAPI = "https://api.malsync.moe"
        const val jikanAPI = "https://api.jikan.moe/v4"

        private const val apiKey = "b030404650f279792a8d3287232358e3"

        fun getType(t: String?): TvType = when (t) {
            "movie" -> TvType.Movie
            else -> TvType.TvSeries
        }

        fun getStatus(t: String?): ShowStatus = when (t) {
            "Returning Series" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    // Main Page Final: Indo Viral 18+ Masuk, No Spanish, No Comedy/Anime
    override val mainPage = mainPageOf(
        // 1. Vivamax Movie (Tagalog - Hot New)
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=tl&with_genres=10749,18&without_genres=16,35,10751&primary_release_date.gte=2016-01-01&sort_by=release_date.desc&vote_count.gte=2" to "Vivamax Movie (New)",

        // 2. Vivamax Series (Official Company)
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_companies=149142&without_genres=16,35,10751&first_air_date.gte=2016-01-01&sort_by=first_air_date.desc&vote_count.gte=2" to "Vivamax Series (Uncut)",

        // 3. Global Erotic Thrillers (Softcore Global)
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=10749,53&without_genres=16,35,10751,28,878,12&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=5" to "Global Erotic Thrillers",

        // 4. Japanese Mature Romance
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ja&with_genres=10749,18&without_genres=16,35,10751,28,14&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=2" to "Japanese Mature Romance",

        // 5. Japanese Pinku Style (Softcore)
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ja&with_genres=10749&without_genres=16,35,10751,18&primary_release_date.gte=2016-01-01&sort_by=release_date.desc&vote_count.gte=2" to "Japanese Pinku Style",

        // 6. Korean Intense Drama
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&with_genres=10749,18&without_genres=16,35,10751,28&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=5" to "Korean Intense Drama",

        // 7. Western Affair Stories
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=en&with_genres=10749,18&without_genres=16,35,10751,28,878,12&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=10" to "Western Affair Stories",

        // 8. French Passion
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=fr&with_genres=10749&without_genres=16,35,10751&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=5" to "French Passion & Seduction",

        // 9. Indonesian Viral 18+ (All) - PENGGANTI SPANISH
        // Menampilkan semua film Indo kategori dewasa (Tante Siska, Kelas Bintang, dll)
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=id&include_adult=true&sort_by=popularity.desc" to "Indonesian Viral 18+ (All)",

        // 10. Thai Erotic Drama
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=th&with_genres=10749&without_genres=16,35,10751,28&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=2" to "Thai Erotic Drama",

        // 11. Chinese Romance
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=zh&with_genres=10749,18&without_genres=16,35,10751,14,28&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=5" to "Chinese & Taiwan Romance",

        // 12. Italian Seduction
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=it&with_genres=10749&without_genres=16,35,10751&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=2" to "Italian Seduction",

        // 13. Forbidden Love (Global)
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=18,10749&without_genres=16,35,10751,28,12,878&primary_release_date.gte=2016-01-01&sort_by=popularity.desc&vote_count.gte=10" to "Forbidden Love (Global)",

        // 14. Top Rated Romance
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=10749&without_genres=16,35,10751&primary_release_date.gte=2016-01-01&sort_by=vote_average.desc&vote_count.gte=50" to "Top Rated Romance Drama",

        // 15. Popular Romance This Year
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_genres=10749&without_genres=16,35,10751&primary_release_date.gte=2023-01-01&sort_by=popularity.desc" to "Popular Romance (Hot)"
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

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        return newMovieSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType ?: type).toJson(),
            TvType.Movie,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score = Score.from10(voteAverage)
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
        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&append_to_response=$append&include_video_language=id,en"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&append_to_response=$append&include_video_language=id,en"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        
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
                    cast.name ?: cast.originalName
                    ?: return@mapNotNull null, getImageUrl(cast.profilePath)
                ), roleString = cast.character
            )
        } ?: return null
        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        // FIX V3: "Safe Mode" Trailer
        val trailer = res.videos?.results
            ?.filter { it.site == "YouTube" && it.key?.isNotBlank() == true && it.type == "Trailer" }
            ?.sortedByDescending { it.type == "Trailer" }
            ?.map { "https://www.youtube.com/watch?v=${it.key}" }
            ?.take(1)

        return if (type == TvType.TvSeries) {
            val lastSeason = res.lastEpisodeToAir?.seasonNumber
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$tmdbAPI/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        newEpisode(
                            data = LinkData(
                                data.id,
                                res.externalIds?.imdbId,
                                res.externalIds?.tvdbId,
                                data.type,
                                eps.seasonNumber,
                                eps.episodeNumber,
                                title = title,
                                year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                orgTitle = orgTitle,
                                isAnime = isAnime,
                                airedYear = year,
                                lastSeason = lastSeason,
                                epsTitle = eps.name,
                                jpTitle = res.alternativeTitles?.results?.find { it.iso31661 == "JP" }?.title,
                                date = season.airDate,
                                airedDate = res.releaseDate
                                    ?: res.firstAirDate,
                                isAsian = isAsian,
                                isBollywood = isBollywood,
                                isCartoon = isCartoon
                            ).toJson()
                        ) {
                            this.name =
                                eps.name + if (isUpcoming(eps.airDate)) " • [UPCOMING]" else ""
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
                this.plot = res.overview
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
                    jpTitle = res.alternativeTitles?.results?.find { it.iso31661 == "JP" }?.title,
                    airedDate = res.releaseDate
                        ?: res.firstAirDate,
                    isAsian = isAsian,
                    isBollywood = isBollywood
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.comingSoon = isUpcoming(releaseDate)
                this.year = year
                this.plot = res.overview
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
            {
                invokeMoviebox(
                    title = res.title ?: return@runAllAsync,
                    orgTitle = res.orgTitle,
                    altTitle = res.jpTitle,
                    year = res.year,
                    airedYear = res.airedYear,
                    season = res.season,
                    episode = res.episode,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            },
            {
                invokeIdlix(
                    title = res.title ?: return@runAllAsync,
                    orgTitle = res.orgTitle,
                    altTitle = res.jpTitle,
                    year = if (res.season != null) (res.airedYear ?: res.year) else res.year,
                    season = res.season,
                    episode = res.episode,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        )

        return true
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
        val date: String? = null,
        val airedDate: String? = null,
        val isAsian: Boolean = false,
        val isBollywood: Boolean = false,
        val isCartoon: Boolean = false,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class Genres(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class Seasons(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("air_date") val airDate: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class Trailers(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class ResultsTrailer(
        @JsonProperty("results") val results: List<Trailers>? = null,
    )

    data class AltTitles(
        @JsonProperty("iso_3166_1") val iso31661: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class ResultsAltTitles(
        @JsonProperty("results") val results: ArrayList<AltTitles>? = arrayListOf(),
    )

    data class ExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("tvdb_id") val tvdbId: Int? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class ProductionCountries(
        @JsonProperty("name") val name: String? = null,
    )

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("vote_average") val voteAverage: Any? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @JsonProperty("keywords") val keywords: KeywordResults? = null,
        @JsonProperty("last_episode_to_air") val lastEpisodeToAir: LastEpisodeToAir? = null,
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("external_ids") val externalIds: ExternalIds? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @JsonProperty("alternative_titles") val alternativeTitles: ResultsAltTitles? = null,
        @JsonProperty("production_countries") val productionCountries: ArrayList<ProductionCountries>? = arrayListOf(),
    )
}
