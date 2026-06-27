package com.adixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        val apiBaseUrl  = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
        val playBaseUrl = "https://netfilm.world"

        val apiHeaders = mapOf(
            "Accept"         to "application/json",
            "x-client-info"  to """{"timezone":"Asia/Jakarta"}""",
            "x-request-lang" to "en",
            "Origin"         to playBaseUrl,
            "Referer"        to "$playBaseUrl/",
            "User-Agent"     to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // ── Helper normalisasi judul ───────────────────────────────────────────
        // Strip semua karakter non-alphanumeric lalu lowercase — identik dengan
        // teknik di AdiMovieBox V1 & V2.
        fun normalize(s: String?): String =
            s?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""

        // ── Step 1: Persiapan keyword ─────────────────────────────────────────
        // Potong sebelum ":" supaya subtitle judul panjang tidak mengganggu.
        // Contoh: "Avengers: Endgame" → "Avengers"
        val searchKeyword = title.substringBefore(":").trim()

        val cleanTitle    = normalize(title)
        val cleanOriginal = normalize(originalTitle)   // judul ID / KO / dll dari TMDB

        // ── Step 2: Search (json= param — tidak butuh manual RequestBody) ─────
        // Memakai parameter json= dari NiceHttp yang sudah di-wrap oleh CloudStream,
        // identik dengan cara yang dipakai MovieboxProvider.
        val searchUrl = "$apiBaseUrl/subject/search"
        val searchPayload = mapOf(
            "keyword"     to searchKeyword,
            "page"        to "1",
            "perPage"     to "20",
            "subjectType" to "0"
        )

        val searchRes = try {
            app.post(searchUrl, headers = apiHeaders, json = searchPayload)
                .parsedSafe<MovieboxSearchResponse>()
        } catch (e: Exception) {
            logError(e)
            return
        }

        val candidates: List<MovieboxSubject> =
            searchRes?.data?.items ?: searchRes?.data?.subjectList ?: return

        // ── Step 3: Smart matching (adaptasi AdiMovieBox V1 + V2) ────────────
        //
        // Urutan pengecekan:
        //   A. Type filter  : movie vs series
        //   B. Title EN     : exact → dua-arah contains (wajib year agar tidak false positive)
        //   C. Original title (ID/KO/dll): exact → dua-arah contains (wajib year)
        //   D. Lulus jika (B || C) && A
        //
        val matched: MovieboxSubject = candidates.find { subject ->
            val cleanSubject = normalize(subject.title)
            val subjectYear  = subject.releaseDate?.take(4)?.toIntOrNull()
            val isYearMatch  = year == null || subjectYear == year

            // Type: series = subjectType 2, movie = 1 atau 3
            val isTypeMatch  = if (isTvSeries) subject.subjectType == 2
                               else (subject.subjectType == 1 || subject.subjectType == 3)

            // Title bahasa Inggris
            val isTitleMatch = cleanSubject.isNotEmpty() && cleanTitle.isNotEmpty() && (
                cleanSubject == cleanTitle ||
                ((cleanSubject.contains(cleanTitle) || cleanTitle.contains(cleanSubject)) && isYearMatch)
            )

            // Judul asli (bahasa Indonesia / Korea / dll)
            val isOrigMatch  = cleanOriginal.isNotEmpty() && cleanSubject.isNotEmpty() && (
                cleanSubject == cleanOriginal ||
                ((cleanSubject.contains(cleanOriginal) || cleanOriginal.contains(cleanSubject)) && isYearMatch)
            )

            (isTitleMatch || isOrigMatch) && isTypeMatch
        } ?: return   // tidak ada hasil cocok → keluar

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
        // Gunakan for-loop (bukan forEach lambda) karena newExtractorLink adalah
        // suspend fun — tidak bisa dipanggil dari dalam lambda biasa.
        for (stream in streams) {
            val streamUrl = stream.url ?: continue
            val quality   = when {
                stream.resolutions?.contains("1080") == true -> Qualities.P1080.value
                stream.resolutions?.contains("720")  == true -> Qualities.P720.value
                stream.resolutions?.contains("480")  == true -> Qualities.P480.value
                else                                          -> Qualities.P360.value
            }

            // newExtractorLink adalah suspend → assign ke val dulu, lalu pass ke callback
            val link = newExtractorLink(
                source = this.name,
                name   = "Moviebox ${stream.resolutions ?: ""}p",
                url    = streamUrl,
                type   = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = playBaseUrl
            }
            callback(link)
        }

        // ── Step 6: Subtitle (dari stream pertama saja) ───────────────────────
        val firstStream = streams.firstOrNull() ?: return
        if (firstStream.id == null || firstStream.format == null) return

        try {
            val captionUrl = "$apiBaseUrl/subject/caption" +
                             "?format=${firstStream.format}&id=${firstStream.id}" +
                             "&subjectId=$subjectId&detailPath=$detailPath"

            val captions = app.get(captionUrl, headers = playHeaders)
                .parsedSafe<MovieboxCaptionResponse>()
                ?.data?.captions ?: return

            // Sama: for-loop karena newSubtitleFile adalah suspend fun
            for (cap in captions) {
                val capUrl = cap.url ?: continue
                val sub = newSubtitleFile(cap.lanName ?: "Unknown", capUrl)
                subtitleCallback(sub)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }
}
