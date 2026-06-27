package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty

// ================== TMDB DATA CLASSES ==================
data class TmdbResponse(@JsonProperty("results") val results: List<TmdbMovie>)
data class TmdbMovie(@JsonProperty("id") val id: Int, @JsonProperty("title") val title: String?, @JsonProperty("name") val name: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("media_type") val mediaType: String?)
data class TmdbGenre(@JsonProperty("name") val name: String)
data class TmdbVideoResult(@JsonProperty("results") val results: List<TmdbVideo>)
data class TmdbVideo(@JsonProperty("type") val type: String, @JsonProperty("key") val key: String, @JsonProperty("site") val site: String)
data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>)
data class TmdbCast(@JsonProperty("name") val name: String, @JsonProperty("character") val character: String?, @JsonProperty("profile_path") val profilePath: String?)
data class TmdbRecommendations(@JsonProperty("results") val results: List<TmdbMovie>)
data class TmdbDetailResponse(@JsonProperty("title") val title: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("backdrop_path") val backdropPath: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("release_date") val releaseDate: String?, @JsonProperty("runtime") val runtime: Int?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("genres") val genres: List<TmdbGenre>?, @JsonProperty("videos") val videos: TmdbVideoResult?, @JsonProperty("credits") val credits: TmdbCredits?, @JsonProperty("recommendations") val recommendations: TmdbRecommendations?, @JsonProperty("original_title") val originalTitle: String? = null)
data class TmdbTvDetailResponse(@JsonProperty("name") val name: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("backdrop_path") val backdropPath: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("first_air_date") val firstAirDate: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("seasons") val seasons: List<TmdbSeason>?, @JsonProperty("genres") val genres: List<TmdbGenre>?, @JsonProperty("videos") val videos: TmdbVideoResult?, @JsonProperty("credits") val credits: TmdbCredits?, @JsonProperty("recommendations") val recommendations: TmdbRecommendations?, @JsonProperty("original_name") val originalName: String? = null)
data class TmdbSeason(@JsonProperty("season_number") val seasonNumber: Int, @JsonProperty("episode_count") val episodeCount: Int)
data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisodeDetail>)
data class TmdbEpisodeDetail(@JsonProperty("episode_number") val episodeNumber: Int, @JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("still_path") val stillPath: String?, @JsonProperty("air_date") val airDate: String?, @JsonProperty("vote_average") val voteAverage: Double?)

// ================== MOVIEBOX DATA CLASSES ==================
data class MovieboxSearchResponse(
    @JsonProperty("data") val data: MovieboxSearchData? = null
)
data class MovieboxSearchData(
    @JsonProperty("items")       val items:       List<MovieboxSubject>? = null,
    @JsonProperty("subjectList") val subjectList: List<MovieboxSubject>? = null
)
data class MovieboxSubject(
    @JsonProperty("subjectId")   val subjectId:   String? = null,
    @JsonProperty("title")       val title:       String? = null,
    @JsonProperty("subjectType") val subjectType: Int?    = null,
    @JsonProperty("detailPath")  val detailPath:  String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null
)

data class MovieboxPlayResponse(
    @JsonProperty("data") val data: MovieboxPlayData? = null
)
data class MovieboxPlayData(
    @JsonProperty("streams") val streams: List<MovieboxStreamItem>? = null
)
data class MovieboxStreamItem(
    @JsonProperty("id")          val id:          String? = null,
    @JsonProperty("url")         val url:         String? = null,
    @JsonProperty("resolutions") val resolutions: String? = null,
    @JsonProperty("format")      val format:      String? = null
)

data class MovieboxCaptionResponse(
    @JsonProperty("data") val data: MovieboxCaptionData? = null
)
data class MovieboxCaptionData(
    @JsonProperty("captions") val captions: List<MovieboxCaptionItem>? = null
)
data class MovieboxCaptionItem(
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("url")     val url:     String? = null
)
