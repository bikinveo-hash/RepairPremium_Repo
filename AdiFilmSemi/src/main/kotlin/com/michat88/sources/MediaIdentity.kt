package com.michat88.sources

import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/** Catalog IDs are never treated as TMDB IDs. Missing identity evidence fails closed. */
internal object MediaIdentity {
    data class Request(
        val titles: List<String>,
        val year: Int?,
        val type: Int,
        val season: Int?,
        val episode: Int?,
        val tmdbId: String?,
        val imdbId: String? = null,
        val seasonYear: Int? = null,
    )

    data class Candidate(
        val key: String,
        val titles: List<String>,
        val year: Int?,
        val type: Int,
        val tmdbId: String? = null,
        val imdbId: String? = null,
        val season: Int? = null,
    )

    data class Episode(val url: String, val season: Int?, val number: Int?)

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun valid(r: Request): Boolean =
        r.type in 1..2 &&
            r.titles.any { normalize(it).isNotEmpty() } &&
            (r.type == 1 ||
                (r.season != null && r.season >= 0 && r.episode != null && r.episode > 0))

    fun matches(r: Request, c: Candidate): Boolean {
        if (!valid(r) || c.type != r.type) return false
        if (r.tmdbId != null && c.tmdbId != null && r.tmdbId != c.tmdbId) return false
        if (r.imdbId != null && c.imdbId != null && r.imdbId != c.imdbId) return false
        if (r.type == 2 && c.season != null && c.season != r.season) return false
        val id =
            (r.tmdbId != null && r.tmdbId == c.tmdbId) || (r.imdbId != null && r.imdbId == c.imdbId)
        val sameYear =
            r.year != null && r.year == c.year ||
                (r.type == 2 &&
                    c.season == r.season &&
                    r.seasonYear != null &&
                    r.seasonYear == c.year)
        if (r.year != null && c.year != null && !sameYear) return false
        if (id) return true
        // No fuzzy/token-overlap fallback: aliases must come from selected TMDB metadata.
        return sameYear &&
            c.titles.any { title ->
                val n = normalize(title)
                n.isNotEmpty() && r.titles.any { normalize(it) == n }
            }
    }

    fun select(r: Request, rows: List<Candidate>): Candidate? =
        rows.distinctBy { it.key }.filter { matches(r, it) }.singleOrNull()

    fun episode(r: Request, rows: List<Episode>): Episode? =
        rows
            .distinctBy { it.url }
            .filter { it.season == r.season && it.number == r.episode }
            .singleOrNull()

    fun text(o: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" } }

    fun objects(a: JSONArray?): List<JSONObject> =
        (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }

    private fun year(s: String?): Int? =
        s?.let { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

    fun idlix(key: String, o: JSONObject, type: Int): Candidate =
        Candidate(
            key,
            listOfNotNull(
                text(o, "title", "name"),
                text(o, "originalTitle", "original_title", "originalName"),
            ),
            year(text(o, "releaseDate", "firstAirDate", "release_date", "first_air_date", "year")),
            type,
            text(o, "tmdbId", "tmdb_id"),
            text(o, "imdbId", "imdb_id"),
        )

    fun catalogTitle(raw: String): String =
        raw.replace(Regex("(?i)^(nonton|streaming|download)\\s+"), "")
            .replace(Regex("(?i)\\s*(subtitle\\s+indonesia|sub\\s+indo)\\s*$"), "")
            .replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "")
            .trim()

    fun oppa(key: String, doc: Document): Candidate? {
        val rawTitle =
            doc.selectFirst(".infolimit h2, h1.entry-title, h1[itemprop=name], h1.title")?.text()
                ?: return null
        val title = catalogTitle(rawTitle)
        val facts = doc.select(".spe span, .info-content .info, .infox .info").map { it.text() }
        val type =
            facts.firstOrNull { Regex("(?i)^(tipe|type)\\s*:").containsMatchIn(it) } ?: return null
        val kind =
            when {
                type.contains("Movie", true) -> 1
                Regex("(?i)drama|series|tv show|anime").containsMatchIn(type) -> 2
                else -> return null
            }
        val release =
            facts.firstOrNull {
                Regex("(?i)^(rilis|released|release|tahun|aired|tayang)\\s*:").containsMatchIn(it)
            }
        val seasonMatch = Regex("(?i)\\s+(?:season|s)\\s*(\\d+)\\s*$").find(title)
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
        val plain = seasonMatch?.let { title.substring(0, it.range.first).trim() } ?: title
        val aliases =
            facts
                .filter {
                    Regex("(?i)^(judul lain|other name|native title|alternative titles?)\\s*:")
                        .containsMatchIn(it)
                }
                .flatMap { it.substringAfter(':').split(',', ';') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val links =
            doc.select(".spe a[href], .info-content a[href], .single-info .infox a[href]").map {
                it.attr("href")
            }
        val tmdb =
            links.firstNotNullOfOrNull {
                Regex("themoviedb\\.org/${if(kind==1) "movie" else "tv"}/(\\d+)")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
            }
        val imdb =
            links.firstNotNullOfOrNull {
                Regex("imdb\\.com/title/(tt\\d+)").find(it)?.groupValues?.get(1)
            }
        return Candidate(
            key,
            listOf(plain) + aliases.map(::catalogTitle),
            year(release) ?: Regex("\\((?:19|20)\\d{2}\\)").find(rawTitle)?.value?.let(::year),
            kind,
            tmdb,
            imdb,
            season,
        )
    }

    fun oppaEpisodes(doc: Document, c: Candidate): List<Episode> =
        doc.select(".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li")
            .mapNotNull { li ->
                val a = li.selectFirst("a[href]") ?: return@mapNotNull null
                val title =
                    li.selectFirst(".epl-title, .playinfo h4, .lchx")?.text()
                        ?: a.attr("title").ifBlank { a.text() }
                val number =
                    Regex("(?i)(?:episode|eps|ep)\\s*(\\d+)\\b")
                        .find(title)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                val season =
                    Regex("(?i)(?:season|s)\\s*(\\d+)\\b")
                        .find(title)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: c.season
                        ?: 1 // unnumbered catalog is season one only; never assume requested
                // season.
                a.absUrl("href").takeIf { it.isNotBlank() }?.let { Episode(it, season, number) }
            }
}
