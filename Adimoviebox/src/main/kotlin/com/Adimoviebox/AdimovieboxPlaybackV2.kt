package com.Adimoviebox

import android.os.Build
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Playback-only V2 layer for the active Adimoviebox plugin.
 *
 * Home, categories, search, detail, metadata and recommendations remain delegated
 * unchanged to the existing Adimoviebox implementation. Only loadLinks() is
 * replaced with the proven MovieBox play-info/v2 flow.
 */
class AdimovieboxPlaybackV2Provider : MainAPI() {
    private val delegate = Adimoviebox()

    override var mainUrl = delegate.mainUrl
    override var name = delegate.name
    override var lang = delegate.lang
    override val supportedTypes = delegate.supportedTypes
    override var hasMainPage = delegate.hasMainPage
    override val hasQuickSearch = delegate.hasQuickSearch
    override val instantLinkLoading = delegate.instantLinkLoading
    override val mainPage = delegate.mainPage

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = delegate.getMainPage(page, request)

    override suspend fun search(query: String): List<SearchResponse> = delegate.search(query)

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        delegate.quickSearch(query)

    override suspend fun load(url: String): LoadResponse? = delegate.load(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val media = try {
            parseJson<LoadData>(data)
        } catch (error: Exception) {
            Log.e("Adimoviebox", "[MOVIEBOX-V2] invalid load data: ${error.message}")
            return false
        }

        val subjectId = media.id?.takeIf { it.isNotBlank() } ?: return false
        return AdimovieboxPlaybackV2.resolve(
            subjectId = subjectId,
            season = media.season,
            episode = media.episode,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}

private object AdimovieboxPlaybackV2 {
    private const val TAG = "AdimovieboxMB"
    private const val API_URL = "https://api3.aoneroom.com"
    private const val API_FALLBACK = "https://api4sg.aoneroom.com"
    private const val USER_AGENT =
        "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"

    private val deviceId: String by lazy { md5(UUID.randomUUID().toString()) }

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

    private val secretBytes: ByteArray by lazy {
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

    private fun catalogClientInfo(): String =
        JSONObject()
            .put("package_name", "com.community.oneroom")
            .put("version_name", "3.0.13.0325.03")
            .put("version_code", 50020088)
            .put("os", "android")
            .put("os_version", "13")
            .put("device_id", deviceId)
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
            .put("device_id", deviceId)
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
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val bytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return "$ts|2|${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun normalSignature(method: String, target: String, ts: String): String =
        sign(normalCanonical(method, target, ts), ts)

    private fun playbackSignature(target: String, ts: String): String =
        sign(listOf("GET", "", "", "", ts, "", target).joinToString("\n"), ts)

    private fun guestToken(ts: String): String = "$ts,${md5(ts.reversed())}"

    private fun normalHeaders(
        ts: String,
        signature: String,
        bearer: String?
    ): Map<String, String> {
        val headers = mutableMapOf(
            "user-agent" to USER_AGENT,
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to guestToken(ts),
            "x-tr-signature" to signature,
            "x-client-info" to catalogClientInfo(),
            "x-client-status" to "0"
        )
        if (!bearer.isNullOrBlank()) headers["authorization"] = "Bearer $bearer"
        return headers
    }

    private suspend fun getBearer(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "page=1&perPage=1&tabId=0"
        val target = "$path?$query"
        return try {
            val response = app.get(
                "$API_URL$target",
                headers = normalHeaders(ts, normalSignature("GET", target, ts), null)
            )
            val raw = response.headers["x-user"] ?: return null
            Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
                .find(raw)?.groupValues?.getOrNull(1)
        } catch (error: Exception) {
            Log.e(TAG, "[MOVIEBOX-V2] bearer gagal: ${error.message}")
            null
        }
    }

    private suspend fun getSigned(
        path: String,
        query: String,
        bearer: String
    ): String? {
        val ts = System.currentTimeMillis().toString()
        val target = if (query.isBlank()) path else "$path?$query"
        return try {
            val response = app.get(
                "$API_URL$target",
                headers = normalHeaders(ts, normalSignature("GET", target, ts), bearer)
            )
            if (response.code == 200) response.text else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun seasons(subjectId: String, bearer: String): List<Int> {
        val raw = getSigned(
            "/wefeed-mobile-bff/subject-api/season-info",
            "subjectId=$subjectId",
            bearer
        ) ?: return emptyList()
        return try {
            val array = JSONObject(raw).optJSONObject("data")?.optJSONArray("seasons")
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
        val target = "$path?${canonicalQuery(query)}"
        return mapOf(
            "authorization" to "Bearer $bearer",
            "x-tr-signature" to playbackSignature(target, ts),
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
        initialBearer: String
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
                } catch (error: Exception) {
                    Log.e(TAG, "[MOVIEBOX-V2] request gagal host=$host: ${error.message}")
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
                    Log.d(TAG, "[MOVIEBOX-V2] subject=$subjectId se=$se ep=$ep streams=${parsed.size}")
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
            val array = JSONObject(raw).optJSONObject("data")?.optJSONArray("extCaptions")
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array!!.optJSONObject(i) ?: continue
                val url = item.optString("url", "")
                if (url.isBlank()) continue
                val label = item.optString("lanName", "").ifBlank {
                    item.optString("lan", "").ifBlank { "Unknown" }
                }
                subtitleCallback(SubtitleFile(label, url))
            }
        } catch (_: Exception) {
        }
    }

    suspend fun resolve(
        subjectId: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var bearer = getBearer() ?: run {
            Log.e(TAG, "[MOVIEBOX-V2] bearer token null")
            return false
        }

        val wantedEpisode = episode ?: 1
        val pairs = if (season == null) {
            listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
        } else {
            val available = seasons(subjectId, bearer)
            val mappedSeason = when {
                available.contains(season) -> season
                available.firstOrNull() == 0 && available.contains(season - 1) -> season - 1
                else -> season
            }
            buildList {
                add(mappedSeason to wantedEpisode)
                add(season to wantedEpisode)
                if (season == 1) add(0 to wantedEpisode)
            }.distinct()
        }

        var result: PlaybackResult? = null
        for ((se, ep) in pairs) {
            val current = playInfo(subjectId, se, ep, bearer)
            if (current != null) {
                result = current
                bearer = current.bearer
                break
            }
        }

        val streams = result?.streams ?: return false
        subtitles(subjectId, streams.firstOrNull()?.id, bearer, subtitleCallback)

        var emitted = 0
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
                    source = "Adimoviebox",
                    name = "Adimoviebox $kind ${q}p$codec",
                    url = stream.url,
                    type = type
                ) {
                    quality = q
                    this.headers = headers
                }
            )
            emitted += 1
        }

        Log.i(TAG, "[MOVIEBOX-V2] emitted=$emitted subject=$subjectId")
        return emitted > 0
    }
}
