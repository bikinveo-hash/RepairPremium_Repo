package com.adixtream

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Verify full subject identity before choosing a canonical original from an explicit dub family.
 */
internal object AdiXtreamMovieboxMapping {
    data class Request(
        val titles: List<String>,
        val year: Int?,
        val type: Int,
        val season: Int?,
        val episode: Int?,
        val tmdbId: String? = null,
        val imdbId: String? = null,
        val overview: String? = null,
        val runtimeMinutes: Int? = null,
        val castNames: List<String> = emptyList(),
        val works: List<Work>? = null,
        val excludedSubjects: Set<String> = emptySet(),
        val localizedTitles: List<String> = emptyList(),
        val localizedOverviews: List<String> = emptyList(),
        val originalLanguage: String? = null,
    )

    data class Subject(val id: String, val title: String, val year: Int?, val type: Int)

    data class Edition(val subject: Subject, val metadata: JSONObject)

    data class Result(
        val subject: Subject?,
        val reason: String,
        val metadata: JSONObject? = null,
        val editions: List<Edition> = emptyList(),
        val candidates: Int = 0,
        val verified: Int = 0,
    )

    /** Metadata only: deliberately cannot hold media URLs, headers, cookies or bearer tokens. */
    class CatalogCache(
        private val ttlMs: Long = 30_000,
        private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    ) {
        private val rows = linkedMapOf<String, Pair<Long, String>>()

        @Synchronized
        fun get(key: String): String? =
            rows[key]?.takeIf { now() - it.first in 0 until ttlMs }?.second

        @Synchronized
        fun put(key: String, value: String) {
            if (rows.size >= 24) rows.remove(rows.keys.first())
            rows[key] = now() to value
        }
    }

    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun valid(r: Request): Boolean =
        r.type in 1..2 &&
            (r.year != null || r.tmdbId != null || r.imdbId != null) &&
            r.titles.any { normalize(it).isNotEmpty() } &&
            (r.type == 1 ||
                (r.season != null && r.season >= 0 && r.episode != null && r.episode > 0))

    fun subject(o: JSONObject): Subject? {
        val id = AdiXtreamMovieboxPlayback.text(o, "subjectId")
        val title = AdiXtreamMovieboxPlayback.text(o, "title")
        if (!id.matches(Regex("[0-9]+")) || title.isBlank()) return null
        return Subject(
            id,
            title,
            AdiXtreamMovieboxPlayback.text(o, "releaseDate").take(4).toIntOrNull(),
            o.optInt("subjectType", -1),
        )
    }

    fun searchObjects(raw: String?): List<JSONObject> =
        try {
            val root = JSONObject(raw ?: "{}")
            if (root.optInt("code", -1) != 0) emptyList()
            else {
                val data = root.optJSONObject("data")
                AdiXtreamMovieboxPlayback.objects(data?.optJSONArray("results")).flatMap {
                    AdiXtreamMovieboxPlayback.objects(it.optJSONArray("subjects"))
                } + AdiXtreamMovieboxPlayback.objects(data?.optJSONArray("items"))
            }
        } catch (_: Exception) {
            emptyList()
        }

    fun parseSearch(raw: String?): List<Subject> = searchObjects(raw).mapNotNull(::subject)

    private fun titleMatches(r: Request, s: Subject): Boolean {
        fun exact(value: String) =
            (r.titles + r.localizedTitles).any {
                normalize(it).isNotEmpty() && comparable(it) == comparable(value)
            }
        if (exact(s.title)) return true
        if (r.type != 2 || r.season == null) return false
        // Moviebox displays series ranges such as S1-S3. This is display metadata,
        // never permission to renumber the actual requested season/episode.
        val suffix =
            Regex("(?i)\\s+S(\\d+)(?:\\s*[-–]\\s*S?(\\d+))?\\s*$").find(s.title) ?: return false
        val first = suffix.groupValues[1].toIntOrNull() ?: return false
        val last = suffix.groupValues[2].toIntOrNull() ?: first
        return r.season in first..last && exact(s.title.substring(0, suffix.range.first))
    }

    data class Work(val id: String, val overview: String)

    private fun comparable(value: String): String = normalize(value).replace(" ", "")

