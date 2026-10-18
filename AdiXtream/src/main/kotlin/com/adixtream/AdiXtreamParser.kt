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
    @param:JsonProperty("media_type") val mediaType: String?,
)

data class TmdbGenre(@param:JsonProperty("name") val name: String)

data class TmdbVideoResult(
    @param:JsonProperty("results") val results: List<TmdbVideo> = emptyList()
)

data class TmdbVideo(
    @param:JsonProperty("type") val type: String,
    @param:JsonProperty("key") val key: String,
    @param:JsonProperty("site") val site: String,
)

data class TmdbCredits(@param:JsonProperty("cast") val cast: List<TmdbCast> = emptyList())

data class TmdbCast(
    @param:JsonProperty("name") val name: String,
    @param:JsonProperty("character") val character: String?,
    @param:JsonProperty("profile_path") val profilePath: String?,
)

data class TmdbRecommendations(
    @param:JsonProperty("results") val results: List<TmdbMovie> = emptyList()
)

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
    @param:JsonProperty("original_title") val originalTitle: String? = null,
    @param:JsonProperty("alternative_titles") val alternativeTitles: TmdbAlternativeTitles? = null,
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
    @param:JsonProperty("original_name") val originalName: String? = null,
    @param:JsonProperty("alternative_titles") val alternativeTitles: TmdbAlternativeTitles? = null,
)

data class TmdbSeason(
    @param:JsonProperty("season_number") val seasonNumber: Int,
    @param:JsonProperty("episode_count") val episodeCount: Int,
)

data class TmdbSeasonDetail(
    @param:JsonProperty("episodes") val episodes: List<TmdbEpisodeDetail> = emptyList()
)

data class TmdbEpisodeDetail(
    @param:JsonProperty("episode_number") val episodeNumber: Int,
    @param:JsonProperty("name") val name: String?,
    @param:JsonProperty("overview") val overview: String?,
    @param:JsonProperty("still_path") val stillPath: String?,
    @param:JsonProperty("air_date") val airDate: String?,
    @param:JsonProperty("vote_average") val voteAverage: Double?,
)

data class TmdbAlternativeTitle(@param:JsonProperty("title") val title: String? = null)

data class TmdbAlternativeTitles(
    @param:JsonProperty("titles") val titles: List<TmdbAlternativeTitle> = emptyList(),
    @param:JsonProperty("results") val results: List<TmdbAlternativeTitle> = emptyList(),
) {
    fun all(): List<String> = (titles + results).mapNotNull { it.title }.distinct()
}
