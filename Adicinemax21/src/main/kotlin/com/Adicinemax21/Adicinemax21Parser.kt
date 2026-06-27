package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonProperty

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

// ================== KISSKH DATA CLASSES ==================
data class KisskhMedia(
    @param:JsonProperty("id") val id: Int?, 
    @param:JsonProperty("title") val title: String?
)
data class KisskhDetail(
    @param:JsonProperty("episodes") val episodes: List<KisskhEpisode>?
)
data class KisskhEpisode(
    @param:JsonProperty("id") val id: Int?, 
    @param:JsonProperty("number") val number: Double?
)
data class KisskhKey(
    @param:JsonProperty("key") val key: String?
)
data class KisskhSources(
    @param:JsonProperty("Video") val video: String?, 
    @param:JsonProperty("ThirdParty") val thirdParty: String?
)
data class KisskhSubtitle(
    @param:JsonProperty("src") val src: String?, 
    @param:JsonProperty("label") val label: String?
)

// ================== VIDLINK DATA CLASSES ==================
data class VidlinkSources(
    @param:JsonProperty("stream") val stream: Stream? = null,
) {
    data class Stream(
        @param:JsonProperty("playlist") val playlist: String? = null,
    )
}
