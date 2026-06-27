package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty

// ================== TMDB DATA CLASSES ==================
data class TmdbResponse(@param:JsonProperty("results") val results: List<TmdbMovie> = emptyList())
data class TmdbMovie(
    @param:JsonProperty("id") val id: Int, 
    @param:JsonProperty("title") val title: String?, 
    @param:JsonProperty("name") val name: String?, 
    @param:JsonProperty("poster_path") val posterPath: String?, 
    @param:JsonProperty("vote_average") val voteAverage: Double?, 
    @param:JsonProperty("media_type") val mediaType: String?
)
data class TmdbGenre(@param:JsonProperty("name") val name: String)
data class TmdbVideoResult(@param:JsonProperty("results") val results: List<TmdbVideo> = emptyList())
data class TmdbVideo(
    @param:JsonProperty("type") val type: String, 
    @param:JsonProperty("key") val key: String, 
    @param:JsonProperty("site") val site: String
)
data class TmdbCredits(@param:JsonProperty("cast") val cast: List<TmdbCast> = emptyList())
data class TmdbCast(
    @param:JsonProperty("name") val name: String, 
    @param:JsonProperty("character") val character: String?, 
    @param:JsonProperty("profile_path") val profilePath: String?
)
data class TmdbRecommendations(@param:JsonProperty("results") val results: List<TmdbMovie> = emptyList())
data class TmdbDetailResponse(
    @param:JsonProperty("title") val title: String?, 
    @param:JsonProperty("poster_path") val posterPath: String?, 
    @param:JsonProperty("backdrop_path") val backdropPath: String?, 
    @param:JsonProperty("overview") val overview: String?, 
    @param:JsonProperty("release_date") val releaseDate: String?, 
    @param:JsonProperty("runtime") val runtime: Int?, 
    @param:JsonProperty("vote_average") val voteAverage: Double?, 
    @param:JsonProperty("genres") val genres: List<TmdbGenre>?, 
    @param:JsonProperty("videos") val videos: TmdbVideoResult?, 
    @param:JsonProperty("credits") val credits: TmdbCredits?, 
    @param:JsonProperty("recommendations") val recommendations: TmdbRecommendations?, 
    @param:JsonProperty("original_title") val originalTitle: String? = null
)
data class TmdbTvDetailResponse(
    @param:JsonProperty("name") val name: String?, 
    @param:JsonProperty("poster_path") val posterPath: String?, 
    @param:JsonProperty("backdrop_path") val backdropPath: String?, 
    @param:JsonProperty("overview") val overview: String?, 
    @param:JsonProperty("first_air_date") val firstAirDate: String?, 
    @param:JsonProperty("vote_average") val voteAverage: Double?, 
    @param:JsonProperty("seasons") val seasons: List<TmdbSeason>?, 
    @param:JsonProperty("genres") val genres: List<TmdbGenre>?, 
    @param:JsonProperty("videos") val videos: TmdbVideoResult?, 
    @param:JsonProperty("credits") val credits: TmdbCredits?, 
    @param:JsonProperty("recommendations") val recommendations: TmdbRecommendations?, 
    @param:JsonProperty("original_name") val originalName: String? = null
)
data class TmdbSeason(
    @param:JsonProperty("season_number") val seasonNumber: Int, 
    @param:JsonProperty("episode_count") val episodeCount: Int
)
data class TmdbSeasonDetail(@param:JsonProperty("episodes") val episodes: List<TmdbEpisodeDetail> = emptyList())
data class TmdbEpisodeDetail(
    @param:JsonProperty("episode_number") val episodeNumber: Int, 
    @param:JsonProperty("name") val name: String?, 
    @param:JsonProperty("overview") val overview: String?, 
    @param:JsonProperty("still_path") val stillPath: String?, 
    @param:JsonProperty("air_date") val airDate: String?, 
    @param:JsonProperty("vote_average") val voteAverage: Double?
)

