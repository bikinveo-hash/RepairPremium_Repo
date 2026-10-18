package com.Adicinemax21

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Playback-only schemas observed in the attached CS3 v36, not a recursive URL scraper. */
internal object Adicinemax21MovieboxPlayback {
    const val WEB_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
    val webDomains =
        listOf(
            "https://h5-api.aoneroom.com",
            "https://123moviesfree.club",
            "https://movie-box.co",
            "https://movieboxonline.net",
            "https://sflix.film",
            "https://netnaija.film",
            "https://movieboxhd.net",
        )

    enum class Format {
        DASH,
        HLS,
        VIDEO,
    }

    enum class AudioCategory(val display: String) {
        ORIGINAL("Asli"),
        INDONESIAN("Indonesia"),
        ENGLISH("Inggris"),
        UNSUPPORTED(""),
    }

    data class Stream(
        val url: String,
        val format: String,
        val id: String,
        val quality: Int?,
        val codec: String,
        val cookie: String?,
        val signHeader: String,
        val audio: String,
        val source: String,
        val category: AudioCategory = audioCategory(audio),
    )

    data class Sample(
        val url: String,
        val code: Int,
        val mime: String,
        val bytes: ByteArray,
        val truncated: Boolean = false,
    )

    data class Validated(val stream: Stream, val format: Format)

    fun text(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "").trim()

    fun objects(a: JSONArray?): List<JSONObject> =
        (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }

    fun http(url: String): Boolean =
        try {
            val u = URI(url)
            u.scheme in listOf("https", "http") && !u.host.isNullOrBlank() && u.userInfo == null
        } catch (_: Exception) {
            false
        }

    fun notice(url: String): Boolean =
        listOf(
                "b164fbfb4347792950bdfbfb563d39d9",
                "1c7de0bd3393702d9191801f15f88f8d",
                "9a0461bc39da389663bf3dbb17091d3f",
                "/other/2026/09/",
                "/notice.mp4",
                "upgrade-notice",
            )
            .any { url.contains(it, true) }

    fun matches(o: JSONObject, subject: String, se: Int, ep: Int): Boolean =
        (text(o, "subjectId").let { it.isEmpty() || it == subject }) &&
            (!o.has("se") || o.optInt("se", -1) == se) &&
            (!o.has("ep") || o.optInt("ep", -1) == ep)

    fun parse(
        raw: String,
        source: String,
        subject: String,
        se: Int,
        ep: Int,
        audio: String = "",
        originalLanguage: String = "",
    ): List<Stream> {
        if (source != "H5") return emptyList()
        val root = JSONObject(raw)
        if (root.has("code") && root.optInt("code", -1) != 0) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        if (!matches(data, subject, se, ep)) return emptyList()
        val result = mutableListOf<Stream>()
        fun add(o: JSONObject, format: String, parent: JSONObject = data) {
            if (!matches(o, subject, se, ep)) return
            val url = text(o, "url")
            if (!http(url)) return
            val quality =
                Regex("\\d{3,4}")
                    .findAll(text(o, "resolutions").ifBlank { text(o, "resolution") })
                    .mapNotNull { it.value.toIntOrNull() }
                    .filter { it in 144..4320 }
                    .maxOrNull()
            val languages = audioLanguages(o)
            // Blank audio is original only for the selected subject, never an unlabelled dub.
            val parentCategory =
                if (audio.isBlank()) AudioCategory.ORIGINAL else audioCategory(audio)
            val categories =
                languages
                    .map { language ->
                        if (
                            parentCategory == AudioCategory.ORIGINAL &&
                                originalLanguage.isNotBlank() &&
                                audioName(language) == audioName(originalLanguage)
                        )
                            AudioCategory.ORIGINAL
                        else audioCategory(language)
                    }
                    .distinct()
            val category =
                when {
                    languages.any { audioCategory(it) == AudioCategory.ORIGINAL } ->
                        AudioCategory.ORIGINAL
                    categories.isEmpty() -> parentCategory
                    else -> categories.singleOrNull() ?: AudioCategory.UNSUPPORTED
                }
            result +=
                Stream(
                    url,
                    text(o, "format").ifBlank { format },
                    text(o, "id").ifBlank { text(parent, "resourceId") },
                    quality,
                    text(o, "codecName"),
                    text(o, "signCookie")
                        .ifBlank { text(parent, "signCookie").ifBlank { text(data, "signCookie") } }
                        .takeIf { it.isNotEmpty() },
                    text(o, "signHeaderKey")
                        .ifBlank { text(parent, "signHeaderKey") }
                        .ifBlank { "X-MB-Token" },
                    text(o, "lanName").ifBlank { audio },
                    source,
                    category,
                )
        }
        // Only H5 movie/series media groups are accepted.
        for ((key, format) in listOf("dash" to "DASH", "hls" to "HLS", "streams" to "")) {
            objects(data.optJSONArray(key)).forEach { add(it, format) }
        }
        return result
    }

