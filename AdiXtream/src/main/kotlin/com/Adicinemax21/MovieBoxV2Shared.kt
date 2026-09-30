package com.Adicinemax21

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Shared MovieBox source used by Adicinemax21, AdiDrakor, AdiFilmSemi and AdiXtream.
 *
 * Scope is intentionally MovieBox-only. Metadata, Idlix, VidSrc and each host
 * provider remain owned by their original implementation.
 */
object MovieBoxV2Shared {
    private const val API_URL = "https://api3.aoneroom.com"
    private const val API_FALLBACK = "https://api4sg.aoneroom.com"
    private const val USER_AGENT =
        "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"

    private const val ID_PREFS = "moviebox_v2_shared_identity"
    private const val ID_KEY = "apkdeviceid"
    private val ID_FORMAT = Regex("^[0-9a-f]{32}$")

    private const val MAX_SUBJECTS_TV = 6
    private const val MAX_SUBJECTS_MOVIE = 3

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedDeviceId: String? = null
    @Volatile private var persisted = false

    private data class Subject(
        val id: String,
        val title: String,
        val type: Int?,
        val releaseDate: String?
    )

    private data class Stream(
        val id: String?,
        val url: String,
        val cookie: String?,
        val resolutions: String?,
        val codec: String?,
        val format: String?
    )

    private data class PlaybackResult(
        val streams: List<Stream>,
        val bearer: String
    )

    fun attachContext(context: Context) {
        appContext = context.applicationContext
        deviceId()
    }