// ================== ADIMOVIEBOX (OLD/V1) DATA CLASSES ==================
data class AdimovieboxResponse(
    @param:JsonProperty("data") val data: AdimovieboxData? = null,
)
data class AdimovieboxData(
    @param:JsonProperty("items") val items: List<AdimovieboxItem>? = emptyList(),
    @param:JsonProperty("streams") val streams: List<AdimovieboxStreamItem>? = emptyList(),
    @param:JsonProperty("captions") val captions: List<AdimovieboxCaptionItem>? = emptyList(),
)
data class AdimovieboxItem(
    @param:JsonProperty("subjectId") val subjectId: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("releaseDate") val releaseDate: String? = null,
    @param:JsonProperty("detailPath") val detailPath: String? = null,
    @param:JsonProperty("subjectType") val subjectType: Int? = null
)
data class AdimovieboxStreamItem(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("format") val format: String? = null,
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("resolutions") val resolutions: String? = null,
)
data class AdimovieboxCaptionItem(
    @param:JsonProperty("lanName") val lanName: String? = null,
    @param:JsonProperty("url") val url: String? = null,
)

// ================== ADIMOVIEBOX 2 (NEW) DATA CLASSES ==================
data class Adimoviebox2SearchResponse(
    @param:JsonProperty("data") val data: Adimoviebox2SearchData? = null
)
data class Adimoviebox2SearchData(
    @param:JsonProperty("results") val results: List<Adimoviebox2SearchResult>? = emptyList()
)
data class Adimoviebox2SearchResult(
    @param:JsonProperty("subjects") val subjects: List<Adimoviebox2Subject>? = emptyList()
)
data class Adimoviebox2Subject(
    @param:JsonProperty("subjectId") val subjectId: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("releaseDate") val releaseDate: String? = null,
    @param:JsonProperty("subjectType") val subjectType: Int? = null 
)
data class Adimoviebox2PlayResponse(
    @param:JsonProperty("data") val data: Adimoviebox2PlayData? = null
)
data class Adimoviebox2PlayData(
    @param:JsonProperty("streams") val streams: List<Adimoviebox2Stream>? = emptyList()
)
data class Adimoviebox2Stream(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("format") val format: String? = null,
    @param:JsonProperty("resolutions") val resolutions: String? = null,
    @param:JsonProperty("signCookie") val signCookie: String? = null 
)
data class Adimoviebox2SubtitleResponse(
    @param:JsonProperty("data") val data: Adimoviebox2SubtitleData? = null
)
data class Adimoviebox2SubtitleData(
    @param:JsonProperty("extCaptions") val extCaptions: List<Adimoviebox2Caption>? = emptyList()
)
data class Adimoviebox2Caption(
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("language") val language: String? = null,
    @param:JsonProperty("lanName") val lanName: String? = null,
    @param:JsonProperty("lan") val lan: String? = null
)

// ================== MOVIEBOX DATA CLASSES ==================
data class MovieBoxSearchResponse(
    @param:JsonProperty("data") val data: MovieBoxSearchData? = null
)
data class MovieBoxSearchData(
    @param:JsonProperty("subjectList") val subjectList: List<MovieBoxSubject>? = emptyList(),
    @param:JsonProperty("items") val items: List<MovieBoxSubject>? = emptyList()
)
data class MovieBoxSubject(
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("subjectId") val subjectId: String? = null,
    @param:JsonProperty("subjectType") val subjectType: Int? = null,
    @param:JsonProperty("detailPath") val detailPath: String? = null,
    @param:JsonProperty("releaseDate") val releaseDate: String? = null
)
data class MovieBoxPlayResponse(
    @param:JsonProperty("data") val data: MovieBoxPlayData? = null
)
data class MovieBoxPlayData(
    @param:JsonProperty("streams") val streams: List<MovieBoxStreamItem>? = emptyList()
)
data class MovieBoxStreamItem(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("resolutions") val resolutions: String? = null,
    @param:JsonProperty("format") val format: String? = null
)
data class MovieBoxCaptionResponse(
    @param:JsonProperty("data") val data: MovieBoxCaptionData? = null
)
data class MovieBoxCaptionData(
    @param:JsonProperty("captions") val captions: List<MovieBoxCaptionItem>? = emptyList()
)
data class MovieBoxCaptionItem(
    @param:JsonProperty("lanName") val lanName: String? = null,
    @param:JsonProperty("url") val url: String? = null
)
