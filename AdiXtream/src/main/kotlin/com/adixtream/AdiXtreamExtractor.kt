package com.adixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.mvvm.logError

object AdiXtreamExtractor : AdiXtream() {

    // ================== EKSTRAKTOR MOVIEBOX ==================
    suspend fun invokeMovieBox(
        tmdbId: String,
        title: String,
        originalTitle: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
        isTvSeries: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
        val playBaseUrl = "https://netfilm.world"

        val apiHeaders = mapOf(
            "Accept"         to "application/json",
            "x-client-info"  to """{"timezone":"Asia/Jakarta"}""",
            "x-request-lang" to "en",
            "Origin"         to playBaseUrl,
            "Referer"        to "$playBaseUrl/",
            "User-Agent"     to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // ── Helper: normalisasi judul ──────────────────────────────────────────
        // Strip semua karakter non-alphanumeric lalu lowercase.
        // Identik dengan teknik yang dipakai AdiMovieBox V1 & V2.
        fun normalize(s: String?): String =
            s?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""

        // ── Step 1: Persiapan keyword ─────────────────────────────────────────
        // Potong sebelum ":" agar subtitle panjang tidak mengganggu pencarian.
        // Contoh: "Avengers: Endgame" → "Avengers"
        val searchKeyword = title.substringBefore(":").trim()

        val cleanTitle    = normalize(title)
        val cleanOriginal = normalize(originalTitle)   // judul bahasa Indonesia / Korea / dll

        // ── Step 2: Search ────────────────────────────────────────────────────
        val searchUrl  = "$apiBaseUrl/subject/search"
        val searchBody = mapOf(
            "keyword"     to searchKeyword,
            "page"        to "1",
            "perPage"     to "20",
            "subjectType" to "0"
        ).toJson()

        val searchRes = try {
            app.post(
                searchUrl,
                headers     = apiHeaders,
                requestBody = searchBody.toRequestBody()
            ).parsedSafe<MovieboxSearchResponse>()
        } catch (e: Exception) {
            logError(e)
            return
        }

        val candidates: List<MovieboxSubject> =
            searchRes?.data?.items ?: searchRes?.data?.subjectList ?: return

        // ── Step 3: Smart matching (adaptasi V1 + V2) ─────────────────────────
        //
        // Urutan pengecekan:
        //   A. Type filter: movie vs series
        //   B. Title match (EN): exact → dua-arah contains (butuh year)
        //   C. Original title match (ID/KO/dll): exact → dua-arah contains (butuh year)
        //   D. Lulus jika (B || C) && A
        //
        val matched: MovieboxSubject = candidates.find { subject ->
            val cleanSubject = normalize(subject.title)
            val subjectYear  = subject.releaseDate?.take(4)?.toIntOrNull()
            val isYearMatch  = year == null || subjectYear == year

            // Type: series = subjectType 2, movie = 1 atau 3
            val isTypeMatch = if (isTvSeries) subject.subjectType == 2
                              else (subject.subjectType == 1 || subject.subjectType == 3)

            // Title EN match
            val isTitleMatch = cleanSubject.isNotEmpty() && cleanTitle.isNotEmpty() && (
                cleanSubject == cleanTitle ||
                ((cleanSubject.contains(cleanTitle) || cleanTitle.contains(cleanSubject)) && isYearMatch)
            )

            // Original title match (bahasa Indonesia / bahasa lain)
            val isOrigMatch = cleanOriginal.isNotEmpty() && cleanSubject.isNotEmpty() && (
                cleanSubject == cleanOriginal ||
                ((cleanSubject.contains(cleanOriginal) || cleanOriginal.contains(cleanSubject)) && isYearMatch)
            )

            (isTitleMatch || isOrigMatch) && isTypeMatch
        } ?: return   // tidak ada hasil yang cocok → keluar

        val subjectId  = matched.subjectId  ?: return
        val detailPath = matched.detailPath ?: return

        val se = season  ?: 0
        val ep = episode ?: 0

        // ── Step 4: Hit play endpoint ─────────────────────────────────────────
        val playUrl = "$playBaseUrl/wefeed-h5api-bff/subject/play" +
                      "?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath"

        val playReferer = "$playBaseUrl/spa/videoPlayPage/movies/$detailPath" +
                          "?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en"

        val playHeaders = apiHeaders + mapOf("Referer" to playReferer)

        val streams = try {
            app.get(playUrl, headers = playHeaders)
                .parsedSafe<MovieboxPlayResponse>()?.data?.streams
        } catch (e: Exception) {
            logError(e)
            return
        } ?: return

        // ── Step 5: Callback stream ───────────────────────────────────────────
        streams.forEach { stream ->
            val streamUrl = stream.url ?: return@forEach
            val quality   = when {
                stream.resolutions?.contains("1080") == true -> Qualities.P1080.value
                stream.resolutions?.contains("720")  == true -> Qualities.P720.value
                stream.resolutions?.contains("480")  == true -> Qualities.P480.value
                else                                          -> Qualities.P360.value
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "Moviebox ${stream.resolutions ?: ""}p",
                    url    = streamUrl,
                    type   = ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = playBaseUrl
                }
            )
        }

        // ── Step 6: Subtitle (dari stream pertama saja) ───────────────────────
        val firstStream = streams.firstOrNull() ?: return
        if (firstStream.id == null || firstStream.format == null) return

        try {
            val captionUrl = "$apiBaseUrl/subject/caption" +
                             "?format=${firstStream.format}&id=${firstStream.id}" +
                             "&subjectId=$subjectId&detailPath=$detailPath"

            app.get(captionUrl, headers = playHeaders)
                .parsedSafe<MovieboxCaptionResponse>()
                ?.data?.captions
                ?.forEach { cap ->
                    subtitleCallback.invoke(
                        newSubtitleFile(cap.lanName ?: "Unknown", cap.url ?: return@forEach)
                    )
                }
        } catch (e: Exception) {
            logError(e)
        }
    }

    // ── Helper: buat RequestBody dari String JSON ──────────────────────────────
    private fun String.toRequestBody() =
        toRequestBody(okhttp3.MediaType.Companion.toMediaTypeOrNull("application/json"))
}