    fun tmdbWorks(raw: String?, r: Request): List<Work>? =
        try {
            val root = JSONObject(raw ?: "{}")
            if (!root.has("results") || root.optInt("total_pages", 1) > 1) null
            else
                AdiXtreamMovieboxPlayback.objects(root.optJSONArray("results"))
                    .filter { row ->
                        val year =
                            AdiXtreamMovieboxPlayback.text(
                                    row,
                                    if (r.type == 1) "release_date" else "first_air_date",
                                )
                                .take(4)
                                .toIntOrNull()
                        year == r.year &&
                            listOf("title", "original_title", "name", "original_name").any { key ->
                                val title = AdiXtreamMovieboxPlayback.text(row, key)
                                title.isNotEmpty() &&
                                    r.titles.any { comparable(it) == comparable(title) }
                            }
                    }
                    .mapNotNull { row ->
                        AdiXtreamMovieboxPlayback.text(row, "id")
                            .takeIf { it.matches(Regex("[0-9]+")) }
                            ?.let { Work(it, AdiXtreamMovieboxPlayback.text(row, "overview")) }
                    }
                    .distinctBy { it.id }
        } catch (_: Exception) {
            null
        }

    /** Translations belong to the selected TMDB ID. Never translate or infer a franchise title. */
    fun hydrate(r: Request, meta: JSONObject): Request {
        if (r.tmdbId == null || AdiXtreamMovieboxPlayback.text(meta, "id") != r.tmdbId) return r
        val translations =
            AdiXtreamMovieboxPlayback.objects(
                    meta.optJSONObject("translations")?.optJSONArray("translations")
                )
                .filter { AdiXtreamMovieboxPlayback.text(it, "iso_639_1") in setOf("en", "id") }
                .sortedBy { if (AdiXtreamMovieboxPlayback.text(it, "iso_639_1") == "id") 0 else 1 }
                .mapNotNull { it.optJSONObject("data") }
        val alternatives = meta.optJSONObject("alternative_titles")
        val aliases =
            AdiXtreamMovieboxPlayback.objects(
                alternatives?.optJSONArray("titles") ?: alternatives?.optJSONArray("results")
            )
        val priority =
            (translations.flatMap { t ->
                    listOf("title", "name").map { AdiXtreamMovieboxPlayback.text(t, it) }
                } +
                    aliases
                        .filter {
                            AdiXtreamMovieboxPlayback.text(it, "iso_3166_1") in
                                setOf("ID", "US", "GB")
                        }
                        .map { AdiXtreamMovieboxPlayback.text(it, "title") })
                .filter { it.isNotBlank() }
                .distinctBy(::normalize)
        val titles =
            (r.titles +
                    listOf("title", "original_title", "name", "original_name").map {
                        AdiXtreamMovieboxPlayback.text(meta, it)
                    } +
                    priority +
                    aliases.map { AdiXtreamMovieboxPlayback.text(it, "title") })
                .filter { it.isNotBlank() }
                .distinctBy(::normalize)
        return r.copy(
            titles = titles,
            localizedTitles = priority,
            originalLanguage =
                AdiXtreamMovieboxPlayback.text(meta, "original_language").takeIf {
                    it.matches(Regex("[a-z]{2,3}"))
                } ?: r.originalLanguage,
            localizedOverviews =
                translations
                    .map { AdiXtreamMovieboxPlayback.text(it, "overview") }
                    .filter { it.isNotBlank() }
                    .distinct(),
            year =
                r.year
                    ?: AdiXtreamMovieboxPlayback.text(
                            meta,
                            if (r.type == 1) "release_date" else "first_air_date",
                        )
                        .take(4)
                        .toIntOrNull(),
            imdbId =
                r.imdbId
                    ?: meta.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf {
                        it.matches(Regex("tt[0-9]{5,12}"))
                    },
            overview =
                AdiXtreamMovieboxPlayback.text(meta, "overview").takeIf { it.isNotBlank() }
                    ?: r.overview,
            castNames =
                (r.castNames +
                        AdiXtreamMovieboxPlayback.objects(
                                meta.optJSONObject("credits")?.optJSONArray("cast")
                            )
                            .take(8)
                            .map { AdiXtreamMovieboxPlayback.text(it, "name") })
                    .filter { it.isNotBlank() }
                    .distinct(),
            runtimeMinutes = r.runtimeMinutes ?: meta.optInt("runtime", 0).takeIf { it > 0 },
        )
    }

