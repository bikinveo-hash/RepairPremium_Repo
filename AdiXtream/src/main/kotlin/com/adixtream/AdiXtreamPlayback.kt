package com.adixtream

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaCodecList
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Playback-only schemas observed in the attached CS3 v36, not a recursive URL scraper. */
internal object AdiXtreamMovieboxPlayback {
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

/** Bounded sanitized events, accessible on the plugin settings gear without adb/root. */
internal object AdiXtreamMovieboxDiagnostics {
    private val rows = java.util.ArrayDeque<String>()
    private val keys =
        setOf(
            "subjectId",
            "tmdbId",
            "se",
            "ep",
            "type",
            "quality",
            "codec",
            "audio",
            "host",
            "status",
            "mime",
            "redirect",
            "attempt",
            "elapsedMs",
            "ranged",
            "retryAfterMs",
            "protocol",
            "candidates",
            "validated",
            "verified",
            "hasCookie",
            "hasSignHeader",
        )

    @Synchronized
    fun clear() {
        rows.clear()
    }

    @Synchronized
    fun record(stage: String, reason: String, fields: Map<String, Any?> = emptyMap()) {
        val row = JSONObject().put("stage", stage).put("reason", reason)
        for ((key, value) in fields) if (key in keys && value != null) {
            // Values originate in typed diagnostics, never request/response headers or URLs.
            row.put(key, if (value is String) value.filter { it >= ' ' }.take(100) else value)
        }
        val line = row.toString()
        if (rows.size >= 160) rows.removeFirst()
        rows.addLast(line)
        Log.d("AdiXtreamMoviebox", "[$stage] $line")
    }

    @Synchronized
    fun snapshot(): String = "AdiXtream Moviebox playback diagnostics\n" + rows.joinToString("\n")

    fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Diagnostik Moviebox — AdiXtream")
            .setMessage(
                "Setelah mencoba film, salin log ini. Log tidak memuat URL media, cookie, atau token.\n\n" +
                    snapshot()
            )
            .setPositiveButton("Salin log") { _, _ ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("Moviebox diagnostics", snapshot()))
            }
            .setNeutralButton("Bersihkan") { _, _ -> clear() }
            .setNegativeButton("Tutup", null)
            .show()
    }
}

internal data class MovieVersionData(val url: String, val versionName: String)

internal class OppaPlayback {
    private val mainUrl = "https://oppadrama.io"
    private val DEBUG = false
    private val USE_PROXY = true
    private val FL_DIAG = false
    private val concurrencySemaphore = Semaphore(3)
    private val headersMap = mapOf("Cookie" to "user_is_human=true", "User-Agent" to USER_AGENT)

    private fun fixUrl(url: String): String = java.net.URL(java.net.URL(mainUrl), url).toString()

    private fun logDebug(message: String) = Unit