    private val SECRET_BYTES: ByteArray by lazy {
        val step1 = String(
            Base64.decode(
                "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==",
                Base64.DEFAULT
            ),
            Charsets.UTF_8
        )
        Base64.decode(step1, Base64.DEFAULT)
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun deviceId(): String {
        val cached = cachedDeviceId
        if (cached != null && persisted) return cached

        val ctx = appContext
        if (ctx != null) {
            try {
                val sp = ctx.getSharedPreferences(ID_PREFS, Context.MODE_PRIVATE)
                val existing = sp.getString(ID_KEY, null)
                if (!existing.isNullOrBlank() && ID_FORMAT.matches(existing)) {
                    cachedDeviceId = existing
                    persisted = true
                    return existing
                }
                val fresh = cached ?: md5(UUID.randomUUID().toString())
                sp.edit().putString(ID_KEY, fresh).apply()
                cachedDeviceId = fresh
                persisted = true
                return fresh
            } catch (_: Exception) {
            }
        }

        return cached ?: md5(UUID.randomUUID().toString()).also { cachedDeviceId = it }
    }

    private fun catalogClientInfo(): String =
        JSONObject()
            .put("package_name", "com.community.oneroom")
            .put("version_name", "3.0.13.0325.03")
            .put("version_code", 50020088)
            .put("os", "android")
            .put("os_version", "13")
            .put("device_id", deviceId())
            .put("install_store", "ps")
            .put("system_language", "en")
            .put("net", "NETWORK_WIFI")
            .put("region", "US")
            .put("timezone", "Asia/Calcutta")
            .put("sp_code", "")
            .toString()

    private fun playbackClientInfo(): String =
        JSONObject()
            .put("package_name", "com.community.oneroom")
            .put("version_name", "4.0.03.0922.03")
            .put("version_code", 50020131L)
            .put("os", "android")
            .put("os_version", Build.VERSION.RELEASE ?: "")
            .put("device_id", deviceId())
            .put("install_store", "ps")
            .put("brand", Build.BRAND ?: "")
            .put("model", Build.MODEL ?: "")
            .put("system_language", java.util.Locale.getDefault().language)
            .put("net", "NETWORK_WIFI")
            .put("region", java.util.Locale.getDefault().country)
            .put("timezone", java.util.TimeZone.getDefault().id)
            .put("sp_code", "")
            .toString()

    private fun normalCanonical(
        method: String,
        pathWithQuery: String,
        ts: String,
        body: String = ""
    ): String {
        val length = if (body.isEmpty()) "" else body.length.toString()
        val digest = if (body.isEmpty()) "" else md5(body)
        return listOf(
            method.uppercase(),
            "application/json",
            "application/json",
            length,
            ts,
            digest,
            pathWithQuery
        ).joinToString("\n")
    }

    private fun sign(canonical: String, ts: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
        val bytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return "$ts|2|${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun normalSignature(
        method: String,
        pathWithQuery: String,
        ts: String,
        body: String = ""
    ): String = sign(normalCanonical(method, pathWithQuery, ts, body), ts)

    private fun playbackSignature(pathWithCanonicalQuery: String, ts: String): String =
        sign(
            listOf("GET", "", "", "", ts, "", pathWithCanonicalQuery)
                .joinToString("\n"),
            ts
        )

    private fun guestToken(ts: String): String = "$ts,${md5(ts.reversed())}"

    private fun normalHeaders(
        ts: String,
        signature: String,
        bearer: String?
    ): Map<String, String> {
        val out = mutableMapOf(
            "user-agent" to USER_AGENT,
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to guestToken(ts),
            "x-tr-signature" to signature,
            "x-client-info" to catalogClientInfo(),
            "x-client-status" to "0"
        )
        if (!bearer.isNullOrBlank()) out["authorization"] = "Bearer $bearer"
        return out
    }

    private suspend fun getBearer(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "page=1&perPage=1&tabId=0"
        return try {
            val response = app.get(
                "$API_URL$path?$query",
                headers = normalHeaders(
                    ts,
                    normalSignature("GET", "$path?$query", ts),
                    null
                )
            )
            val raw = response.headers["x-user"] ?: return null
            Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
                .find(raw)?.groupValues?.getOrNull(1)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getSigned(path: String, query: String, bearer: String?): String? {
        val ts = System.currentTimeMillis().toString()
        val target = if (query.isBlank()) path else "$path?$query"
        return try {
            val response = app.get(
                "$API_URL$target",
                headers = normalHeaders(
                    ts,
                    normalSignature("GET", target, ts),
                    bearer
                )
            )
            if (response.code == 200) response.text else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun postSigned(path: String, body: String, bearer: String?): String? {
        val ts = System.currentTimeMillis().toString()
        return try {
            val response = app.post(
                "$API_URL$path",
                headers = normalHeaders(
                    ts,
                    normalSignature("POST", path, ts, body),
                    bearer
                ),
                requestBody = body.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaTypeOrNull())
            )
            if (response.code == 200) response.text else null
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeTitle(value: String): String =
        value.replace(Regex("[^A-Za-z0-9]"), "").lowercase()

    private suspend fun searchSubjects(
        query: String,
        matchYear: Int?,
        wantedType: Int,
        bearer: String,
        tag: String
    ): List<Subject> {
        val normalized = normalizeTitle(query)
        if (normalized.isBlank()) return emptyList()

        val body = JSONObject()
            .put("page", 1)
            .put("perPage", 10)
            .put("keyword", query)
            .put("tabId", "")
            .toString()

        val raw = postSigned(
            "/wefeed-mobile-bff/subject-api/search/v2",
            body,
            bearer
        ) ?: return emptyList()

        return try {
            val results = JSONObject(raw)
                .optJSONObject("data")
                ?.optJSONArray("results")

            val all = mutableListOf<Subject>()
            val seen = mutableSetOf<String>()
            for (i in 0 until (results?.length() ?: 0)) {
                val subjects = results!!.optJSONObject(i)?.optJSONArray("subjects") ?: continue
                for (j in 0 until subjects.length()) {
                    val item = subjects.optJSONObject(j) ?: continue
                    val id = item.optString("subjectId", "")
                    val title = item.optString("title", "")
                    if (id.isBlank() || title.isBlank() || !seen.add(id)) continue
                    val type = if (item.has("subjectType")) item.optInt("subjectType") else null
                    val date = item.optString("releaseDate", "").takeIf { it.isNotBlank() }
                    all += Subject(id, title, type, date)
                }
            }

            val ranked = mutableListOf<Pair<Int, Subject>>()
            for (subject in all) {
                if (subject.type != null && subject.type != wantedType) continue
                val cleanTitle = normalizeTitle(subject.title)
                if (cleanTitle.isBlank()) continue
                val rank = when {
                    cleanTitle == normalized -> 0
                    cleanTitle.startsWith(normalized) -> 1
                    cleanTitle.contains(normalized) -> 2
                    normalized.contains(cleanTitle) -> 3
                    else -> -1
                }
                if (rank < 0) continue
                if (subject.type == null && rank != 0) continue

                val subjectYear = subject.releaseDate
                    ?.substringBefore('-')
                    ?.toIntOrNull()
                val yearOk = when {
                    matchYear == null || subjectYear == null -> true
                    wantedType == 2 -> subjectYear >= matchYear - 1
                    else -> abs(subjectYear - matchYear) <= 1
                }
                if (!yearOk) continue
                ranked += rank to subject
            }

            val strong = ranked.filter { it.first <= 2 }
            (if (strong.isNotEmpty()) strong else ranked)
                .sortedBy { it.first }
                .map { it.second }
        } catch (e: Exception) {
            Log.e(tag, "[MOVIEBOX-V2] search parse gagal: ${e.message}")
            emptyList()
        }
    }

    private suspend fun seasons(subjectId: String, bearer: String): List<Int> {
        val raw = getSigned(
            "/wefeed-mobile-bff/subject-api/season-info",
            "subjectId=$subjectId",
            bearer
        ) ?: return emptyList()
        return try {
            val array = JSONObject(raw)
                .optJSONObject("data")
                ?.optJSONArray("seasons")
            buildList {
                for (i in 0 until (array?.length() ?: 0)) {
                    val item = array!!.optJSONObject(i) ?: continue
                    if (item.has("se")) add(item.optInt("se"))
                }
            }.distinct().sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun canonicalQuery(query: String): String =
        query.split("&")
            .filter { it.isNotBlank() }
            .sortedBy { it.substringBefore('=') }
            .joinToString("&")

    private fun playbackHeaders(query: String, bearer: String): Map<String, String> {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/subject-api/play-info/v2"
        val signature = playbackSignature("$path?${canonicalQuery(query)}", ts)
        return mapOf(
            "authorization" to "Bearer $bearer",
            "x-tr-signature" to signature,
            "x-client-info" to playbackClientInfo(),
            "x-client-status" to "1"
        )
    }

    private fun noticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("b164fbfb4347792950bdfbfb563d39d9") ||
            lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("/other/2026/09/") ||
            lower.contains("/notice.mp4") ||
            lower.contains("upgrade-notice")
    }

    private fun edgeManifest(cookie: String): String? {
        val encoded = Regex(
            """Edge-Cache-Cookie=urlprefix=([^:;\s]+)""",
            RegexOption.IGNORE_CASE
        ).find(cookie)?.groupValues?.getOrNull(1) ?: return null
        return try {
            val normalized = encoded.replace('_', '/').replace('-', '+') +
                "=".repeat((4 - encoded.length % 4) % 4)
            val prefix = String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
                .trimEnd('/')
            if (prefix.endsWith(".mpd", true) || prefix.endsWith(".m3u8", true)) {
                prefix
            } else {
                "$prefix/index.mpd"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cloudFrontManifest(cookie: String): String? {
        val encoded = Regex(
            """CloudFront-Policy=([^;]+)""",
            RegexOption.IGNORE_CASE
        ).find(cookie)?.groupValues?.getOrNull(1) ?: return null
        return try {
            val normalized = encoded
                .replace('-', '+')
                .replace('~', '/')
                .replace('_', '=') +
                "=".repeat((4 - encoded.length % 4) % 4)
            val json = String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
            val resource = JSONObject(json)
                .optJSONArray("Statement")
                ?.optJSONObject(0)
                ?.optString("Resource")
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return null
            val base = resource.trimEnd('*').trimEnd('/')
            if (base.endsWith(".mpd", true) || base.endsWith(".m3u8", true)) {
                base
            } else {
                "$base/index.mpd"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvedUrl(url: String, cookie: String?): String? {
        val resolved = if (cookie.isNullOrBlank()) {
            url
        } else {
            edgeManifest(cookie) ?: cloudFrontManifest(cookie) ?: url
        }
        return resolved.takeUnless(::noticeUrl)
    }

    private suspend fun playInfo(
        subjectId: String,
        se: Int,
        ep: Int,
        initialBearer: String,
        tag: String
    ): PlaybackResult? {
        var bearer = initialBearer
        val path = "/wefeed-mobile-bff/subject-api/play-info/v2"
        val queries = listOf(
            "subjectId=$subjectId&se=$se&ep=$ep",
            "subjectId=$subjectId&se=$se&ep=$ep" +
                "&streamSignType=1" +
                "&supportCodecs%5Bhevc%5D=1" +
                "&supportCodecs%5Bh264%5D=1"
        )

        for (host in listOf(API_URL, API_FALLBACK)) {
            for (query in queries) {
                var response = try {
                    app.get("$host$path?$query", headers = playbackHeaders(query, bearer))
                } catch (e: Exception) {
                    Log.e(tag, "[MOVIEBOX-V2] request gagal host=$host: ${e.message}")
                    continue
                }

                if (response.code == 401 || response.code == 441) {
                    val refreshed = getBearer()
                    if (!refreshed.isNullOrBlank()) {
                        bearer = refreshed
                        response = try {
                            app.get("$host$path?$query", headers = playbackHeaders(query, bearer))
                        } catch (_: Exception) {
                            continue
                        }
                    }
                }
                if (response.code != 200) continue

                val parsed = try {
                    val root = JSONObject(response.text)
                    if (root.has("code") && root.optInt("code", 0) != 0) continue
                    val data = root.optJSONObject("data") ?: continue
                    val dataCookie = data.optString("signCookie", "").takeIf { it.isNotBlank() }
                    val array = data.optJSONArray("streams") ?: continue
                    buildList {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            val rawUrl = item.optString("url", "")
                            if (rawUrl.isBlank()) continue
                            val cookie = item.optString("signCookie", "")
                                .takeIf { it.isNotBlank() }
                                ?: dataCookie
                            val url = resolvedUrl(rawUrl, cookie) ?: continue
                            add(
                                Stream(
                                    id = item.optString("id", "").takeIf { it.isNotBlank() },
                                    url = url,
                                    cookie = cookie,
                                    resolutions = item.optString("resolutions", "").takeIf { it.isNotBlank() },
                                    codec = item.optString("codecName", "").takeIf { it.isNotBlank() },
                                    format = item.optString("format", "").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }

                if (parsed.isNotEmpty()) {
                    Log.d(tag, "[MOVIEBOX-V2] subject=$subjectId se=$se ep=$ep streams=${parsed.size}")
                    return PlaybackResult(parsed.distinctBy { it.url }, bearer)
                }
            }
        }
        return null
    }

    private fun linkType(stream: Stream): ExtractorLinkType {
        val url = stream.url.lowercase()
        return when {
            url.contains(".mpd") || stream.format.equals("dash", true) -> ExtractorLinkType.DASH
            url.contains(".m3u8") || stream.format.equals("hls", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun quality(stream: Stream): Int =
        Regex("""\d{3,4}""")
            .findAll(stream.resolutions.orEmpty())
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 144..4320 }
            .maxOrNull() ?: 1080

    private suspend fun subtitles(
        subjectId: String,
        streamId: String?,
        bearer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (streamId.isNullOrBlank()) return
        val raw = getSigned(
            "/wefeed-mobile-bff/subject-api/get-stream-captions",
            "streamId=$streamId&subjectId=$subjectId",
            bearer
        ) ?: return
        try {
            val array = JSONObject(raw)
                .optJSONObject("data")
                ?.optJSONArray("extCaptions")
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array!!.optJSONObject(i) ?: continue
                val url = item.optString("url", "")
                if (url.isBlank()) continue
                val label = item.optString("lanName", "").ifBlank {
                    item.optString("lan", "").ifBlank { "Unknown" }
                }
                subtitleCallback(newSubtitleFile(label, url))
            }
        } catch (_: Exception) {
        }
    }

    suspend fun invokeMoviebox(
        sourceTag: String,
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?,
        airedYear: Int? = null,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val matchYear = if (season != null) (airedYear ?: year) else year
        val wantedType = if (season != null) 2 else 1
        var bearer = getBearer() ?: run {
            Log.e(sourceTag, "[MOVIEBOX-V2] bearer token null")
            return
        }

        val queries = listOfNotNull(
            title,
            title.substringBefore(':'),
            orgTitle,
            orgTitle?.substringBefore(':'),
            altTitle,
            altTitle?.substringBefore(':')
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        var candidates = emptyList<Subject>()
        for (query in queries) {
            candidates = searchSubjects(query, matchYear, wantedType, bearer, sourceTag)
            if (candidates.isNotEmpty()) break
        }
        if (candidates.isEmpty()) return

        val pool = candidates.take(if (season == null) MAX_SUBJECTS_MOVIE else MAX_SUBJECTS_TV)
        var chosenId: String? = null
        var result: PlaybackResult? = null
        val wantedEpisode = episode ?: 1

        if (season == null) {
            val pairs = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
            loop@ for (subject in pool) {
                for ((se, ep) in pairs) {
                    val current = playInfo(subject.id, se, ep, bearer, sourceTag)
                    if (current != null) {
                        chosenId = subject.id
                        result = current
                        bearer = current.bearer
                        break@loop
                    }
                }
            }
        } else {
            val unknown = mutableListOf<Subject>()
            loop@ for (subject in pool) {
                val available = seasons(subject.id, bearer)
                if (available.isEmpty()) {
                    unknown += subject
                    continue
                }
                val serverSeason = when {
                    available.contains(season) -> season
                    available.firstOrNull() == 0 && available.contains(season - 1) -> season - 1
                    else -> null
                } ?: continue
                val current = playInfo(subject.id, serverSeason, wantedEpisode, bearer, sourceTag)
                if (current != null) {
                    chosenId = subject.id
                    result = current
                    bearer = current.bearer
                    break@loop
                }
            }

            if (result == null) {
                val fallbackPairs = if (season == 1) {
                    listOf(1 to wantedEpisode, 0 to wantedEpisode)
                } else {
                    listOf(season to wantedEpisode)
                }
                loop@ for (subject in unknown) {
                    for ((se, ep) in fallbackPairs) {
                        val current = playInfo(subject.id, se, ep, bearer, sourceTag)
                        if (current != null) {
                            chosenId = subject.id
                            result = current
                            bearer = current.bearer
                            break@loop
                        }
                    }
                }
            }
        }

        val subjectId = chosenId ?: return
        val streams = result?.streams ?: return
        subtitles(subjectId, streams.firstOrNull()?.id, bearer, subtitleCallback)

        for (stream in streams) {
            val type = linkType(stream)
            val q = quality(stream)
            val kind = when (type) {
                ExtractorLinkType.DASH -> "DASH"
                ExtractorLinkType.M3U8 -> "HLS"
                else -> "VIDEO"
            }
            val codec = stream.codec?.let { " ${it.uppercase()}" }.orEmpty()
            val headers = mutableMapOf(
                "Referer" to "$API_URL/",
                "User-Agent" to USER_AGENT
            )
            stream.cookie?.let { headers["Cookie"] = it }

            callback(
                newExtractorLink(
                    source = "MovieBox",
                    name = "MovieBox $kind ${q}p$codec",
                    url = stream.url,
                    type = type
                ) {
                    quality = q
                    this.headers = headers
                }
            )
        }
    }
}