    private fun requestPlot(r: Request, description: String): Pair<Int, Double> =
        (listOfNotNull(r.overview) + r.localizedOverviews)
            .map { plotScore(it, description) }
            .maxWithOrNull(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })
            ?: (0 to 0.0)

    fun queries(r: Request): List<String> {
        val titles = r.titles.map { it.trim() }.filter { it.isNotBlank() }.distinctBy(::normalize)
        val primary = titles.take(2)
        val aliases =
            titles.drop(2).sortedByDescending { title ->
                // Prefer distinctive official aliases over very short franchise-like aliases.
                normalize(title).split(' ').distinct().size
            }
        val main = titles.firstOrNull() ?: return emptyList()
        val prefix =
            main.substringBefore(':').trim().takeIf { ':' in main && normalize(it).length >= 5 }
        return (primary +
                r.localizedTitles +
                listOf(normalize(main)) +
                aliases +
                listOfNotNull(prefix))
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(12)
    }

    fun hasMore(raw: String?): Boolean =
        try {
            val root = JSONObject(raw ?: "{}")
            val data = root.optJSONObject("data")
            root.optInt("code", -1) == 0 &&
                (data?.optJSONObject("pager")?.optBoolean("hasMore", false) == true ||
                    data?.optBoolean("hasMore", false) == true)
        } catch (_: Exception) {
            false
        }

    private val stopWords =
        setOf(
            "the",
            "and",
            "with",
            "from",
            "that",
            "this",
            "they",
            "their",
            "into",
            "after",
            "before",
            "when",
            "where",
            "who",
            "what",
            "which",
            "while",
            "until",
            "through",
            "than",
            "then",
            "have",
            "has",
            "had",
            "are",
            "was",
            "were",
            "will",
            "would",
            "could",
            "should",
            "his",
            "her",
            "him",
            "she",
            "for",
            "not",
            "but",
            "its",
            "you",
            "your",
            "our",
            "out",
            "can",
            "one",
            "new",
            "all",
            "only",
            "own",
            "about",
            "against",
            "must",
            "being",
            "been",
            "life",
            "time",
            "world",
            "city",
            "family",
            "friends",
            "people",
            "young",
            "man",
            "woman",
            "find",
            "finds",
            "begins",
            "old",
            "story",
            "dan",
            "yang",
            "dari",
            "untuk",
            "dengan",
            "adalah",
            "ini",
            "itu",
            "mereka",
            "sebuah",
            "seorang",
            "pada",
            "dalam",
            "ketika",
            "saat",
            "setelah",
            "sebelum",
            "oleh",
            "akan",
            "telah",
            "sudah",
            "harus",
            "juga",
            "namun",
            "tetapi",
            "hingga",
            "atau",
            "menjadi",
            "karena",
            "agar",
            "tentang",
            "lebih",
            "sebagai",
            "sangat",
            "para",
            "pun",
            "kembali",
            "hidup",
            "dunia",
            "kota",
            "keluarga",
            "teman",
            "kisah",
        )

    private fun words(s: String): Set<String> =
        normalize(s).split(' ').filter { it.length >= 3 && it !in stopWords }.toSet()

    fun plotScore(a: String, b: String): Pair<Int, Double> {
        val left = words(a)
        val right = words(b)
        val shared = left.intersect(right).size
        return shared to
            if (minOf(left.size, right.size) < 4) 0.0
            else shared.toDouble() / minOf(left.size, right.size)
    }

    fun mergeMetadata(native: JSONObject?, web: JSONObject): JSONObject {
        val merged = native?.let { JSONObject(it.toString()) } ?: JSONObject()
        val ids = setOf("imdbId", "imdb_id", "tmdbId", "tmdb_id")
        for (key in listOf("externalIds", "external_ids")) {
            val a = merged.optJSONObject(key)
            val b = web.optJSONObject(key)
            if (a != null && b != null) merged.put(key, mergeMetadata(a, b))
            if (merged.optJSONObject(key)?.optBoolean("identityConflict", false) == true)
                merged.put("identityConflict", true)
        }
        web.keys().forEach { key ->
            val value = web.get(key)
            val empty =
                value == JSONObject.NULL ||
                    (value is String && value.isBlank()) ||
                    (value is JSONArray && value.length() == 0) ||
                    (value is JSONObject && value.length() == 0) ||
                    (key in setOf("duration", "durationSeconds") && value.toString() == "0")
            val prior = merged.optString(key)
            val incoming = value.toString()
            val pattern =
                if (key.startsWith("imdb")) Regex("tt[0-9]{5,12}") else Regex("[1-9][0-9]*")
            if (
                key in ids &&
                    pattern.matches(prior) &&
                    pattern.matches(incoming) &&
                    prior != incoming
            )
                merged.put("identityConflict", true)
            val shorter =
                key == "description" && merged.optString(key).length > value.toString().length
            if (key !in setOf("externalIds", "external_ids") || merged.optJSONObject(key) == null)
                if (!shorter && (!empty || !merged.has(key))) merged.put(key, value)
        }
        return merged
    }

    fun identity(r: Request, meta: JSONObject): String {
        if (meta.optBoolean("identityConflict", false)) return "EXTERNAL_ID_CONFLICT"
        fun external(vararg keys: String): Set<String> =
            (listOf(meta) +
                    listOfNotNull(
                        meta.optJSONObject("externalIds"),
                        meta.optJSONObject("external_ids"),
                    ))
                .flatMap { item ->
                    keys.map { AdiXtreamMovieboxPlayback.text(item, it) }.filter { it.isNotBlank() }
                }
                .toSet()
        val tmdb = external("tmdbId", "tmdb_id").filter { it.matches(Regex("[1-9][0-9]*")) }.toSet()
        val imdb =
            external("imdbId", "imdb_id").filter { it.matches(Regex("tt[0-9]{5,12}")) }.toSet()
        if (r.tmdbId != null && tmdb.any { it != r.tmdbId }) return "EXTERNAL_ID_CONFLICT"
        if (r.imdbId != null && imdb.any { it != r.imdbId }) return "EXTERNAL_ID_CONFLICT"
        if (
            r.originalLanguage != null &&
                AdiXtreamMovieboxPlayback.audioLanguages(playbackMetadata(meta)).any {
                    AdiXtreamMovieboxPlayback.audioCategory(it) ==
                        AdiXtreamMovieboxPlayback.AudioCategory.UNSUPPORTED &&
                        AdiXtreamMovieboxPlayback.audioName(it) !=
                            AdiXtreamMovieboxPlayback.audioName(r.originalLanguage)
                }
        )
            return "AUDIO_EDITION_UNVERIFIED"
        val durationSeconds =
            meta.optInt("durationSeconds", 0).takeIf { it > 0 }
                ?: AdiXtreamMovieboxPlayback.text(meta, "duration").toIntOrNull()?.takeIf { it > 0 }
        if (
            r.type == 1 &&
                durationSeconds != null &&
                r.runtimeMinutes != null &&
                kotlin.math.abs(durationSeconds / 60 - r.runtimeMinutes) >
                    maxOf(25, r.runtimeMinutes / 3)
        )
            return "RUNTIME_MISMATCH"
        if ((r.tmdbId != null && r.tmdbId in tmdb) || (r.imdbId != null && r.imdbId in imdb))
            return "EXTERNAL_ID_MATCH"
        // Only the real MainAPI supplies a selected TMDB ID. Unit schema fixtures may omit it.
        if (r.tmdbId == null) return "LEGACY_TITLE_YEAR"
        val works = r.works
        val primary =
            subject(meta)?.let {
                titleMatches(r.copy(titles = r.titles.take(1), localizedTitles = emptyList()), it)
            } == true

        val description = AdiXtreamMovieboxPlayback.text(meta, "description")
        val (shared, score) = requestPlot(r, description)
        val other =
            works
                .orEmpty()
                .filter { it.id != r.tmdbId }
                .maxOfOrNull { plotScore(it.overview, description).second } ?: 0.0
        val names =
            AdiXtreamMovieboxPlayback.objects(meta.optJSONArray("staffList"))
                .map { normalize(AdiXtreamMovieboxPlayback.text(it, "name")) }
                .filter { it.isNotBlank() }
                .toSet()
        if (other >= 0.5 && other >= score + 0.2) return "AMBIGUOUS_TMDB_WORK"
        val castAgrees =
            r.castNames.map(::normalize).filter { it.isNotBlank() }.toSet().intersect(names).size >=
                2
        val seconds =
            meta.optInt("durationSeconds", 0).takeIf { it > 0 }
                ?: AdiXtreamMovieboxPlayback.text(meta, "duration").toIntOrNull()?.takeIf { it > 0 }
        val runtime = seconds?.let { it / 60 }
        val durationAgrees =
            runtime != null &&
                r.runtimeMinutes != null &&
                kotlin.math.abs(runtime - r.runtimeMinutes) <= maxOf(8, r.runtimeMinutes / 10)
        if (
            r.type == 1 &&
                runtime != null &&
                r.runtimeMinutes != null &&
                kotlin.math.abs(runtime - r.runtimeMinutes) > maxOf(25, r.runtimeMinutes / 3)
        )
            return "RUNTIME_MISMATCH"
        val castCount =
            r.castNames.map(::normalize).filter { it.isNotBlank() }.toSet().intersect(names).size
        val exact = subject(meta)?.let { titleMatches(r, it) } == true
        // A localized display title absent from TMDB is not an alias by guesswork.
        // Require three independent channels (cast, plot, runtime), exact type/year,
        // and a margin over known competing works before crossing that boundary.
        if (
            !exact &&
                Regex("(?i)\\[[^]]+\\]|\\btrailer\\b|\\bfull\\s+movie\\b")
                    .containsMatchIn(AdiXtreamMovieboxPlayback.text(meta, "title"))
        )
            return "EDITION_NOT_VERIFIED"
        if (!exact)
            return if (
                castCount >= 3 &&
                    durationAgrees &&
                    shared >= 6 &&
                    score >= 0.4 &&
                    score >= other + 0.2 &&
                    subject(meta)?.let {
                        it.type == r.type && it.year != null && it.year == r.year
                    } == true
            )
                "CROSS_LANGUAGE_VERIFIED"
            else "CROSS_LANGUAGE_UNVERIFIED"
        if (castAgrees) return "CAST_MATCH"
        val plotAgrees =
            (shared >= 8 && score >= 0.5) || (durationAgrees && shared >= 4 && score >= 0.25)
        if (plotAgrees && score >= other + 0.15) return "PLOT_MATCH"
        if (
            primary &&
                r.year != null &&
                subject(meta)?.year == r.year &&
                works != null &&
                works.size == 1 &&
                works.single().id == r.tmdbId
        )
            return "TMDB_UNIQUE_WORK"
        return if (works == null) "TMDB_IDENTITY_UNVERIFIED" else "AMBIGUOUS_TMDB_WORK"
    }

    private fun trusted(r: Request, meta: JSONObject): Boolean =
        identity(r, meta) in
            setOf(
                "EXTERNAL_ID_MATCH",
                "CAST_MATCH",
                "PLOT_MATCH",
                "CROSS_LANGUAGE_VERIFIED",
                "LEGACY_TITLE_YEAR",
            )

    fun matches(r: Request, s: Subject): Boolean =
        valid(r) && s.type == r.type && s.year == r.year && titleMatches(r, s)

    private fun potential(r: Request, s: Subject): Boolean =
        (s.type !in 1..2 || s.type == r.type) &&
            (r.tmdbId != null ||
                r.imdbId != null ||
                r.year == null ||
                s.year == null ||
                s.year == r.year) &&
            (titleMatches(r, s) ||
                ((r.tmdbId != null || r.imdbId != null) &&
                    (r.year == null || s.year == null || kotlin.math.abs(r.year - s.year) <= 1)))

    fun choose(r: Request, candidates: List<Subject>): Result {
        if (!valid(r)) return Result(null, "INSUFFICIENT_TMDB_IDENTITY")
        val matches = candidates.filter { matches(r, it) }.distinctBy { it.id }
        return when (matches.size) {
            0 -> Result(null, "SUBJECT_NOT_FOUND_OR_IDENTITY_MISMATCH")
            1 -> Result(matches.single(), "MATCH")
            else -> Result(null, "AMBIGUOUS_SUBJECT")
        }
    }

    fun episode(raw: String?, id: String, season: Int, ep: Int): Boolean =
        try {
            val root = JSONObject(raw ?: "{}")
            val data = root.optJSONObject("data")
            root.optInt("code", -1) == 0 &&
                data != null &&
                AdiXtreamMovieboxPlayback.text(data, "subjectId").let {
                    it.isEmpty() || it == id
                } &&
                AdiXtreamMovieboxPlayback.objects(data.optJSONArray("seasons"))
                    .filter { it.optInt("se", -1) == season }
                    .singleOrNull()
                    ?.let { ep > 0 && ep <= it.optInt("maxEp", 0) } == true
        } catch (_: Exception) {
            false
        }

    fun episodeStatus(raw: String?, meta: JSONObject, id: String, season: Int, ep: Int): String {
        if (episode(raw, id, season, ep)) return "VERIFIED"
        val data =
            try {
                JSONObject(raw ?: "{}").takeIf { it.optInt("code", -1) == 0 }?.optJSONObject("data")
            } catch (_: Exception) {
                null
            }
        // Explicit authoritative negatives must never be overridden by a fallback.
        if (
            data != null &&
                AdiXtreamMovieboxPlayback.text(data, "subjectId").let {
                    it.isEmpty() || it == id
                } &&
                data.has("seasons")
        )
            return "EPISODE_NOT_AVAILABLE"
        if (AdiXtreamMovieboxPlayback.text(meta, "subjectId") != id) return "SEASON_NOT_VERIFIED"
        val seasons = meta.optJSONArray("catalogSeasons") ?: return "SEASON_NOT_VERIFIED"
        val exact =
            AdiXtreamMovieboxPlayback.objects(seasons)
                .filter { it.optInt("se", -1) == season }
                .singleOrNull() ?: return "EPISODE_NOT_AVAILABLE"
        return if (ep > 0 && ep <= exact.optInt("maxEp", 0)) "VERIFIED" else "EPISODE_NOT_AVAILABLE"
    }

    /**
     * Decode only public catalog fields from Nuxt's inert JSON reference table; never execute JS.
     */
    fun catalogPage(html: String): List<JSONObject> =
        try {
            if (html.length > 2_000_000) emptyList()
            else {
                val script =
                    Jsoup.parse(html).selectFirst("script#__NUXT_DATA__[type=application/json]")
                val table = JSONArray(script?.data() ?: "[]")
                val keys =
                    setOf(
                        "subjectId",
                        "title",
                        "subjectType",
                        "releaseDate",
                        "detailPath",
                        "detailUrl",
                        "dubs",
                        "original",
                        "lanName",
                        "lanCode",
                        "lan",
                        "language",
                        "description",
                        "duration",
                        "durationSeconds",
                        "staffList",
                        "name",
                        "subject",
                        "resource",
                        "seasons",
                        "se",
                        "maxEp",
                        "allEp",
                        "stars",
                        "imdbId",
                        "imdb_id",
                        "tmdbId",
                        "tmdb_id",
                        "externalIds",
                        "external_ids",
                    )
                var decoded = 0
                fun ref(index: Any?, depth: Int): Any? {
                    if (++decoded > 50_000 || depth > 8 || index !is Number) return null
                    val i = index.toInt()
                    if (i < 0 || i >= table.length()) return null
                    return when (val value = table.opt(i)) {
                        is JSONObject ->
                            JSONObject().apply {
                                value.keys().forEach { k ->
                                    if (k in keys) ref(value.opt(k), depth + 1)?.let { put(k, it) }
                                }
                            }
                        is JSONArray ->
                            JSONArray().apply {
                                for (j in 0 until minOf(value.length(), 200)) ref(
                                        value.opt(j),
                                        depth + 1,
                                    )
                                    ?.let { put(it) }
                            }
                        JSONObject.NULL -> null
                        else -> value
                    }
                }
                val owners = mutableMapOf<String, JSONObject>()
                for (i in 0 until minOf(table.length(), 30_000)) {
                    val node = table.optJSONObject(i) ?: continue
                    if (!node.has("subject") || !node.has("resource")) continue
                    val wrapper = ref(i, 0) as? JSONObject ?: continue
                    val owned = wrapper.optJSONObject("subject") ?: continue
                    val id = AdiXtreamMovieboxPlayback.text(owned, "subjectId")
                    if (subject(owned) == null) continue
                    wrapper.optJSONObject("resource")?.optJSONArray("seasons")?.let {
                        owned.put("catalogSeasons", it)
                    }
                    wrapper
                        .optJSONArray("stars")
                        ?.takeIf { it.length() > 0 }
                        ?.let { owned.put("staffList", it) }
                    owners[id] = owned
                }
                (0 until minOf(table.length(), 30_000))
                    .mapNotNull { i ->
                        val node = table.optJSONObject(i) ?: return@mapNotNull null
                        if (!node.has("subjectId") || !node.has("title")) return@mapNotNull null
                        (ref(i, 0) as? JSONObject)
                            ?.takeIf { subject(it) != null }
                            ?.let { owners[AdiXtreamMovieboxPlayback.text(it, "subjectId")] ?: it }
                    }
                    .groupBy { AdiXtreamMovieboxPlayback.text(it, "subjectId") }
                    .values
                    .map { group -> group.maxBy { it.length() + if (it.has("dubs")) 100 else 0 } }
            }
        } catch (_: Exception) {
            emptyList()
        }

    fun catalogSearch(html: String): String? {
        // A search error/sign-in page cannot masquerade as a successful empty API result.
        val items = catalogPage(html)
        if (items.isEmpty()) return null
        return JSONObject()
            .put("code", 0)
            .put("data", JSONObject().put("items", JSONArray(items)))
            .toString()
    }

    /** Public H5 marks originals with a flag plus ISO code rather than a language name. */
    fun playbackMetadata(meta: JSONObject): JSONObject {
        val copy = JSONObject(meta.toString())
        val id = AdiXtreamMovieboxPlayback.text(copy, "subjectId")
        val original =
            AdiXtreamMovieboxPlayback.objects(copy.optJSONArray("dubs")).singleOrNull {
                it.opt("original") == true && AdiXtreamMovieboxPlayback.text(it, "subjectId") == id
            }
        val code =
            original
                ?.let { AdiXtreamMovieboxPlayback.text(it, "lanCode") }
                ?.lowercase(Locale.ROOT)
                ?.substringBefore('-')
                ?.substringBefore('_')
        if (code != null && code.matches(Regex("[a-z]{2,3}"))) copy.put("lanName", code)
        return copy
    }

    suspend fun resolve(
        r: Request,
        search: suspend (String) -> String?,
        detail: suspend (String) -> String?,
        seasons: suspend (String) -> String?,
        searchPage: (suspend (String, Int) -> String?)? = null,
    ): Result {
        if (!valid(r)) return Result(null, "INSUFFICIENT_TMDB_IDENTITY")
        suspend fun safe(read: suspend () -> String?): String? =
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        val candidates = linkedMapOf<String, Subject>()
        val verified = linkedMapOf<String, Pair<Subject, JSONObject>>()
        val rejected = mutableSetOf<String>()
        val attempts = mutableMapOf<String, Int>()
        var successfulSearch = false
        var lastError = "SEARCH_API_FAILED"
        suspend fun verify(
            id: String,
            cached: String? = null,
            prefetched: Boolean = false,
        ): Pair<Subject, JSONObject>? {
            verified[id]?.let {
                return it
            }
            val count = attempts[id] ?: 0
            if (count >= 2) return null
            attempts[id] = count + 1
            val raw =
                (if (prefetched) cached else safe { detail(id) })
                    ?: run {
                        lastError = "SUBJECT_DETAIL_UNAVAILABLE"
                        return null
                    }
            val meta =
                AdiXtreamMovieboxPlayback.metadata(raw, id)
                    ?: run {
                        lastError = "SUBJECT_DETAIL_IDENTITY_MISMATCH"
                        return null
                    }
            val s = subject(meta) ?: return null
            val proof = identity(r, meta)
            val (plotWords, plotOverlap) =
                requestPlot(r, AdiXtreamMovieboxPlayback.text(meta, "description"))
            val cast =
                AdiXtreamMovieboxPlayback.objects(meta.optJSONArray("staffList"))
                    .map { normalize(AdiXtreamMovieboxPlayback.text(it, "name")) }
                    .toSet()
            val seasonEdition =
                r.type == 2 &&
                    (r.season ?: 0) > 1 &&
                    r.year != null &&
                    s.year != null &&
                    s.year >= r.year &&
                    AdiXtreamMovieboxPlayback.objects(meta.optJSONArray("catalogSeasons")).any {
                        it.optInt("se", -1) == r.season
                    } &&
                    r.castNames.map(::normalize).toSet().intersect(cast).size >= 2 &&
                    plotWords >= 8 &&
                    plotOverlap >= 0.5
            val yearOK =
                s.year == r.year && r.year != null ||
                    proof == "EXTERNAL_ID_MATCH" ||
                    seasonEdition ||
                    ((r.year == null || s.year == null) &&
                        proof in setOf("CAST_MATCH", "PLOT_MATCH"))
            val titleOK =
                titleMatches(r, s) || proof in setOf("EXTERNAL_ID_MATCH", "CROSS_LANGUAGE_VERIFIED")
            val sameWork = valid(r) && s.type == r.type && titleOK && yearOK
            if (s.id != id || !sameWork || !trusted(r, meta)) {
                rejected += id
                lastError =
                    when {
                        s.id != id -> "SUBJECT_DETAIL_IDENTITY_MISMATCH"
                        proof == "EXTERNAL_ID_CONFLICT" -> proof
                        s.type != r.type -> "MOVIE_TYPE_MISMATCH"
                        !yearOK -> "YEAR_MISMATCH"
                        !titleOK -> "IDENTITY_REJECTED"
                        else -> proof
                    }
                return null
            }
            return (s to meta).also { verified[id] = it }
        }
        fun original(meta: JSONObject): String? =
            AdiXtreamMovieboxPlayback.objects(meta.optJSONArray("dubs"))
                .filter { it.opt("original") == true }
                .map { AdiXtreamMovieboxPlayback.text(it, "subjectId") }
                .filter { it.matches(Regex("[0-9]+")) }
                .distinct()
                .singleOrNull()
        suspend fun select(): Result? {
            val possible = candidates.values.filter { it.id !in rejected }
            if (possible.isEmpty()) return null
            // At most three same-ID detail lookups concurrently; IO cancellation propagates.
            // Verification state mutations remain sequential; parallel reads are prefetched.
            val needed = possible.filter { it.id !in verified && (attempts[it.id] ?: 0) < 2 }
            val exact = needed.filter { titleMatches(r, it) }
            val pending = (exact.ifEmpty { needed }).take(16)
            val gate = Semaphore(3)
            val prefetched = coroutineScope {
                pending
                    .map { candidate ->
                        async { candidate.id to gate.withPermit { safe { detail(candidate.id) } } }
                    }
                    .awaitAll()
                    .toMap()
            }
            for (candidate in pending) verify(candidate.id, prefetched[candidate.id], true)
            val originals = verified.values.mapNotNull { original(it.second) }.distinct()
            for (id in originals.take(16)) verify(id)
            val remaining = possible.filter { it.id !in rejected }
            val families =
                verified.values
                    .filter { (s, meta) -> original(meta) == s.id }
                    .filter { (_, meta) ->
                        val ids =
                            AdiXtreamMovieboxPlayback.objects(meta.optJSONArray("dubs"))
                                .map { AdiXtreamMovieboxPlayback.text(it, "subjectId") }
                                .toSet()
                        remaining.all { it.id in ids } && verified.keys.all { it in ids }
                    }
            val strong =
                setOf("EXTERNAL_ID_MATCH", "CAST_MATCH", "PLOT_MATCH", "CROSS_LANGUAGE_VERIFIED")
            val chosen: List<Pair<Subject, JSONObject>> =
                when {
                    families.size == 1 -> listOf(families.single())
                    verified.isNotEmpty() &&
                        verified.values.all { identity(r, it.second) in strong } -> {
                        // Distinct editions may be tried only after each independently proves the
                        // selected work. Similar title/year alone never establishes this relation.
                        verified.values.filter { (s, meta) ->
                            original(meta).let { it == null || it == s.id }
                        }
                    }
                    verified.size == 1 && remaining.size == 1 ->
                        listOfNotNull(
                            verified.values.single().takeIf { (s, meta) ->
                                val orig = original(meta)
                                orig == null || orig == s.id
                            }
                        )
                    else -> {
                        lastError = if (verified.isEmpty()) lastError else "AMBIGUOUS_SUBJECT"
                        return null
                    }
                }
            val available =
                chosen
                    .filter { it.first.id !in r.excludedSubjects }
                    .filter { (s, meta) ->
                        if (r.type != 2) true
                        else {
                            val status =
                                episodeStatus(
                                    safe { seasons(s.id) },
                                    meta,
                                    s.id,
                                    r.season!!,
                                    r.episode!!,
                                )
                            if (status != "VERIFIED") lastError = status
                            status == "VERIFIED"
                        }
                    }
                    .take(3)
            if (available.isEmpty()) return null
            val (subject, meta) = available.first()
            return Result(
                subject,
                "MATCH",
                meta,
                available.map { Edition(it.first, it.second) },
                candidates.size,
                verified.size,
            )
        }

        var queryCount = 0
        for (query in queries(r)) {
            if (queryCount >= 12) break
            // A native page advertises pagination explicitly. Public SSR is one page only;
            // never invent a public pagination parameter that the server does not support.
            for (page in 1..3) {
                if (queryCount >= 12) break
                queryCount++
                val raw =
                    safe { if (page == 1) search(query) else searchPage?.invoke(query, page) }
                        ?: break
                val response =
                    try {
                        JSONObject(raw)
                    } catch (_: Exception) {
                        lastError = "SEARCH_API_INVALID"
                        break
                    }
                if (response.optInt("code", -1) != 0) {
                    lastError = "SEARCH_API_REJECTED"
                    break
                }
                successfulSearch = true
                for (candidate in
                    parseSearch(raw)
                        .filter { potential(r, it) }
                        .sortedWith(
                            compareBy<Subject> { !titleMatches(r, it) }.thenBy { it.year != r.year }
                        )) {
                    if (candidates.size < 32 || candidate.id in candidates)
                        candidates[candidate.id] = candidate
                }
                // Finish the advertised bounded pages before a metadata-level decision: later
                // rows may reveal ambiguity. External IDs may safely finish immediately.
                if (!hasMore(raw) || page == 3 || searchPage == null) {
                    select()?.let {
                        return it
                    }
                    break
                }
            }
        }
        // Broad localized discovery must not delay verified exact-title work. After
        // queries finish, inspect remaining bounded candidates even if a later API failed.
        repeat(4) {
            val before = attempts.values.sum()
            select()?.let {
                return it
            }
            if (attempts.values.sum() == before) return@repeat
        }
        if (!successfulSearch)
            return Result(null, lastError, candidates = candidates.size, verified = verified.size)
        if (candidates.isEmpty()) return Result(null, "SUBJECT_NOT_FOUND_OR_IDENTITY_MISMATCH")
        return Result(null, lastError, candidates = candidates.size, verified = verified.size)
    }
}
