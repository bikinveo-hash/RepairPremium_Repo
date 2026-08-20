package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonProperty

// ================== MOVIEBOX DATA CLASSES ==================
// Menggantikan seluruh data class Adimoviebox (V1) dan Adimoviebox2 (V2).
// Semua di-prefix "Moviebox" supaya tidak bentrok dengan nama generik
// (StreamItem / PlayData / CoverItem) milik MovieBoxProvider asli.
//
// Endpoint yang dipakai sebagai source playback:
//   POST /wefeed-mobile-bff/subject-api/search/v2            -> data.results[].subjects[]
//   GET  /wefeed-mobile-bff/subject-api/season-info          -> data.seasons[]
//   GET  /wefeed-mobile-bff/subject-api/play-info            -> data.streams[]
//   GET  /wefeed-mobile-bff/subject-api/get-stream-captions  -> data.extCaptions[]

data class MovieboxSearchResponse(
    @param:JsonProperty("code") val code: Int? = null,
    @param:JsonProperty("data") val data: MovieboxSearchData? = null,
)

data class MovieboxSearchData(
    @param:JsonProperty("results") val results: List<MovieboxSearchResult>? = emptyList(),
)

data class MovieboxSearchResult(
    @param:JsonProperty("subjects") val subjects: List<MovieboxSubject>? = emptyList(),
)

data class MovieboxSubject(
    @param:JsonProperty("subjectId") val subjectId: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("releaseDate") val releaseDate: String? = null,
    // 1 = Movie, 2 = TV. Nilai lain (mis. 9 = UGC) tidak bisa diputar via play-info.
    @param:JsonProperty("subjectType") val subjectType: Int? = null,
)

// [FIX-4] season-info dipakai untuk mengetahui indexing season milik MovieBox
// (endpoint & struktur mengikuti MovieBoxProvider: data.seasons[] { se, maxEp }).
data class MovieboxSeasonInfoResponse(
    @param:JsonProperty("code") val code: Int? = null,
    @param:JsonProperty("data") val data: MovieboxSeasonInfoData? = null,
)

data class MovieboxSeasonInfoData(
    @param:JsonProperty("seasons") val seasons: List<MovieboxSeasonItem>? = emptyList(),
)

data class MovieboxSeasonItem(
    @param:JsonProperty("se") val se: Int? = null,
    @param:JsonProperty("maxEp") val maxEp: Int? = null,
)

data class MovieboxPlayInfoResponse(
    @param:JsonProperty("code") val code: Int? = null,
    @param:JsonProperty("message") val message: String? = null,
    @param:JsonProperty("data") val data: MovieboxPlayData? = null,
)

data class MovieboxPlayData(
    @param:JsonProperty("streams") val streams: List<MovieboxStreamItem>? = emptyList(),
)

data class MovieboxStreamItem(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("format") val format: String? = null,
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("resolutions") val resolutions: String? = null,
    @param:JsonProperty("codecName") val codecName: String? = null,
    // Wajib ada, dikirim balik sebagai header Cookie lewat getVideoInterceptor.
    @param:JsonProperty("signCookie") val signCookie: String? = null,
)

data class MovieboxCaptionResponse(
    @param:JsonProperty("data") val data: MovieboxCaptionData? = null,
)

data class MovieboxCaptionData(
    @param:JsonProperty("extCaptions") val extCaptions: List<MovieboxCaption>? = emptyList(),
)

data class MovieboxCaption(
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("lan") val lan: String? = null,
    @param:JsonProperty("lanName") val lanName: String? = null,
    @param:JsonProperty("language") val language: String? = null,
)

// ================== KISSKH DATA CLASSES ==================
// Catatan: Adicinemax21Extractor punya versi privat (nested) dari class ini,
// jadi yang di bawah ini secara efektif tidak terpakai. Sengaja TIDAK dihapus
// karena berada di luar scope migrasi Adimoviebox.
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
