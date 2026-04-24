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
data class TmdbDetailResponse(@JsonProperty("title") val title: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("backdrop_path") val backdropPath: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("release_date") val releaseDate: String?, @JsonProperty("runtime") val runtime: Int?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("genres") val genres: List<TmdbGenre>?, @JsonProperty("videos") val videos: TmdbVideoResult?, @JsonProperty("credits") val credits: TmdbCredits?, @JsonProperty("recommendations") val recommendations: TmdbRecommendations?)
data class TmdbTvDetailResponse(@JsonProperty("name") val name: String?, @JsonProperty("poster_path") val posterPath: String?, @JsonProperty("backdrop_path") val backdropPath: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("first_air_date") val firstAirDate: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("seasons") val seasons: List<TmdbSeason>?, @JsonProperty("genres") val genres: List<TmdbGenre>?, @JsonProperty("videos") val videos: TmdbVideoResult?, @JsonProperty("credits") val credits: TmdbCredits?, @JsonProperty("recommendations") val recommendations: TmdbRecommendations?)
data class TmdbSeason(@JsonProperty("season_number") val seasonNumber: Int, @JsonProperty("episode_count") val episodeCount: Int)
data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisodeDetail>)
data class TmdbEpisodeDetail(@JsonProperty("episode_number") val episodeNumber: Int, @JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("still_path") val stillPath: String?, @JsonProperty("air_date") val airDate: String?, @JsonProperty("vote_average") val voteAverage: Double?)

// ================== ADIMOVIEBOX (OLD/V1) DATA CLASSES ==================
data class AdimovieboxResponse(
    @JsonProperty("data") val data: AdimovieboxData? = null,
)
data class AdimovieboxData(
    @JsonProperty("items") val items: ArrayList<AdimovieboxItem>? = arrayListOf(),
    @JsonProperty("streams") val streams: ArrayList<AdimovieboxStreamItem>? = arrayListOf(),
    @JsonProperty("captions") val captions: ArrayList<AdimovieboxCaptionItem>? = arrayListOf(),
)
data class AdimovieboxItem(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("detailPath") val detailPath: String? = null,
)
data class AdimovieboxStreamItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("resolutions") val resolutions: String? = null,
)
data class AdimovieboxCaptionItem(
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("url") val url: String? = null,
)

// ================== ADIMOVIEBOX 2 (NEW) DATA CLASSES ==================
data class Adimoviebox2SearchResponse(
    @JsonProperty("data") val data: Adimoviebox2SearchData? = null
)
data class Adimoviebox2SearchData(
    @JsonProperty("results") val results: ArrayList<Adimoviebox2SearchResult>? = arrayListOf()
)
data class Adimoviebox2SearchResult(
    @JsonProperty("subjects") val subjects: ArrayList<Adimoviebox2Subject>? = arrayListOf()
)
data class Adimoviebox2Subject(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("subjectType") val subjectType: Int? = null 
)
data class Adimoviebox2PlayResponse(
    @JsonProperty("data") val data: Adimoviebox2PlayData? = null
)
data class Adimoviebox2PlayData(
    @JsonProperty("streams") val streams: ArrayList<Adimoviebox2Stream>? = arrayListOf()
)
data class Adimoviebox2Stream(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("resolutions") val resolutions: String? = null,
    @JsonProperty("signCookie") val signCookie: String? = null 
)
data class Adimoviebox2SubtitleResponse(
    @JsonProperty("data") val data: Adimoviebox2SubtitleData? = null
)
data class Adimoviebox2SubtitleData(
    @JsonProperty("extCaptions") val extCaptions: ArrayList<Adimoviebox2Caption>? = arrayListOf()
)
data class Adimoviebox2Caption(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("lan") val lan: String? = null
)