    private fun getQualityFromString(quality: String?): Int {
        return when {
            quality.isNullOrBlank() -> Qualities.Unknown.value
            quality.contains("2160") || quality.contains("4k", ignoreCase = true) ->
                Qualities.P2160.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()

        val versionList =
            tryParseJson<List<MovieVersionData>>(data) ?: listOf(MovieVersionData(data, ""))

        // Deduplikasi Thread-Safe
        val visitedEmbedUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        val safeSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (visitedSubtitleUrls.add(sub.url)) {
                logDebug("Subtitle emitted: [${sub.lang}] -> ${sub.url}")
                subtitleCallback(sub)
            }
        }

        // Post-Extractor Deduplication
        val safeLinkCallback: (ExtractorLink) -> Unit = { link ->
            if (visitedStreamUrls.add(link.url)) {
                logDebug("Source emitted: [${link.source}] ${link.name} -> ${link.url}")
                callback(link)
            } else {
                logDebug("Stream URL skipped (Duplicate Stream Link): ${link.url}")
            }
        }

        coroutineScope {
            versionList
                .map { version ->
                    async {
                        concurrencySemaphore.withPermit {
                            val pageUrl = version.url
                            val labelSuffix =
                                if (version.versionName.isNotBlank()) " (${version.versionName})"
                                else ""
                            logDebug("Processing Mode/Version: $pageUrl | Label: $labelSuffix")

                            sourceCatching {
                                    val res = app.get(pageUrl, headers = headersMap)
                                    val doc = res.document

                                    val mirrorOptions =
                                        doc.select(
                                            "select.mirror option, select[name=mirror] option"
                                        )
                                    logDebug(
                                        "Found ${mirrorOptions.size} mirror option(s) on $pageUrl"
                                    )

                                    for (option in mirrorOptions) {
                                        val base64Value = option.attr("value").trim()
                                        var serverName = option.text().trim()

                                        if (
                                            base64Value.isBlank() ||
                                                serverName.contains(
                                                    "Pilih Server",
                                                    ignoreCase = true,
                                                )
                                        ) {
                                            continue
                                        }

                                        if (labelSuffix.isNotBlank()) {
                                            serverName += labelSuffix
                                        }

                                        sourceCatching {
                                                val decodedHtml = base64Decode(base64Value)
                                                val iframeDoc = Jsoup.parse(decodedHtml)
                                                val iframeElements =
                                                    iframeDoc.select("iframe[src], IFRAME[SRC]")

                                                for (iframe in iframeElements) {
                                                    val rawSrc =
                                                        iframe.attr("src").ifEmpty {
                                                            iframe.attr("SRC")
                                                        }
                                                    if (rawSrc.isNotBlank()) {
                                                        val fixedUrl = fixUrl(rawSrc)

                                                        if (!visitedEmbedUrls.add(fixedUrl)) {
                                                            logDebug(
                                                                "Embed URL skipped (Pre-Extractor Duplicate): $fixedUrl"
                                                            )
                                                            continue
                                                        }

                                                        val countBefore = visitedStreamUrls.size

                                                        // 1. Penanganan TurboVIP
                                                        if (
                                                            fixedUrl.contains("emturbovid.com") ||
                                                                fixedUrl.contains("turboviplay.com")
                                                        ) {
                                                            logDebug(
                                                                "Processing TurboVIP: $fixedUrl"
                                                            )
                                                            extractTurboVipWithFallback(
                                                                fixedUrl,
                                                                pageUrl,
                                                                serverName,
                                                                safeSubtitleCallback,
                                                                safeLinkCallback,
                                                            )
                                                        }
                                                        // 2. Penanganan Hydrax
                                                        else if (
                                                            fixedUrl.contains("abyss") ||
                                                                fixedUrl.contains("hydrax")
                                                        ) {
                                                            val mediaId = extractMediaId(fixedUrl)
                                                            LocalProxy.obs(
                                                                "HYDRAX",
                                                                "in=$fixedUrl id=$mediaId",
                                                            )

                                                            val hxOk =
                                                                try {
                                                                    extractHydrax(
                                                                        fixedUrl,
                                                                        pageUrl,
                                                                        serverName,
                                                                        safeLinkCallback,
                                                                    )
                                                                } catch (e: Exception) {
                                                                    if (
                                                                        e
                                                                            is
                                                                            kotlinx.coroutines.CancellationException
                                                                    )
                                                                        throw e
                                                                    LocalProxy.obs(
                                                                        "HX-ERROR",
                                                                        "${e.javaClass.simpleName}: ${e.message}",
                                                                    )
                                                                    false
                                                                }

                                                            if (
                                                                !hxOk &&
                                                                    visitedStreamUrls.size ==
                                                                        countBefore
                                                            ) {
                                                                LocalProxy.obs(
                                                                    "HYDRAX-GAGAL",
                                                                    "Ekstraksi HydraX tidak menghasilkan stream",
                                                                )
                                                            }
                                                        }
                                                        // 3. Penanganan FileLions / Minochinos
                                                        else if (
                                                            fixedUrl.contains("minochinos.com") ||
                                                                fixedUrl.contains("vidhide") ||
                                                                fixedUrl.contains("filelions")
                                                        ) {
                                                            val flOk =
                                                                try {
                                                                    extractFileLions(
                                                                        fixedUrl,
                                                                        pageUrl,
                                                                        serverName,
                                                                        safeLinkCallback,
                                                                    )
                                                                } catch (e: Exception) {
                                                                    if (
                                                                        e
                                                                            is
                                                                            kotlinx.coroutines.CancellationException
                                                                    )
                                                                        throw e
                                                                    LocalProxy.obs(
                                                                        "FL-ERROR",
                                                                        "${e.javaClass.simpleName}: ${e.message}",
                                                                    )
                                                                    false
                                                                }

                                                            if (
                                                                !flOk &&
                                                                    visitedStreamUrls.size ==
                                                                        countBefore
                                                            ) {
                                                                val mediaId =
                                                                    extractMediaId(fixedUrl)
                                                                LocalProxy.obs(
                                                                    "FL-FALLBACK",
                                                                    "in=$fixedUrl id=$mediaId",
                                                                )
                                                                if (!mediaId.isNullOrBlank()) {
                                                                    loadExtractor(
                                                                        "https://vidhidepro.com/v/$mediaId",
                                                                        referer = pageUrl,
                                                                        safeSubtitleCallback,
                                                                        safeLinkCallback,
                                                                    )
                                                                    if (
                                                                        visitedStreamUrls.size ==
                                                                            countBefore
                                                                    ) {
                                                                        loadExtractor(
                                                                            "https://vidhidepro.com/d/$mediaId",
                                                                            referer = pageUrl,
                                                                            safeSubtitleCallback,
                                                                            safeLinkCallback,
                                                                        )
                                                                    }
                                                                }
                                                                if (
                                                                    visitedStreamUrls.size ==
                                                                        countBefore
                                                                ) {
                                                                    loadExtractor(
                                                                        fixedUrl,
                                                                        referer = pageUrl,
                                                                        safeSubtitleCallback,
                                                                        safeLinkCallback,
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            // General Fallback
                                                            loadExtractor(
                                                                fixedUrl,
                                                                referer = pageUrl,
                                                                safeSubtitleCallback,
                                                                safeLinkCallback,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            .onFailure { err ->
                                                logDebug(
                                                    "Failed to decode Base64 mirror: ${err.message}"
                                                )
                                            }
                                    }

                                    // Secondary Fallback: Iframe Default di DOM (#pembed)
                                    if (visitedStreamUrls.isEmpty()) {
                                        val defaultIframes =
                                            doc.select(
                                                ".player-embed iframe, #pembed iframe, .mvelement iframe"
                                            )
                                        for (iframe in defaultIframes) {
                                            val src =
                                                iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                            if (src.isNotBlank()) {
                                                val fixedUrl = fixUrl(src)
                                                if (visitedEmbedUrls.add(fixedUrl)) {
                                                    logDebug(
                                                        "Attempting loadExtractor for DOM Iframe: $fixedUrl"
                                                    )
                                                    if (
                                                        fixedUrl.contains("emturbovid.com") ||
                                                            fixedUrl.contains("turboviplay.com")
                                                    ) {
                                                        extractTurboVipWithFallback(
                                                            fixedUrl,
                                                            pageUrl,
                                                            "TurboVIP",
                                                            safeSubtitleCallback,
                                                            safeLinkCallback,
                                                        )
                                                    } else if (
                                                        fixedUrl.contains("abyss") ||
                                                            fixedUrl.contains("hydrax")
                                                    ) {
                                                        extractHydrax(
                                                            fixedUrl,
                                                            pageUrl,
                                                            "Hydrax",
                                                            safeLinkCallback,
                                                        )
                                                    } else if (
                                                        fixedUrl.contains("minochinos.com") ||
                                                            fixedUrl.contains("vidhide") ||
                                                            fixedUrl.contains("filelions")
                                                    ) {
                                                        extractFileLions(
                                                            fixedUrl,
                                                            pageUrl,
                                                            "FileLions",
                                                            safeLinkCallback,
                                                        )
                                                    } else {
                                                        loadExtractor(
                                                            fixedUrl,
                                                            referer = pageUrl,
                                                            safeSubtitleCallback,
                                                            safeLinkCallback,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                .onFailure { err ->
                                    logDebug("Failed to process page $pageUrl: ${err.message}")
                                }
                        }
                    }
                }
                .awaitAll()
        }

        logDebug(
            "loadLinks -> Extraction completed. Total unique stream URLs emitted: ${visitedStreamUrls.size}"
        )
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return visitedStreamUrls.isNotEmpty()
    }

    // Ekstraksi TurboVIP
    private suspend fun extractTurboVipWithFallback(
        url: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return sourceCatching {
                val doc = app.get(url, referer = referer, headers = headersMap).document

                var m3u8Url = doc.selectFirst("#video_player[data-hash]")?.attr("data-hash")?.trim()

                if (!m3u8Url.isNullOrBlank()) {
                    logDebug("[TurboVIP Level 1 Success] Found M3U8 via data-hash: $m3u8Url")
                } else {
                    logDebug("[TurboVIP Level 1 Failed] Trying Level 2 JS Unpacker")
                    val html = doc.html()
                    val unpacked = getAndUnpack(html)
                    m3u8Url =
                        Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
                            .find(unpacked)
                            ?.groupValues
                            ?.get(1)
                            ?: Regex("""file:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                }

                if (!m3u8Url.isNullOrBlank()) {
                    val fixedM3u8 = fixUrl(m3u8Url)

                    val servedUrl =
                        if (USE_PROXY) {
                            LocalProxy.proxyUrl(fixedM3u8) ?: return@sourceCatching false
                        } else {
                            fixedM3u8
                        }
                    val viaProxy = servedUrl != fixedM3u8
                    logDebug("[TurboVIP] serve=$servedUrl viaProxy=$viaProxy")

                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = if (viaProxy) "$serverName [proxy]" else serverName,
                            url = servedUrl,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            if (viaProxy) {
                                this.referer = ""
                            } else {
                                this.referer = "https://turboviplay.com/"
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        }
                    )
                    true
                } else {
                    logDebug("[TurboVIP Level 2 Failed] Trying Level 4 loadExtractor built-in")
                    val altUrl = if (url.contains("/t/")) url.replace("/t/", "/v/") else url
                    loadExtractor(altUrl, referer = url, subtitleCallback, callback)
                }
            }
            .getOrElse { false }
    }

    // ======================================================================
    // Dukungan AV1 perangkat. Dipakai agar track av1 hanya ditawarkan bila
    // perangkat benar-benar punya decodernya (bundle player juga menyaring
    // av1 berdasarkan kemampuan browser).
    // ======================================================================
    private val deviceSupportsAv1: Boolean by lazy {
        runCatching {
                (0 until MediaCodecList.getCodecCount())
                    .map { MediaCodecList.getCodecInfoAt(it) }
                    .any { info ->
                        !info.isEncoder && info.supportedTypes.any { it.equals("video/av01", true) }
                    }
            }
            .getOrDefault(false)
    }

    /** Satu entri `sources` hasil dekripsi config Hydrax. */
    private data class HydraxSource(
        val label: String,
        val codec: String,
        val resId: String?,
        val size: Long?,
        val sub: String?,
        val url: String?,
        val path: String?,
        val partSize: Long,
    )

    /**
     * MD5 dengan seed "satu byte per digit".
     *
     * Ini meniru perilaku pustaka md5 di bundle player: expandKey(size) dipanggil dengan Number,
     * dan modul md5 hanya melakukan toString() tanpa stringToBytes, sehingga bytesToWords membaca
     * tiap karakter lalu meng-koersinya jadi angka ('7' menjadi 7, bukan 0x37).
     */
    private fun md5HexOfDigits(size: Long): String {
        val digits = size.toString()
        val seed = ByteArray(digits.length) { i -> (digits[i] - '0').toByte() }
        return MessageDigest.getInstance("MD5").digest(seed).joinToString("") { "%02x".format(it) }
    }

    /** AES/CTR/NoPadding. CTR bersifat simetris; dipakai untuk enkripsi token. */
    private fun aesCtr(keyHex: String, data: ByteArray): ByteArray {
        val keyBytes = keyHex.toByteArray(Charsets.UTF_8) // 32 byte -> AES-256
        val ivBytes = keyBytes.copyOfRange(0, 16) // counter = 16 byte pertama
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return cipher.doFinal(data)
    }

    /** base64 alfabet standar, tanpa padding dan tanpa newline. */
    private fun b64NoPad(data: ByteArray): String =
        android.util.Base64.encodeToString(
            data,
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )

    /**
     * Bangun URL jalur mp4_native Hydrax.
     *
     * plain = "/mp4/{md5_id}/{res_id}/{size}?v={slug}" keyHex = md5(seed digit dari size) dalam
     * hex, 32 karakter ct = AES/CTR encrypt(plain), kunci = keyHex sebagai ASCII token =
     * base64(base64(ct) tanpa '=') tanpa '=' URL = https://{host}/sora/{size}/{token}
     */
    private fun buildSoraUrl(
        md5Id: String,
        resId: String,
        size: Long,
        slug: String,
        host: String,
        type: String = "mp4",
    ): String {
        val plain = "/$type/$md5Id/$resId/$size?v=$slug"
        val ct = aesCtr(md5HexOfDigits(size), plain.toByteArray(Charsets.UTF_8))
        val token = b64NoPad(b64NoPad(ct).toByteArray(Charsets.US_ASCII))
        return "https://$host/sora/$size/$token"
    }

    /**
     * Urutan preferensi kandidat dalam satu resolusi yang sama.
     * 1. Buang av1 bila perangkat tidak punya decodernya.
     * 2. Dahulukan h264. av1 hanya dipakai bila tidak ada h264 untuk resolusi itu.
     * 3. Dalam codec yang sama, berkas terbesar dipilih (bitrate lebih tinggi).
     *
     * Mengembalikan daftar terurut, bukan satu nilai, supaya pemanggil bisa mencoba kandidat
     * berikutnya kalau pembangunan URL gagal.
     */
    private fun hydraxPreference(group: List<HydraxSource>): List<HydraxSource> {
        val playable = group.filter { !it.codec.equals("av1", true) || deviceSupportsAv1 }
        return playable.sortedWith(
            compareBy<HydraxSource> { if (it.codec.equals("av1", true)) 1 else 0 }
                .thenByDescending { it.size ?: 0L }
        )
    }

    /**
     * Extractor Hydrax.
     *
     * HX-1..HX-4 (ambil HTML, blob base64, dekripsi config AES-CTR) tidak berubah. HX-5 mengurai
     * sources, HX-6 memilih satu kandidat per resolusi lalu membangun URL dengan dua jalur:
     * 1. /sora/ bila source punya size + sub + domain yang cocok -> file utuh
     * 2. url + path (perilaku lama) sebagai cadangan
     */
    private suspend fun extractHydrax(
        embedUrl: String,
        pageUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // ---------------- HX-1 ----------------
        val html =
            try {
                app.get(embedUrl, referer = pageUrl, headers = mapOf("User-Agent" to USER_AGENT))
                    .text
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LocalProxy.obs("HX-1-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
                return false
            }
        LocalProxy.obs("HX-1-OK", "html=${html.length} char")
        if (html.isBlank()) return false

        // ---------------- HX-2 ----------------
        val b64 = Regex("""["']([A-Za-z0-9+/=]{200,})["']""").find(html)?.groupValues?.get(1)
        if (b64.isNullOrBlank()) {
            LocalProxy.obs("HX-2-GAGAL", "blob base64 config tidak ditemukan")
            return false
        }
        LocalProxy.obs("HX-2-OK", "b64=${b64.length} char")

        // ---------------- HX-3 ----------------
        val rawBytes =
            try {
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LocalProxy.obs("HX-3-GAGAL", "base64 decode: ${e.message}")
                return false
            }

        val jsonObj =
            try {
                JSONObject(String(rawBytes, Charsets.ISO_8859_1))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LocalProxy.obs("HX-3-JSON-ERROR", "Parsing JSONObject gagal: ${e.message}")
                return false
            }

        val slug =
            jsonObj.optString("slug").takeIf { it.isNotBlank() } ?: extractMediaId(embedUrl) ?: ""
        val md5id = jsonObj.optString("md5_id").takeIf { it.isNotBlank() }
        val userId = jsonObj.optString("user_id").takeIf { it.isNotBlank() }
        val mediaStr = jsonObj.optString("media").takeIf { it.isNotBlank() }

        LocalProxy.obs(
            "HX-3-OK",
            "json=${rawBytes.size}B slug=$slug md5_id=$md5id user_id=$userId media_len=${mediaStr?.length ?: 0}",
        )

        // ---------------- HX-4 (Proses Dekripsi Field 'media') ----------------
        if (userId.isNullOrBlank() || md5id.isNullOrBlank() || mediaStr.isNullOrBlank()) {
            LocalProxy.obs(
                "HX-4-GAGAL",
                "Parameter terenkripsi tidak lengkap (userId=$userId, md5id=$md5id, media=${mediaStr != null})",
            )
            return false
        }

        val decryptedJson =
            try {
                val hashInput = "$userId:$slug:$md5id".toByteArray(Charsets.UTF_8)
                val md5Hash = MessageDigest.getInstance("MD5").digest(hashInput)
                val keyHex = md5Hash.joinToString("") { "%02x".format(it) }

                val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
                val ivBytes = keyBytes.copyOfRange(0, 16)

                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    IvParameterSpec(ivBytes),
                )

                String(cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LocalProxy.obs("HX-4-DECRYPT-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                return false
            }

        LocalProxy.obs("HX-4-DECRYPT-OK", "decrypted_len=${decryptedJson.length}")

        // ---------------- HX-5 (Parsing hasil dekripsi) ----------------
        val decryptedObj =
            try {
                JSONObject(decryptedJson)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LocalProxy.obs("HX-5-JSON-ERROR", "Parsing decrypted JSON gagal: ${e.message}")
                return false
            }

        val mp4Obj = decryptedObj.optJSONObject("mp4")
        val sourcesArr = mp4Obj?.optJSONArray("sources") ?: decryptedObj.optJSONArray("sources")

        if (sourcesArr == null || sourcesArr.length() == 0) {
            LocalProxy.obs(
                "HX-5-GAGAL",
                "sources kosong (len=${decryptedJson.length}): ${decryptedJson.take(300)}",
            )
            return false
        }

        val parsed = ArrayList<HydraxSource>()
        for (i in 0 until sourcesArr.length()) {
            val o = sourcesArr.optJSONObject(i) ?: continue
            LocalProxy.obs("HX-5-SOURCE[$i]", o.toString())
            parsed.add(
                HydraxSource(
                    label = o.optString("label").takeIf { it.isNotBlank() } ?: "HD",
                    codec = o.optString("codec").takeIf { it.isNotBlank() } ?: "",
                    resId = o.optString("res_id").takeIf { it.isNotBlank() && it != "null" },
                    size = o.optString("size").toLongOrNull(),
                    sub = o.optString("sub").takeIf { it.isNotBlank() && it != "null" },
                    url = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
                    path =
                        o.optString("path").takeIf { it.isNotBlank() && it != "null" }
                            ?: o.optString("file").takeIf { it.isNotBlank() && it != "null" }
                            ?: o.optString("src").takeIf { it.isNotBlank() && it != "null" },
                    partSize = o.optLong("partSize", 0L),
                )
            )
        }

        // Daftar domain CDN, dipakai untuk mencocokkan field `sub` tiap source
        val domainsArr = mp4Obj?.optJSONArray("domains") ?: decryptedObj.optJSONArray("domains")
        val domains = ArrayList<String>()
        for (i in 0 until (domainsArr?.length() ?: 0)) {
            val d = domainsArr?.optString(i)
            if (!d.isNullOrBlank()) domains.add(d)
        }

        LocalProxy.obs(
            "HX-5-OK",
            "sources=${parsed.size} domains=${domains.size} av1_support=$deviceSupportsAv1",
        )

        // ---------------- HX-6 (Pilih satu per resolusi, lalu emit) ----------------
        // Satu resolusi = satu baris di daftar Sumber CloudStream, karena satu
        // ExtractorLink selalu dirender sebagai satu entri.
        var linksEmitted = 0
        val emitted = HashSet<String>()

        val byLabel =
            parsed
                .groupBy { it.label }
                .toList()
                .sortedByDescending { (label, _) -> getQualityFromString(label) }

        for ((label, group) in byLabel) {
            val order = hydraxPreference(group)

            if (order.isEmpty()) {
                LocalProxy.obs(
                    "HX-6-SKIP-AV1",
                    "label=$label semua kandidat av1, perangkat tidak punya decodernya",
                )
                continue
            }
            if (group.size > 1) {
                LocalProxy.obs(
                    "HX-6-DEDUPE",
                    "label=$label kandidat=${group.size} codec=${group.map { it.codec }} " +
                        "-> dipilih codec=${order.first().codec} size=${order.first().size}",
                )
            }

            var chosen: HydraxSource? = null
            var fullUrl: String? = null

            for (src in order) {
                // The site's R2 fallback is encrypted at the start and split into
                // storage parts. It must be served as one decrypted, seekable MP4.
                if (USE_PROXY && src.size != null && src.url != null && src.path != null) {
                    val storageUrl =
                        if (src.path.startsWith("https://")) src.path
                        else
                            "${unescapeJs(src.url).trimEnd('/')}/${unescapeJs(src.path).trimStart('/')}"
                    fullUrl = LocalProxy.hydraxUrl(storageUrl, src.size, src.partSize)
                    if (fullUrl != null) {
                        LocalProxy.obs(
                            "HX-6-STORAGE",
                            "label=$label codec=${src.codec} size=${src.size} part=${src.partSize}",
                        )
                        chosen = src
                        break
                    }
                }

                // --- Jalur 1: /sora/ (mp4_native). Mengembalikan file utuh. ---
                if (src.resId != null && src.size != null && src.sub != null) {
                    val host = domains.firstOrNull { it.contains(src.sub) }
                    if (host != null) {
                        fullUrl =
                            try {
                                buildSoraUrl(md5id, src.resId, src.size, slug, host)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                LocalProxy.obs(
                                    "HX-6-SORA-ERROR",
                                    "${e.javaClass.simpleName}: ${e.message}",
                                )
                                null
                            }
                        if (fullUrl != null) {
                            LocalProxy.obs(
                                "HX-6-SORA",
                                "label=$label codec=${src.codec} res_id=${src.resId} size=${src.size} host=$host",
                            )
                        }
                    } else {
                        LocalProxy.obs(
                            "HX-6-SORA-SKIP",
                            "domain untuk sub='${src.sub}' tidak ditemukan",
                        )
                    }
                }

                // --- Jalur 2: url + path (perilaku lama, dipertahankan sebagai cadangan) ---
                if (fullUrl == null) {
                    val bUrl =
                        src.url
                            ?: mp4Obj?.optString("url")?.takeIf { it.isNotBlank() && it != "null" }
                            ?: decryptedObj.optString("url").takeIf {
                                it.isNotBlank() && it != "null"
                            }
                    val pPath = src.path

                    if (
                        pPath != null &&
                            (pPath.startsWith("http://") || pPath.startsWith("https://"))
                    ) {
                        fullUrl = unescapeJs(pPath)
                    } else if (!bUrl.isNullOrBlank() && !pPath.isNullOrBlank()) {
                        fullUrl =
                            "${unescapeJs(bUrl).trimEnd('/')}/${unescapeJs(pPath).trimStart('/')}"
                    }
                    if (fullUrl != null) {
                        LocalProxy.obs("HX-6-LEGACY", "label=$label (cadangan url+path)")
                    }
                }

                if (fullUrl != null) {
                    chosen = src
                    break
                }
            }

            val finalUrl = fullUrl
            if (chosen == null || finalUrl.isNullOrBlank() || !emitted.add(finalUrl)) continue

            val qualityVal = getQualityFromString(label)

            LocalProxy.obs(
                "HX-6-EMIT-DETAILS",
                "source=$serverName | name=$serverName | quality=$label ($qualityVal) | " +
                    "codec=${chosen.codec} | type=VIDEO | referer=https://abyssplayer.com/ | " +
                    "UA=$USER_AGENT | url=$finalUrl",
            )

            callback(
                newExtractorLink(
                    source = serverName,
                    name = serverName,
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = qualityVal
                    this.referer = "https://abyssplayer.com/"
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            linksEmitted++
        }

        if (linksEmitted == 0) {
            LocalProxy.obs(
                "HX-6-GAGAL",
                "Tidak ada URL yang bisa dibangun (len=${decryptedJson.length}): ${decryptedJson.take(300)}",
            )
            return false
        }

        LocalProxy.obs(
            "HX-6-EMIT-DIRECT",
            "Sukses emit $linksEmitted quality track under single server $serverName",
        )
        return true
    }

    private fun unpackDeanEdwards(packed: String): String? {
        val m =
            Regex(
                    """\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\('\|'\)""",
                    RegexOption.DOT_MATCHES_ALL,
                )
                .find(packed) ?: return null

        val payload = unescapeJs(m.groupValues[1])
        val radix = m.groupValues[2].toIntOrNull() ?: return null
        val count = m.groupValues[3].toIntOrNull() ?: return null
        val words = m.groupValues[4].split("|")
        if (radix < 2 || radix > 62 || count <= 0) return null

        val map = HashMap<String, String>(count * 2)
        for (i in 0 until count) {
            val tok = baseN(i, radix)
            val w = words.getOrNull(i)
            map[tok] = if (w.isNullOrEmpty()) tok else w
        }
        return Regex("""\b[0-9A-Za-z]+\b""").replace(payload) { mr -> map[mr.value] ?: mr.value }
    }

    private fun baseN(num: Int, radix: Int): String {
        if (num == 0) return "0"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % radix])
            n /= radix
        }
        return sb.toString()
    }

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                    }
                    '\'' -> {
                        sb.append('\'')
                        i += 2
                    }
                    '"' -> {
                        sb.append('"')
                        i += 2
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }
                    't' -> {
                        sb.append('\t')
                        i += 2
                    }
                    '/' -> {
                        sb.append('/')
                        i += 2
                    }
                    else -> {
                        sb.append(n)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // Extractor FileLions
    private suspend fun extractFileLions(
        embedUrl: String,
        pageUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html =
            try {
                app.get(embedUrl, referer = pageUrl, headers = mapOf("User-Agent" to USER_AGENT))
                    .text
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LocalProxy.obs("FL-1-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
                return false
            }
        LocalProxy.obs("FL-1-OK", "html=${html.length} char")
        if (html.isBlank()) return false

        val kandidat =
            Regex(
                    """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\s*\)?\s*\)""",
                    RegexOption.DOT_MATCHES_ALL,
                )
                .findAll(html)
                .map { it.value }
                .toList()

        if (kandidat.isEmpty()) {
            LocalProxy.obs("FL-2-GAGAL", "blok packed tidak ditemukan")
            return false
        }
        LocalProxy.obs("FL-2-OK", "blok=${kandidat.size} ukuran=${kandidat.map { it.length }}")

        var code: String? = null
        var links = LinkedHashMap<String, String>()
        for ((idx, blok) in kandidat.withIndex()) {
            val hasil = unpackDeanEdwards(blok)
            if (hasil.isNullOrBlank()) {
                LocalProxy.obs("FL-3-SKIP", "blok[$idx] unpack gagal")
                continue
            }
            val l = LinkedHashMap<String, String>()
            Regex(""""(\w+)"\s*:\s*"((?:https?://|/)[^"]+)"""").findAll(hasil).forEach {
                l[it.groupValues[1]] = it.groupValues[2]
            }
            if (l.isEmpty()) {
                Regex("""(\w+)\s*:\s*"((?:https?://|/)[^"]+)"""").findAll(hasil).forEach {
                    l[it.groupValues[1]] = it.groupValues[2]
                }
            }
            LocalProxy.obs("FL-3-OK", "blok[$idx] unpacked=${hasil.length} char links=${l.keys}")
            if (l.values.any { it.contains(".m3u8") || it.contains(".mp4") }) {
                code = hasil
                links = l
                break
            }
        }
        if (code == null) {
            LocalProxy.obs("FL-3-GAGAL", "tidak ada blok yang menghasilkan URL media")
            return false
        }

        val kandidatUrl =
            listOf("hls4", "hls2").firstNotNullOfOrNull { k ->
                links[k]?.takeIf { it.contains(".m3u8") || it.contains(".txt") }?.let { k to it }
            }
        if (kandidatUrl == null) {
            LocalProxy.obs("FL-4-BERHENTI", "hls4/hls2 tidak ada (links: ${links.keys})")
            return false
        }
        val (labelDipakai, urlMentah) = kandidatUrl
        val hls2 =
            sourceCatching { java.net.URL(java.net.URL(embedUrl), urlMentah).toString() }
                .getOrElse { urlMentah }

        val host = sourceCatching { java.net.URL(hls2).host }.getOrNull() ?: "?"
        val servedUrl = LocalProxy.proxyUrl(hls2, clean = true) ?: hls2
        val viaProxy = servedUrl != hls2
        LocalProxy.obs(
            "FL-4-EMIT",
            "label=$labelDipakai host=$host clean=$viaProxy tersedia=${links.keys} url=$hls2",
        )
        callback(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = servedUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = ""
            }
        )
        LocalProxy.obs("FL-4-OK", "emit=1 ($labelDipakai)")

        if (FL_DIAG) {
            try {
                val pl = app.get(hls2, headers = mapOf("User-Agent" to USER_AGENT)).text
                val resDari = { tag: String ->
                    Regex("""$tag:[^\n]*""")
                        .findAll(pl)
                        .map { mr ->
                            Regex("""RESOLUTION=(\d+x\d+)""").find(mr.value)?.groupValues?.get(1)
                                ?: "?"
                        }
                        .toList()
                }
                val normal = resDari("#EXT-X-STREAM-INF")
                val iframe = resDari("#EXT-X-I-FRAME-STREAM-INF")
                LocalProxy.obs(
                    "FL-5-MASTER",
                    "bytes=${pl.length} normal=${normal.size}$normal " +
                        "iframe=${iframe.size}$iframe total_entri=${normal.size + iframe.size}",
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LocalProxy.obs("FL-5-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return true
    }

    private fun extractMediaId(url: String): String? {
        val base = url.substringBefore('#')
        Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(base)?.let {
            return it.groupValues[1]
        }
        val seg = base.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return if (seg.matches(Regex("""[A-Za-z0-9_-]{4,}"""))) seg else null
    }
}