    fun metadata(raw: String?, subject: String): JSONObject? =
        try {
            val root = raw?.let { JSONObject(it) }
            val data =
                root
                    ?.takeIf { !it.has("code") || it.optInt("code", -1) == 0 }
                    ?.optJSONObject("data")
            val item = data?.optJSONObject("subject") ?: data
            item?.takeIf {
                text(it, "subjectId").let { id -> id.isEmpty() || id == subject } &&
                    (it.has("subjectId") ||
                        it.has("detailPath") ||
                        it.has("detailUrl") ||
                        it.has("dubs"))
            }
        } catch (_: Exception) {
            null
        }

    fun detailPath(detail: JSONObject, subject: String = text(detail, "subjectId")): String {
        // Prefer the explicit API path over a legacy/redirect detail URL.
        val value =
            text(detail, "detailPath")
                .ifBlank { text(detail, "detailUrl") }
                .ifBlank {
                    // Verified 2026-10-16 from the owner's server-issued Share redirects:
                    // Reacher and House of the Dragon paths/evidence are recorded in tests.
                    // An unavailable title context returns empty H5 resources for these IDs. Do not
                    // infer slugs from titles, replace existing paths, or map another edition.
                    if (text(detail, "subjectId").let { it.isNotBlank() && it != subject }) ""
                    else
                        when (subject) {
                            "3620534210064175096" -> "reacher-e1nw56h5sj4"
                            "4149489342181954296" -> "reacher-indonesian-WjrjjPIHwW4"
                            "6890674365592471456" -> "house-of-the-dragon-indonesian-2vLI2nqm1d8"
                            "5373384118887662624" -> "house-of-the-dragon-uTatZHG9Wo6"
                            "7721864815710718808" -> "house-of-the-dragon-G7LGtmOdqc9"
                            "3135126230249492760" -> "house-of-the-dragon-kkZZ8pvUAJ3"
                            else -> ""
                        }
                }
        return try {
            URI(value).path.orEmpty().trimEnd('/').substringAfterLast('/')
        } catch (_: Exception) {
            value.substringBefore('?').trimEnd('/').substringAfterLast('/')
        }
    }

    private fun cleanAudio(raw: String): String =
        raw.replace(Regex("(?i)\\b(dub(?:bed)?|audio)\\b"), "")
            .replace(Regex("[()]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /** Exact whitelist; missing metadata is not evidence of an original dub. */
    fun audioCategory(raw: String): AudioCategory =
        when (cleanAudio(raw).lowercase(Locale.ROOT)) {
            "original",
            "default",
            "asli" -> AudioCategory.ORIGINAL
            "id",
            "ind",
            "in_id",
            "indonesian",
            "indonesia",
            "bahasa indonesia" -> AudioCategory.INDONESIAN
            "en",
            "eng",
            "english",
            "inggris" -> AudioCategory.ENGLISH
            else -> AudioCategory.UNSUPPORTED
        }

    fun audioLanguages(o: JSONObject): List<String> =
        listOf("lanName", "lan", "language").map { text(o, it) }.filter { it.isNotBlank() }

    fun dubCategory(o: JSONObject): AudioCategory =
        audioLanguages(o).map { audioCategory(it) }.distinct().singleOrNull()
            ?: AudioCategory.UNSUPPORTED

    fun audioName(raw: String): String {
        val clean = cleanAudio(raw)
        return when (clean.lowercase(Locale.ROOT)) {
            "",
            "original",
            "default",
            "asli" -> "Asli"
            "id",
            "ind",
            "in_id",
            "indonesian",
            "indonesia",
            "bahasa indonesia" -> "Indonesia"
            "en",
            "eng",
            "english",
            "inggris" -> "Inggris"
            "hi",
            "hin",
            "hindi" -> "Hindi"
            "es",
            "spa",
            "spanish",
            "español" -> "Spanyol"
            "esla",
            "es-la",
            "spanish latino" -> "Spanyol (Latino)"
            "ptbr",
            "pt-br",
            "portuguese brazil" -> "Portugis (Brasil)"
            "pt",
            "por",
            "portuguese",
            "português" -> "Portugis"
            "ko",
            "kor",
            "korean" -> "Korea"
            "ja",
            "jpn",
            "japanese" -> "Jepang"
            "zh",
            "zho",
            "chinese",
            "mandarin" -> "Mandarin"
            "ta",
            "tam",
            "tamil" -> "Tamil"
            "te",
            "tel",
            "telugu" -> "Telugu"
            "ms",
            "malay" -> "Melayu"
            "ar",
            "arabic" -> "Arab"
            else -> clean
        }
    }

    fun audioOrder(audio: String): Int =
        when (audioName(audio)) {
            "Asli" -> 0
            "Indonesia" -> 1
            "Inggris" -> 2
            else -> 3
        }

    fun codecName(raw: String): String =
        when (raw.lowercase(Locale.ROOT)) {
            "h264",
            "h.264",
            "avc",
            "avc1" -> "H.264"
            "hevc",
            "h265",
            "h.265",
            "hev1",
            "hvc1" -> "H.265"
            else -> raw.trim().uppercase(Locale.ROOT)
        }

    fun codecOrder(raw: String): Int =
        when (codecName(raw)) {
            "H.264" -> 0
            "H.265" -> 2
            else -> 1
        }

    fun label(s: Stream, format: Format): String =
        listOf(
                "MovieBox",
                s.category.display.ifBlank { audioName(s.audio) },
                if (format == Format.VIDEO) "Video" else format.name,
                codecName(s.codec),
            )
            .filter { it.isNotBlank() }
            .joinToString(" • ")

    /** Validate each signed request separately. Never modify the actual playback URL. */
    fun requestKey(s: Stream): String = s.url.substringBefore('#') + identity(s)

    private fun identity(s: Stream): String = "|${s.category}|${s.quality}|${codecName(s.codec)}"

    private val authQueryKeys =
        setOf(
            "policy",
            "signature",
            "key-pair-id",
            "expires",
            "cloudfront-policy",
            "cloudfront-signature",
            "cloudfront-key-pair-id",
            "x-amz-algorithm",
            "x-amz-credential",
            "x-amz-date",
            "x-amz-expires",
            "x-amz-signedheaders",
            "x-amz-signature",
            "x-amz-security-token",
        )

    /**
     * Deduplicate validated media only; preserve content selectors and unknown token parameters.
     */
    fun key(s: Stream): String =
        try {
            val uri = URI(s.url)
            val query =
                uri.rawQuery
                    .orEmpty()
                    .split('&')
                    .filter { entry ->
                        entry.isNotBlank() &&
                            URLDecoder.decode(entry.substringBefore('='), "UTF-8")
                                .lowercase(Locale.ROOT) !in authQueryKeys
                    }
                    .joinToString("&")
            "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}" +
                (if (query.isEmpty()) "" else "?$query") +
                identity(s)
        } catch (_: Exception) {
            requestKey(s)
        }

    fun format(s: Stream, sample: Sample): Format? {
        val body = sample.bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (sample.code !in 200..299 || sample.bytes.isEmpty() || notice(sample.url)) return null
        if (body.startsWith("#EXTM3U")) return Format.HLS
        if (Regex("<(?:[A-Za-z0-9_]+:)?MPD(?:\\s|>)").containsMatchIn(body)) return Format.DASH
        if (body.startsWith("<") || body.startsWith("{") || body.startsWith("[")) return null
        // Content signatures, rather than URL suffix/HTTP 200 alone.
        val b = sample.bytes
        val iso =
            b.size >= 8 &&
                String(b, 4, 4, Charsets.US_ASCII) in
                    listOf("ftyp", "styp", "moof", "sidx", "moov", "mdat")
        val ts = b.size > 188 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte()
        val mkv = b.size > 4 && b.take(4) == listOf(0x1a, 0x45, 0xdf, 0xa3).map { it.toByte() }
        return if (iso || ts || mkv) Format.VIDEO else null
    }

    fun scopeHeaders(
        headers: Map<String, String>,
        scope: String,
        target: String,
    ): Map<String, String> {
        val permitted =
            try {
                val from = URI(scope)
                val to = URI(target)
                val dir = from.rawPath.substringBeforeLast('/', "") + "/"
                from.scheme == to.scheme &&
                    from.host.equals(to.host, true) &&
                    from.port == to.port &&
                    to.normalize().rawPath.startsWith(dir)
            } catch (_: Exception) {
                false
            }
        return if (permitted) headers
        else
            headers.filterKeys {
                it.equals("User-Agent", true) ||
                    it.equals("Accept", true) ||
                    it.equals("Referer", true) ||
                    it.equals("Origin", true) ||
                    it.equals("Range", true)
            }
    }

    fun mediaHeaders(s: Stream, origin: String, referer: String, ua: String): Map<String, String> {
        val h = mutableMapOf("User-Agent" to ua, "Accept" to "*/*", "Referer" to referer)
        if (s.source == "H5") h["Origin"] = origin
        s.cookie
            ?.takeIf { !it.contains('\r') && !it.contains('\n') }
            ?.let {
                h["Cookie"] = it
                // signHeaderKey describes a media token header, never permission to override
                // auth/host.
                if (
                    s.source == "H5" &&
                        s.signHeader.matches(Regex("(?i)X-[A-Za-z0-9-]+")) &&
                        !s.signHeader.startsWith("X-Forwarded", true)
                )
                    h[s.signHeader] = it
            }
        return h
    }

    fun captionEntries(raw: String): List<Pair<String, String>> {
        val root = JSONObject(raw)
        if (root.has("code") && root.optInt("code", -1) != 0) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        return objects(data.optJSONArray("extCaptions")).mapNotNull {
            val url = text(it, "url")
            if (!http(url)) null
            else
                text(it, "lanName").ifBlank {
                    text(it, "language").ifBlank { text(it, "lan").ifBlank { "Unknown" } }
                } to url
        }
    }

    /** Default TLS verification, no API cookie jar or API interceptors on media requests. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun sample(
        url: String,
        headers: Map<String, String>,
        scope: String,
        manifest: Boolean = false,
    ): Sample? = withContext(Dispatchers.IO) { sampleIo(url, headers, scope, manifest) }

    private suspend fun sampleIo(
        url: String,
        headers: Map<String, String>,
        scope: String,
        manifest: Boolean,
    ): Sample? {
        var target = url
        repeat(4) {
            if (!http(target) || notice(target)) return null
            val h = scopeHeaders(headers, scope, target).toMutableMap()
            if (!manifest) h["Range"] = "bytes=0-4095"
            val response =
                suspendCancellableCoroutine<Response?> { cont ->
                    val request =
                        Request.Builder()
                            .url(target)
                            .apply { h.forEach { (k, v) -> header(k, v) } }
                            .build()
                    val call = client.newCall(request)
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onResponse(call: Call, response: Response) {
                                cont.resume(response) { _, value, _ -> value.close() }
                            }
                        }
                    )
                } ?: return null
            response.use { r ->
                if (r.code in listOf(301, 302, 303, 307, 308)) {
                    val next = r.header("Location") ?: return null
                    target = resolve(target, next) ?: return null
                    if (URI(url).scheme == "https" && URI(target).scheme != "https") return null
                } else {
                    val body = r.body
                    val limit = if (manifest) 262144 else 4096
                    // Never consume an entire direct video, even when Range is ignored.
                    val input = body.byteStream()
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (out.size() <= limit) {
                        val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    return Sample(
                        target,
                        r.code,
                        r.header("Content-Type").orEmpty(),
                        out.toByteArray().take(limit).toByteArray(),
                        out.size() > limit,
                    )
                }
            }
        }
        return null
    }

    fun resolve(base: String, relative: String): String? =
        try {
            URI(base).resolve(relative).toString().takeIf { http(it) }
        } catch (_: Exception) {
            null
        }

    /**
     * Injectable transport keeps HTTP/device tests distinct from deterministic regression fixtures.
     */
    suspend fun validate(
        s: Stream,
        headers: Map<String, String>,
        fetch: suspend (String, Map<String, String>, Boolean) -> Sample? = { u, h, m ->
            sample(u, h, s.url, m)
        },
    ): Validated? {
        return validateObserved(s, headers, {}, fetch)
    }

    /** Same parsers and segment/security checks; a complete manifest sample needs no second GET. */
    suspend fun validateObserved(
        s: Stream,
        headers: Map<String, String>,
        report: (String) -> Unit,
        fetch: suspend (String, Map<String, String>, Boolean) -> Sample?,
    ): Validated? {
        val first =
            fetch(s.url, headers, false)
                ?: run {
                    report("MEDIA_UNAVAILABLE")
                    return null
                }
        if (first.code !in 200..299) {
            report(
                when (first.code) {
                    429 -> "MEDIA_HTTP_429"
                    426 -> "MEDIA_HTTP_426"
                    401,
                    403 -> "MEDIA_AUTH_FAILED"
                    else -> "MEDIA_HTTP_ERROR"
                }
            )
            return null
        }
        val type =
            format(s, first)
                ?: run {
                    report("MEDIA_CONTENT_INVALID")
                    return null
                }
        if (type == Format.VIDEO) {
            report("MEDIA_VALIDATED")
            return Validated(s, type)
        }
        val manifest = if (!first.truncated) first else fetch(s.url, headers, true)
        if (manifest == null || manifest.truncated || format(s, manifest) != type) {
            report("MEDIA_MANIFEST_INVALID")
            return null
        }
        var children = 0
        val childFetch: suspend (String, Map<String, String>, Boolean) -> Sample? = { u, h, m ->
            children++
            fetch(u, h, m)
        }
        val ok =
            if (type == Format.HLS) hls(manifest, headers, childFetch, 0)
            else dash(manifest, headers, childFetch)
        report(
            if (ok) "MEDIA_VALIDATED"
            else if (children > 0) "MEDIA_SEGMENT_FAILED" else "MEDIA_MANIFEST_INVALID"
        )
        return if (ok) Validated(s, type) else null
    }

    private suspend fun binary(
        url: String,
        headers: Map<String, String>,
        fetch: suspend (String, Map<String, String>, Boolean) -> Sample?,
    ): Boolean {
        val r = fetch(url, headers, false) ?: return false
        return format(Stream(url, "", "", null, "", null, "", "", ""), r) == Format.VIDEO ||
            // AAC/MP3 segment signatures (audio tracks).
            (r.code in 200..299 &&
                r.bytes.size > 2 &&
                ((r.bytes[0].toInt() and 255 == 255 && r.bytes[1].toInt() and 224 == 224) ||
                    r.bytes.take(3).toByteArray().toString(Charsets.US_ASCII) == "ID3"))
    }

    private suspend fun hls(
        r: Sample,
        headers: Map<String, String>,
        fetch: suspend (String, Map<String, String>, Boolean) -> Sample?,
        depth: Int,
    ): Boolean {
        if (depth > 2 || r.truncated || r.code !in 200..299) return false
        val lines = r.bytes.toString(Charsets.UTF_8).trimStart('\uFEFF').lines().map { it.trim() }
        if (lines.firstOrNull() != "#EXTM3U") return false
        if (
            lines.any {
                it.startsWith("#EXT-X-KEY:") &&
                    !it.contains("METHOD=NONE") &&
                    !it.contains("METHOD=AES-128")
            }
        )
            return false
        val master = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        if (master >= 0) {
            val relative =
                lines.drop(master + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                    ?: return false
            val url = resolve(r.url, relative) ?: return false
            val child = fetch(url, headers, true) ?: return false
            if (!hls(child, headers, fetch, depth + 1)) return false
            val audio =
                lines.firstOrNull {
                    it.startsWith("#EXT-X-MEDIA:") &&
                        it.contains("TYPE=AUDIO") &&
                        it.contains("DEFAULT=YES")
                }
            val audioUri = audio?.let { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            return audioUri == null ||
                resolve(r.url, audioUri)?.let {
                    fetch(it, headers, true)?.let { a -> hls(a, headers, fetch, depth + 1) }
                } == true
        }
        for (line in
            lines.filter { it.startsWith("#EXT-X-MAP:") || it.startsWith("#EXT-X-KEY:") }) {
            val ref = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: continue
            val url = resolve(r.url, ref) ?: return false
            if (line.startsWith("#EXT-X-KEY:")) {
                val key = fetch(url, headers, false) ?: return false
                if (key.code !in 200..299 || key.bytes.size != 16) return false
            } else if (!binary(url, headers, fetch)) return false
        }
        val segment = lines.firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return false
        val url = resolve(r.url, segment) ?: return false
        // AES-128 media is encrypted, so sniff only the key and non-HTML segment response.
        if (lines.any { it.startsWith("#EXT-X-KEY:") && it.contains("METHOD=AES-128") }) {
            val media = fetch(url, headers, false) ?: return false
            return media.code in 200..299 &&
                media.bytes.size >= 16 &&
                !media.bytes.toString(Charsets.UTF_8).trimStart().let {
                    it.startsWith('<') || it.startsWith('{')
                }
        }
        return binary(url, headers, fetch)
    }

    private suspend fun dash(
        r: Sample,
        headers: Map<String, String>,
        fetch: suspend (String, Map<String, String>, Boolean) -> Sample?,
    ): Boolean {
        val doc = Jsoup.parse(r.bytes.toString(Charsets.UTF_8), "", Parser.xmlParser())
        val mpd = doc.selectFirst("MPD") ?: return false
        // DRM requires a licensed player path; this resolver does not attempt a bypass.
        if (mpd.select("ContentProtection").isNotEmpty()) return false
        val reps = mpd.select("Representation")
        if (reps.isEmpty()) return false
        val selected =
            reps
                .groupBy {
                    it.attr("mimeType")
                        .ifBlank { it.parent()?.attr("mimeType").orEmpty() }
                        .substringBefore('/')
                        .ifBlank { it.parent()?.attr("contentType").orEmpty() }
                }
                .values
                .mapNotNull { group ->
                    group.firstOrNull { it.attr("codecs").startsWith("avc", true) }
                        ?: group.firstOrNull()
                }
        for (rep in selected) {
            val ancestors =
                generateSequence(rep) { it.parent() }.takeWhile { it != doc }.toList().reversed()
            var base = r.url
            ancestors.forEach { node ->
                node
                    .children()
                    .firstOrNull { it.tagName().equals("BaseURL", true) }
                    ?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { base = resolve(base, it) ?: return false }
            }
            val templateNodes =
                ancestors.mapNotNull { node ->
                    node.children().firstOrNull { it.tagName().equals("SegmentTemplate", true) }
                }
            fun attr(key: String) =
                templateNodes
                    .asReversed()
                    .firstNotNullOfOrNull { it.attr(key).takeIf { v -> v.isNotEmpty() } }
                    .orEmpty()
            val template = templateNodes.lastOrNull()
            if (template != null) {
                val firstTime =
                    template.selectFirst("SegmentTimeline S")?.attr("t").orEmpty().ifBlank { "0" }
                fun expand(value: String): String =
                    Regex("\\$(RepresentationID|Bandwidth|Number|Time)(%0(\\d+)d)?\\$")
                        .replace(value) { m ->
                            val v =
                                when (m.groupValues[1]) {
                                    "RepresentationID" -> rep.id()
                                    "Bandwidth" -> rep.attr("bandwidth")
                                    "Number" -> attr("startNumber").ifBlank { "1" }
                                    else -> firstTime
                                }
                            v.padStart(m.groupValues[3].toIntOrNull()?.coerceAtMost(16) ?: 0, '0')
                        }
                        .replace("$$", "$")
                for (ref in
                    listOf(attr("initialization"), attr("media")).filter { it.isNotEmpty() }) {
                    val expanded = expand(ref)
                    if (
                        expanded.contains('$') ||
                            !binary(resolve(base, expanded) ?: return false, headers, fetch)
                    )
                        return false
                }
                if (attr("media").isBlank()) return false
            } else {
                val list =
                    ancestors.asReversed().firstNotNullOfOrNull {
                        it.children().firstOrNull { n -> n.tagName().equals("SegmentList", true) }
                    }
                if (list != null) {
                    val refs =
                        listOfNotNull(
                                list.selectFirst("Initialization")?.attr("sourceURL"),
                                list.selectFirst("SegmentURL")?.attr("media"),
                            )
                            .filter { it.isNotBlank() }
                    if (refs.isEmpty()) return false
                    for (ref in refs) if (
                        !binary(resolve(base, ref) ?: return false, headers, fetch)
                    )
                        return false
                } else if (base == r.url || !binary(base, headers, fetch)) return false
            }
        }
        return selected.isNotEmpty()
    }
}
