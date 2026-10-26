package com.Moviebox

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class MovieBoxProvider : MainAPI() {
    private val playbackContext = MovieboxPlaybackContext()
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // Disusun dari daftar kategori yang server kirim (lihat mainPageEntries).
    override val mainPage = mainPageOf(*mainPageEntries())

    companion object {
        private const val TAG = "MovieBox"
        private const val CS_USER_AGENT =
            "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        private const val PLAYBACK_TOKEN_URL =
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
        private const val PLAYBACK_USER_AGENT =
            "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
        private const val PLAYBACK_VERSION_NAME = "4.0.03.0920.03"
        private const val PLAYBACK_VERSION_CODE = 50020130L

        // Keep the catalog device ID stable across app restarts.
        private fun clientInfo(): String =
            "{\"package_name\":\"com.community.oneroom\",\"version_name\":\"3.0.13.0325.03\"," +
                "\"version_code\":50020088,\"os\":\"android\",\"os_version\":\"13\"," +
                "\"device_id\":\"${deviceId()}\",\"install_store\":\"ps\"," +
                "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"US\"," +
                "\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

        // Ranking responses refresh this cached category list.
        private const val CAT_KEY = "categorylist"

        private val SEED_CATEGORIES =
            listOf(
                "4809349160627587984" to "Semua",
                "4380734070238626200" to "K-Drama",
                "5283462032510044280" to "Indo Drama",
                "8617025562613270856" to "Anime",
                "5307082080063488480" to "Barat",
                "8624142774394406504" to "C-Drama",
                "1164329479448281992" to "Thai-Drama",
                "5720220657917522824" to "Reality",
            )

        @Volatile private var categories: List<Pair<String, String>> = SEED_CATEGORIES

        // Filter rows use subject-api/list instead of ranking-list.
        private const val FILTER_PREFIX = "filter:"

        private val EXTRA_ROWS =
            listOf(
                FILTER_PREFIX + "subjectType=1&genre=Horror&country=Indonesia" to "Horror Indonesia"
            )

        private fun prefs() =
            try {
                appContext?.getSharedPreferences(ID_PREFS, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                null
            }

        /** Dipakai oleh mainPage. Sudah terisi karena attachContext() dipanggil duluan. */
        private fun mainPageEntries(): Array<Pair<String, String>> =
            (categories + EXTRA_ROWS).toTypedArray()

        private fun loadCategories() {
            val parsed =
                prefs()
                    ?.getString(CAT_KEY, null)
                    ?.split("\n")
                    ?.mapNotNull { line ->
                        val p = line.split("\t")
                        if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) p[0] to p[1]
                        else null
                    }
                    ?.takeIf { it.isNotEmpty() }
            if (parsed != null) categories = parsed
            Log.d(
                TAG,
                "[CATEGORY] dimuat ${categories.size} kategori " +
                    "(${if (parsed != null) "tersimpan" else "seed"}): " +
                    categories.joinToString(", ") { it.second },
            )
        }

        /** Dipanggil dari getMainPage. Menyimpan hanya bila daftar server berubah. */
        private fun rememberCategories(fresh: List<CategoryItem>) {
            val list =
                fresh.mapNotNull { c ->
                    val t = c.type
                    val n = c.name
                    if (t.isNullOrBlank() || n.isNullOrBlank()) null else t to n
                }
            if (list.isEmpty() || list == categories) return
            categories = list
            try {
                prefs()
                    ?.edit()
                    ?.putString(CAT_KEY, list.joinToString("\n") { "${it.first}\t${it.second}" })
                    ?.apply()
                Log.d(
                    TAG,
                    "[CATEGORY] daftar server berubah -> disimpan ${list.size}: " +
                        list.joinToString(", ") { it.second },
                )
            } catch (e: Exception) {
                Log.e(TAG, "[CATEGORY] gagal menyimpan: ${e.message}")
            }
        }

        private const val ID_PREFS = "moviebox_identity"
        private const val ID_KEY = "apkdeviceid"
        private val ID_FORMAT = Regex("^[0-9a-f]{32}$")

        @Volatile private var appContext: Context? = null
        @Volatile private var cachedDeviceId: String? = null
        @Volatile private var persisted = false

        /** Dipanggil dari MovieboxPlugin.load() sebelum provider didaftarkan. */
        fun attachContext(context: Context) {
            appContext = context.applicationContext
            loadCategories()
            deviceId()
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
                } catch (e: Exception) {
                    Log.e(TAG, "[IDENTITY] storage tidak tersedia: ${e.message}")
                }
            }

            return cached ?: md5(UUID.randomUUID().toString()).also { cachedDeviceId = it }
        }

        private val SECRET_BYTES: ByteArray by lazy {
            val step1 =
                String(
                    Base64.decode(
                        "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==",
                        Base64.DEFAULT,
                    ),
                    Charsets.UTF_8,
                )
            Base64.decode(step1, Base64.DEFAULT)
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
        }

        // Catalog signatures include both the body length and its MD5.
        private fun buildCanonical(
            method: String,
            pathWithQuery: String,
            ts: String,
            body: String,
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
                    pathWithQuery,
                )
                .joinToString("\n")
        }

        private fun generateSignature(
            method: String,
            pathWithQuery: String,
            ts: String,
            body: String = "",
        ): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val hmacBytes =
                mac.doFinal(
                    buildCanonical(method, pathWithQuery, ts, body).toByteArray(Charsets.UTF_8)
                )
            return "$ts|2|${Base64.encodeToString(hmacBytes, Base64.NO_WRAP)}"
        }

        private fun generateGuestToken(ts: String): String = "$ts,${md5(ts.reversed())}"
    }

    private fun headersFor(ts: String, signature: String, bearer: String?): Map<String, String> {
        val h =
            mutableMapOf(
                "user-agent" to CS_USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to signature,
                "x-client-info" to clientInfo(),
                "x-client-status" to "0",
            )
        if (!bearer.isNullOrBlank()) h["authorization"] = "Bearer $bearer"
        return h
    }

    // ByteArray keeps Content-Type exactly application/json for signature matching.
    private suspend fun postSigned(path: String, body: String, bearer: String?): String? {
        val ts = System.currentTimeMillis().toString()
        val sig = generateSignature("POST", path, ts, body)
        return try {
            val res =
                app.post(
                    "$mainUrl$path",
                    headers = headersFor(ts, sig, bearer),
                    requestBody =
                        body
                            .toByteArray(Charsets.UTF_8)
                            .toRequestBody("application/json".toMediaTypeOrNull()),
                )
            Log.d(TAG, "POST $path HTTP=${res.code} bytes=${res.text.length}")
            if (res.code == 200) res.text else null
        } catch (e: Exception) {
            Log.e(TAG, "POST $path gagal: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun getBearerToken(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "page=1&perPage=1&tabId=0"

        val response =
            app.get(
                "$mainUrl$path?$query",
                headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), null),
            )

        val xUserHeader = response.headers["x-user"] ?: return null
        return """"token"\s*:\s*"([^"]+)"""".toRegex().find(xUserHeader)?.groupValues?.get(1)
    }

    private val modernPlaybackDeviceId: String by lazy {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    private fun modernPlaybackClientInfo(): String =
        JSONObject()
            .put("package_name", "com.community.mbox.in")
            .put("version_name", PLAYBACK_VERSION_NAME)
            .put("version_code", PLAYBACK_VERSION_CODE)
            .put("os", "android")
            .put("os_version", "14")
            .put("device_id", modernPlaybackDeviceId)
            .put("install_store", "official")
            .put("brand", "Google")
            .put("model", "Pixel 8")
            .put("system_language", "en")
            .put("net", "NETWORK_WIFI")
            .put("region", "IN")
            .put("timezone", "Asia/Calcutta")
            .put("sp_code", "")
            .toString()

    // Match the playback signature: decode the URI query, then sort its keys.
    private fun modernPlaybackCanonicalPath(url: String): String {
        val uri = URI(url)
        val path = uri.path.orEmpty()
        val query = uri.query.orEmpty()
        if (query.isBlank()) return path

        val sortedQuery =
            query
                .split("&")
                .filter { it.isNotBlank() }
                .map { entry ->
                    val parts = entry.split("=", limit = 2)
                    parts[0] to parts.getOrElse(1) { "" }
                }
                .sortedBy { it.first }
                .joinToString("&") { "${it.first}=${it.second}" }

        return if (sortedQuery.isBlank()) path else "$path?$sortedQuery"
    }

    private fun modernPlaybackSignature(url: String, ts: String): String {
        val canonical =
            listOf(
                    "GET",
                    "application/json",
                    "application/json",
                    "",
                    ts,
                    "",
                    modernPlaybackCanonicalPath(url),
                )
                .joinToString("\n")

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
        val signed = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        return "$ts|2|${Base64.encodeToString(signed, Base64.NO_WRAP)}"
    }

    private fun modernPlaybackHeaders(url: String, bearer: String? = null): Map<String, String> {
        val ts = System.currentTimeMillis().toString()
        val headers =
            mutableMapOf(
                "user-agent" to PLAYBACK_USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to modernPlaybackSignature(url, ts),
                "x-client-info" to modernPlaybackClientInfo(),
                "x-client-status" to "0",
            )
        if (!bearer.isNullOrBlank()) headers["Authorization"] = "Bearer $bearer"
        return headers
    }

    private suspend fun fetchModernPlaybackBearer(): String? {
        return try {
            val response =
                app.get(
                    PLAYBACK_TOKEN_URL,
                    headers = modernPlaybackHeaders(PLAYBACK_TOKEN_URL),
                    timeout = 8,
                    allowRedirects = false,
                )

            val rawXUser = response.headers["x-user"]
            if (rawXUser.isNullOrBlank()) {
                Log.e(TAG, "[PLAYBACK-AUTH] x-user token header kosong HTTP=${response.code}")
                null
            } else {
                val token = JSONObject(rawXUser).optString("token", "")
                if (token.isBlank()) {
                    Log.e(TAG, "[PLAYBACK-AUTH] token anonymous kosong HTTP=${response.code}")
                    null
                } else {
                    token
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                TAG,
                "[PLAYBACK-AUTH] gagal mengambil token anonymous: " + "${e.javaClass.simpleName}",
            )
            null
        }
    }

    private fun decodeEdgeUrlPrefix(signCookie: String): String? {
        val encoded =
            Regex("""Edge-Cache-Cookie=urlprefix=([^:;\s]+)""", RegexOption.IGNORE_CASE)
                .find(signCookie)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() } ?: return null

        return try {
            val normalized =
                encoded.replace('_', '/').replace('-', '+').let {
                    it + "=".repeat((4 - it.length % 4) % 4)
                }

            val prefix =
                String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8).trimEnd('/')

            "$prefix/index.mpd"
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeCloudFrontPolicyResource(signCookie: String): String? {
        val encoded =
            Regex("""CloudFront-Policy=([^;]+)""", RegexOption.IGNORE_CASE)
                .find(signCookie)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() } ?: return null

        return try {
            val normalized =
                encoded.replace('-', '+').replace('~', '/').replace('_', '=').let {
                    it + "=".repeat((4 - it.length % 4) % 4)
                }

            val policyJson = String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)

            val resource =
                JSONObject(policyJson)
                    .optJSONArray("Statement")
                    ?.optJSONObject(0)
                    ?.optString("Resource")
                    ?.trim()
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: return null

            val uri = URI(resource)
            val path = uri.rawPath.trimEnd('*').trimEnd('/')
            val mediaPath =
                if (
                    path.endsWith(".mpd", true) ||
                        path.endsWith(".m3u8", true) ||
                        path.endsWith(".mp4", true)
                )
                    path
                else "$path/index.mpd"
            "${uri.scheme}://${uri.rawAuthority}$mediaPath" + (uri.rawQuery?.let { "?$it" } ?: "")
        } catch (_: Exception) {
            null
        }
    }

    private fun extractPlaybackPolicyResource(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        return decodeEdgeUrlPrefix(signCookie) ?: decodeCloudFrontPolicyResource(signCookie)
    }

    // ---------------------------------------------------------------
    // Struktur response sudah terbukti dari server, jadi parser mengikuti
    // jalurnya secara eksplisit, bukan menelusuri seluruh pohon JSON:
    //
    //   search/v2   -> data.results[].subjects[]
    //   detail-rec  -> data.items[]
    //
    // Keduanya berisi objek Subject yang sama bentuknya.
    // ---------------------------------------------------------------
    private fun subjectToSearchResponse(o: JSONObject): SearchResponse? {
        val subjectId = o.optString("subjectId", "")
        val title = o.optString("title", "")
        if (subjectId.isBlank() || title.isBlank()) return null

        // subjectType 1 = Movie, 2 = TV. Nilai lain (mis. 9 = UGC) dibuang
        // karena tidak bisa diputar lewat play-info.
        val type = o.optInt("subjectType", 1)
        if (type != 1 && type != 2) return null

        val poster = o.optJSONObject("cover")?.optString("url").orEmpty()
        val detailUrl = "$mainUrl/detail?id=$subjectId"

        return if (type == 2) {
            newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, detailUrl, TvType.Movie) { this.posterUrl = poster }
        }
    }

    /** search/v2 : data.results[].subjects[] */
    private fun parseSearchResults(rawJson: String?): List<SearchResponse> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(rawJson)
            val code = root.optInt("code", -1)
            val data = root.optJSONObject("data")
            val results = data?.optJSONArray("results")
            val out = mutableListOf<SearchResponse>()
            val seen = mutableSetOf<String>()
            for (i in 0 until (results?.length() ?: 0)) {
                val subjects = results!!.optJSONObject(i)?.optJSONArray("subjects") ?: continue
                for (j in 0 until subjects.length()) {
                    val obj = subjects.optJSONObject(j) ?: continue
                    if (!seen.add(obj.optString("subjectId", ""))) continue
                    subjectToSearchResponse(obj)?.let { out.add(it) }
                }
            }
            Log.d(TAG, "[SEARCH] code=$code groups=${results?.length() ?: 0} mapped=${out.size}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "[SEARCH] parse gagal: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    /** detail-rec : data.items[] */
    private fun parseRecommendations(rawJson: String?): List<SearchResponse> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(rawJson)
            val code = root.optInt("code", -1)
            val items = root.optJSONObject("data")?.optJSONArray("items")
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until (items?.length() ?: 0)) {
                val obj = items!!.optJSONObject(i) ?: continue
                subjectToSearchResponse(obj)?.let { out.add(it) }
            }
            Log.d(TAG, "[RECOMMEND] code=$code items=${items?.length() ?: 0} mapped=${out.size}")
            out
        } catch (e: Exception) {
            Log.e(TAG, "[RECOMMEND] parse gagal: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    // 1. MAIN PAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data.startsWith(FILTER_PREFIX)) return filterPage(page, request)

        Log.d(TAG, "[CATEGORY] name=${request.name} categoryType=${request.data} page=$page")
        val bearerToken = getBearerToken() ?: return null
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "categoryType=${request.data}&page=$page&perPage=10&tabId=0"

        val response =
            app.get(
                "$mainUrl$path?$query",
                headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), bearerToken),
            )

        val jsonRes = response.parsedSafe<RankingResponse>() ?: return null
        val dataObj = jsonRes.data ?: return null

        // Daftar kategori terbaru ikut menumpang di setiap respons. Nol request tambahan.
        dataObj.categoryList?.let { rememberCategories(it) }

        val homeItems =
            dataObj.subjects?.mapNotNull { item ->
                val subjectId = item.subjectId ?: return@mapNotNull null
                val title = item.title ?: "Unknown"
                val posterUrl = item.cover?.url ?: ""
                val detailUrl = "$mainUrl/detail?id=$subjectId"

                if ((item.subjectType ?: 1) == 2) {
                    newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newMovieSearchResponse(title, detailUrl, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                }
            } ?: emptyList()

        val first = dataObj.subjects?.firstOrNull()
        Log.d(
            TAG,
            "[CATEGORY] name=${request.name} HTTP=${response.code} " +
                "subjects=${homeItems.size} firstSubjectId=${first?.subjectId} " +
                "firstTitle=${first?.title}",
        )

        return newHomePageResponse(request.name, homeItems)
    }

    private suspend fun filterPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val spec = request.data.removePrefix(FILTER_PREFIX)
        Log.d(TAG, "[CATEGORY] name=${request.name} filter=$spec page=$page")

        val bearerToken = getBearerToken() ?: return null

        val body = JSONObject().put("page", page).put("perPage", 10)
        spec.split("&").forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                val v = kv[1]
                if (v.toIntOrNull() != null) body.put(kv[0], v.toInt()) else body.put(kv[0], v)
            }
        }

        val raw =
            postSigned("/wefeed-mobile-bff/subject-api/list", body.toString(), bearerToken)
                ?: return null

        val items =
            try {
                JSONObject(raw).optJSONObject("data")?.optJSONArray("items")
            } catch (e: Exception) {
                Log.e(TAG, "[CATEGORY] parse filter gagal: ${e.javaClass.simpleName}: ${e.message}")
                null
            }

        val out = mutableListOf<SearchResponse>()
        for (i in 0 until (items?.length() ?: 0)) {
            val obj = items!!.optJSONObject(i) ?: continue
            subjectToSearchResponse(obj)?.let { out.add(it) }
        }

        Log.d(
            TAG,
            "[CATEGORY] name=${request.name} items=${items?.length() ?: 0} mapped=${out.size} " +
                "firstTitle=${items?.optJSONObject(0)?.optString("title")}",
        )

        return newHomePageResponse(request.name, out)
    }

    // 2. SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "[SEARCH] keyword=$query")
        return coroutineScope {
            val native = async {
                withTimeoutOrNull(5000) {
                        try {
                            val bearerToken =
                                getBearerToken()
                                    ?: return@withTimeoutOrNull emptyList<SearchResponse>()
                            val body =
                                JSONObject()
                                    .put("page", 1)
                                    .put("perPage", 10)
                                    .put("keyword", query)
                                    .put("tabId", "")
                                    .toString()
                            parseSearchResults(
                                postSigned(
                                    "/wefeed-mobile-bff/subject-api/search/v2",
                                    body,
                                    bearerToken,
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    .orEmpty()
            }
            val public =
                withTimeoutOrNull(6000) {
                        try {
                            val url =
                                "https://moviebox.ph/web/searchResult?keyword=${URLEncoder.encode(query.trim().take(200),"UTF-8")}"
                            val response =
                                playbackRequest(
                                    url,
                                    mapOf(
                                        "User-Agent" to MovieboxPlayback.WEB_UA,
                                        "Referer" to "https://moviebox.ph/",
                                    ),
                                )
                            response
                                ?.takeIf { it.first == 200 }
                                ?.second
                                ?.let { playbackContext.catalogSubjects(it) }
                                .orEmpty()
                                .mapNotNull(::subjectToSearchResponse)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    .orEmpty()
            (native.await() + public)
                .distinctBy { it.url }
                .also { Log.d(TAG, "[SEARCH] returning=${it.size}") }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    data class EpData(
        val subjectId: String,
        val se: Int,
        val ep: Int,
        val subjectType: Int = 1,
        val title: String? = null,
        val detailPath: String? = null,
    )

    private suspend fun fetchRecommendations(
        subjectId: String,
        bearer: String?,
    ): List<SearchResponse> {
        Log.d(TAG, "[RECOMMEND] subjectId=$subjectId")
        val body =
            JSONObject().put("subjectId", subjectId).put("page", 1).put("perPage", 6).toString()
        val raw = postSigned("/wefeed-mobile-bff/subject-api/detail-rec", body, bearer)
        val out =
            parseRecommendations(raw).filterNot {
                it.url.substringAfter("id=").substringBefore("&") == subjectId
            }
        Log.d(TAG, "[RECOMMEND] returning=${out.size}")
        return out
    }

    // 3. LOAD
    override suspend fun load(url: String): LoadResponse? {
        val cleanId =
            when {
                url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
                url.contains("/") -> url.substringAfterLast("/").substringBefore("?")
                else -> url.trim()
            }

        val bearerToken = getBearerToken() ?: return null

        val ts = System.currentTimeMillis().toString()
        val pathGet = "/wefeed-mobile-bff/subject-api/get"
        val queryGet = "subjectId=$cleanId"

        val responseGet =
            app.get(
                "$mainUrl$pathGet?$queryGet",
                headers =
                    headersFor(ts, generateSignature("GET", "$pathGet?$queryGet", ts), bearerToken),
            )

        val detailRes = responseGet.parsedSafe<SubjectDetailResponse>()
        val subject = detailRes?.data ?: return null
        if (!subject.subjectId.isNullOrBlank() && subject.subjectId != cleanId) return null
        val contextMetadata = MovieboxPlayback.metadata(responseGet.text, cleanId)
        val selectedPath =
            contextMetadata
                ?.takeIf { MovieboxPlayback.text(it, "subjectId") == cleanId }
                ?.let { playbackContext.knownPath(it, cleanId) }

        val displayTitle = subject.title ?: "MovieBox Content"
        val poster = subject.cover?.url
        val typeInt = subject.subjectType ?: 1
        val description = subject.description
        val yearInt = subject.releaseDate?.take(4)?.toIntOrNull()
        val ratingStr = subject.imdbRatingValue ?: subject.imdbRate

        val trailerUrl =
            sequenceOf(
                    subject.trailer?.videoAddressUpper?.url,
                    subject.trailer?.videoAddressLower?.url,
                )
                .filterNotNull()
                .firstOrNull { MovieboxPlayback.http(it) && !MovieboxPlayback.notice(it) }

        val genreTags = subject.genre?.split(",")?.map { it.trim() } ?: emptyList()

        val castActors =
            subject.staffList?.mapNotNull { staff ->
                val staffName = staff.name ?: return@mapNotNull null
                ActorData(actor = Actor(staffName, staff.avatarUrl), roleString = staff.character)
            } ?: emptyList()

        val recs = fetchRecommendations(cleanId, bearerToken)

        val tsSeason = System.currentTimeMillis().toString()
        val pathSeason = "/wefeed-mobile-bff/subject-api/season-info"
        val querySeason = "subjectId=$cleanId"

        val responseSeason =
            app.get(
                "$mainUrl$pathSeason?$querySeason",
                headers =
                    headersFor(
                        tsSeason,
                        generateSignature("GET", "$pathSeason?$querySeason", tsSeason),
                        bearerToken,
                    ),
            )

        val seasonRes = responseSeason.parsedSafe<SeasonInfoResponse>()
        val seasons = seasonRes?.data?.seasons

        val episodesList = mutableListOf<Episode>()

        seasons?.forEach { seasonItem ->
            val seNum = seasonItem.se ?: 1
            val maxEp = seasonItem.maxEp ?: 1

            for (epNum in 1..maxEp) {
                episodesList.add(
                    newEpisode(EpData(cleanId, seNum, epNum, typeInt, displayTitle, selectedPath)) {
                        this.name = "Episode $epNum"
                        this.posterUrl = poster
                        this.season = seNum
                        this.episode = epNum
                    }
                )
            }
        }

        val isSeries = typeInt == 2 || episodesList.size > 1

        return if (isSeries) {
            if (episodesList.isEmpty()) {
                episodesList.add(
                    newEpisode(EpData(cleanId, 1, 1, 2, displayTitle, selectedPath)) {
                        this.name = "Episode 1"
                        this.posterUrl = poster
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
            newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
                this.year = yearInt
                this.score = Score.from(ratingStr, 10)
                this.actors = castActors
                this.tags = genreTags
                this.recommendations = recs
                if (!trailerUrl.isNullOrBlank()) {
                    this.trailers.add(
                        TrailerData(extractorUrl = trailerUrl, referer = null, raw = true)
                    )
                }
            }
        } else {
            newMovieLoadResponse(
                displayTitle,
                url,
                TvType.Movie,
                EpData(cleanId, 0, 0, 1, displayTitle, selectedPath),
            ) {
                this.posterUrl = poster
                this.plot = description
                this.year = yearInt
                this.score = Score.from(ratingStr, 10)
                this.actors = castActors
                this.tags = genreTags
                this.recommendations = recs
                if (!trailerUrl.isNullOrBlank()) {
                    this.trailers.add(
                        TrailerData(extractorUrl = trailerUrl, referer = null, raw = true)
                    )
                }
            }
        }
    }

    // Media redirects and segment URLs must not inherit credentials outside this resource.
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        val scope = extractorLink.url
        val mediaClient =
            MovieboxPlayback.client
                .newBuilder()
                .followRedirects(true)
                .followSslRedirects(false)
                .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    val permitted =
                        MovieboxPlayback.scopeHeaders(
                            extractorLink.headers,
                            scope,
                            request.url.toString(),
                        )
                    val builder = request.newBuilder()
                    extractorLink.headers.keys
                        .filter { key -> key !in permitted }
                        .forEach { builder.removeHeader(it) }
                    // No API bearer ever belongs on a media request.
                    builder.removeHeader("Authorization")
                    if (
                        MovieboxPlayback.scopeHeaders(
                                mapOf("Cookie" to "scope-check"),
                                scope,
                                request.url.toString(),
                            )["Cookie"] == null
                    ) {
                        builder.removeHeader("Cookie").removeHeader("X-MB-Token")
                    }
                    chain.proceed(builder.build())
                }
                .build()
        return Interceptor { chain -> mediaClient.newCall(chain.request()).execute() }
    }

    /** Do not log response bodies, full signed URLs, cookies or bearer values. */
    private suspend fun playbackRequest(
        url: String,
        headers: Map<String, String>,
    ): Pair<Int, String>? =
        try {
            val response = app.get(url, headers = headers, timeout = 8, allowRedirects = false)
            Log.d(TAG, "[PLAYBACK] ${URI(url).host}${URI(url).path} HTTP=${response.code}")
            response.code to response.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "[PLAYBACK] ${URI(url).host}${URI(url).path} ${e.javaClass.simpleName}")
            null
        }

    private data class PlaybackChoice(
        val stream: MovieboxPlayback.Stream,
        val headers: Map<String, String>,
        val referer: String,
        val format: MovieboxPlayback.Format,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val selected = AppUtils.tryParseJson<EpData>(data) ?: return false
        if (!selected.subjectId.matches(Regex("[0-9]+")) || selected.se < 0 || selected.ep < 0)
            return false
        // Movie 0/0; serial EXACTLY the requested season/episode. Never retry a different episode.
        val se = if (selected.subjectType == 1) 0 else selected.se
        val ep = if (selected.subjectType == 1) 0 else selected.ep
        val api = mainUrl.trimEnd('/')
        // Mobile API remains metadata/caption authentication only; media is exclusively H5.
        var modernBearer = fetchModernPlaybackBearer()
        var normalBearer: String? = null
        var legacyBootstrapAttempted = false
        val choices = linkedMapOf<String, PlaybackChoice>()
        val captionIds = linkedMapOf<Pair<String, String>, String>()
        val subtitleUrls = linkedSetOf<String>()
        val validatedUrls = mutableMapOf<String, MovieboxPlayback.Format>()
        val failedUrls = mutableSetOf<String>()

        suspend fun legacyBearer(): String? {
            if (!legacyBootstrapAttempted) {
                legacyBootstrapAttempted = true
                normalBearer =
                    try {
                        withTimeoutOrNull(8000) { getBearerToken() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
            }
            return normalBearer
        }
        fun webHeaders(host: String, referer: String): Map<String, String> =
            mutableMapOf(
                    "User-Agent" to MovieboxPlayback.WEB_UA,
                    "Referer" to referer,
                    "Accept" to "application/json",
                    "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                    "x-request-lang" to "en",
                    "x-source" to "",
                )
                .apply {
                    // API bearer is never forwarded to third-party web aliases or media.
                    if (host == "https://h5-api.aoneroom.com" && modernBearer != null)
                        put("Authorization", "Bearer $modernBearer")
                }
        suspend fun metadata(subject: String): JSONObject? {
            val url = "$api/wefeed-mobile-bff/subject-api/get?subjectId=$subject"
            var response = playbackRequest(url, modernPlaybackHeaders(url, modernBearer))
            if (response?.first in listOf(401, 441)) {
                modernBearer = fetchModernPlaybackBearer()
                response = playbackRequest(url, modernPlaybackHeaders(url, modernBearer))
            }
            MovieboxPlayback.metadata(response?.takeIf { it.first == 200 }?.second, subject)?.let {
                return it
            }
            // Preserve the established catalog session as a metadata fallback. Do not request
            // play-info.
            val bearer = legacyBearer()
            if (bearer != null) {
                val ts = System.currentTimeMillis().toString()
                val path = URI(url).let { it.rawPath + "?" + it.rawQuery }
                response =
                    playbackRequest(url, headersFor(ts, generateSignature("GET", path, ts), bearer))
                MovieboxPlayback.metadata(response?.takeIf { it.first == 200 }?.second, subject)
                    ?.let {
                        return it
                    }
            }
            // Public H5 detail uses the same exact subject identity; never search/fuzzy-match
            // another title.
            val host = MovieboxPlayback.webDomains.first()
            val webUrl = "$host/wefeed-h5api-bff/detail?subjectId=$subject"
            val web = playbackRequest(webUrl, webHeaders(host, "$host/"))
            return MovieboxPlayback.metadata(web?.takeIf { it.first == 200 }?.second, subject)
        }

        suspend fun collectStreams(
            raw: String,
            subject: String,
            origin: String,
            referer: String,
            audio: String,
            originalLanguage: String,
        ): Int {
            val parsed =
                try {
                    MovieboxPlayback.parse(raw, "H5", subject, se, ep, audio, originalLanguage)
                } catch (_: Exception) {
                    emptyList()
                }
            // Policy resources are media candidates, not authorization to strip signed query
            // parameters.
            val candidates =
                parsed
                    .filter { it.category != MovieboxPlayback.AudioCategory.UNSUPPORTED }
                    .flatMap { stream ->
                        val policy = extractPlaybackPolicyResource(stream.cookie)
                        if (
                            policy != null && MovieboxPlayback.http(policy) && policy != stream.url
                        ) {
                            listOf(
                                stream.copy(url = policy, format = "DASH"),
                                stream.copy(
                                    cookie =
                                        if (
                                            MovieboxPlayback.scopeHeaders(
                                                    mapOf("Cookie" to "scope-check"),
                                                    policy,
                                                    stream.url,
                                                )["Cookie"] != null
                                        )
                                            stream.cookie
                                        else null
                                ),
                            )
                        } else listOf(stream)
                    }
                    .filterNot { MovieboxPlayback.notice(it.url) }
                    .distinctBy { MovieboxPlayback.requestKey(it) }
                    .sortedWith(
                        compareBy<MovieboxPlayback.Stream> { it.codec.contains("hevc", true) }
                            .thenBy { it.format.equals("DASH", true) }
                            .thenByDescending { it.quality ?: 0 }
                    )
            val semaphore = Semaphore(3)
            val checked = coroutineScope {
                candidates
                    .map { stream ->
                        async {
                            semaphore.withPermit {
                                val headers =
                                    MovieboxPlayback.mediaHeaders(
                                        stream,
                                        origin,
                                        referer,
                                        MovieboxPlayback.WEB_UA,
                                    )
                                val cacheKey = stream.url + "|" + headers.toSortedMap().toString()
                                val valid =
                                    validatedUrls[cacheKey]?.let { MovieboxPlayback.Validated(it) }
                                        ?: if (cacheKey in failedUrls) null
                                        else
                                            try {
                                                MovieboxPlayback.validate(stream, headers)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                null
                                            }
                                Triple(stream, headers, valid)
                            }
                        }
                    }
                    .map { it.await() }
            }
            var count = 0
            for ((stream, headers, valid) in checked) {
                val cacheKey = stream.url + "|" + headers.toSortedMap().toString()
                if (valid == null) {
                    failedUrls += cacheKey
                    continue
                }
                validatedUrls[cacheKey] = valid.format
                val captionKey = subject to stream.id
                if (stream.id.isNotBlank() && captionKey !in captionIds)
                    captionIds[captionKey] = stream.audio
                val key = MovieboxPlayback.key(stream)
                if (key in choices) continue
                choices[key] = PlaybackChoice(stream, headers, referer, valid.format)
                count++
            }
            Log.d(
                TAG,
                "[PLAYBACK-H5] subject=$subject parsed=${parsed.size} audioFiltered=${parsed.count { it.category == MovieboxPlayback.AudioCategory.UNSUPPORTED }} candidates=${candidates.size} validated=$count se=$se ep=$ep",
            )
            return count
        }
        fun query(subject: String) = "subjectId=$subject&se=$se&ep=$ep"
        // Detail is read solely for H5 paths and explicitly declared audio subjects.
        val detail = metadata(selected.subjectId)
        val subjects = mutableListOf(Triple(selected.subjectId, "", detail))
        // Same-ID server metadata can identify the selected subject's original language.
        val originalLanguage =
            MovieboxPlayback.audioLanguages(detail ?: JSONObject()).firstOrNull()
                ?: MovieboxPlayback.objects(detail?.optJSONArray("dubs"))
                    .firstOrNull { MovieboxPlayback.text(it, "subjectId") == selected.subjectId }
                    ?.let { MovieboxPlayback.audioLanguages(it).firstOrNull() }
                    .orEmpty()
        MovieboxPlayback.objects(detail?.optJSONArray("dubs")).forEach { dub ->
            val sid = MovieboxPlayback.text(dub, "subjectId")
            val category = MovieboxPlayback.dubCategory(dub)
            if (
                category != MovieboxPlayback.AudioCategory.UNSUPPORTED &&
                    sid.matches(Regex("[0-9]+")) &&
                    subjects.none { it.first == sid }
            )
                subjects += Triple(sid, MovieboxPlayback.audioLanguages(dub).first(), dub)
        }
        val orderedSubjects =
            subjects.sortedWith(
                compareBy<Triple<String, String, JSONObject?>> {
                        if (it.first == selected.subjectId) -1
                        else MovieboxPlayback.audioOrder(it.second)
                    }
                    .thenBy { MovieboxPlayback.audioName(it.second) }
            )
        var preferredHost: String? = null
        for ((subject, audio, initialDetail) in orderedSubjects) {
            val meta = if (subject == selected.subjectId) initialDetail else metadata(subject)
            val detail = meta ?: initialDetail ?: JSONObject()
            val path =
                playbackContext.knownPath(detail, subject).orEmpty().ifBlank {
                    playbackContext.resolve(
                        subject,
                        selected.subjectId,
                        selected.title,
                        selected.detailPath,
                        detail,
                    ) { url ->
                        playbackRequest(
                                url,
                                mapOf(
                                    "User-Agent" to MovieboxPlayback.WEB_UA,
                                    "Referer" to "https://moviebox.ph/",
                                ),
                            )
                            ?.takeIf { it.first == 200 }
                            ?.second
                    }
                }
            Log.d(
                TAG,
                "[PLAYBACK-H5] subject=$subject metadata=${meta != null} detailPathAvailable=${path.isNotBlank()} se=$se ep=$ep",
            )
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val webDetail = MovieboxPlayback.text(detail, "detailUrl")
            val fromDetail =
                if (MovieboxPlayback.http(webDetail))
                    URI(webDetail).let { "${it.scheme}://${it.host}" }
                else null
            val hosts =
                (listOfNotNull(
                        preferredHost,
                        fromDetail?.takeIf { it in MovieboxPlayback.webDomains },
                    ) + MovieboxPlayback.webDomains)
                    .distinct()
            // A failed/empty alias must not hide H5 sources on later aliases, including dubbed
            // subjects.
            for (host in hosts) {
                val url =
                    "$host/wefeed-h5api-bff/subject/play?${query(subject)}&detailPath=$encodedPath" +
                        "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                val referer =
                    "$host/movies/$encodedPath?id=$subject&type=/movie/detail&detailSe=&detailEp=&lang=en"
                val response = playbackRequest(url, webHeaders(host, referer)) ?: continue
                if (
                    response.first == 200 &&
                        collectStreams(
                            response.second,
                            subject,
                            host,
                            referer,
                            audio,
                            if (subject == selected.subjectId) originalLanguage else "",
                        ) > 0
                ) {
                    preferredHost = host
                    break
                }
            }
        }
        // Global, deterministic labels: one audio name, one media format, one codec.
        // CloudStream adds the quality itself; do not repeat "1080p" in the source name.
        val orderedChoices =
            choices.values
                .filter { it.stream.category != MovieboxPlayback.AudioCategory.UNSUPPORTED }
                .sortedWith(
                    compareBy<PlaybackChoice> { it.stream.category.ordinal }
                        .thenByDescending { it.stream.quality ?: 0 }
                        .thenBy { MovieboxPlayback.codecOrder(it.stream.codec) }
                        .thenBy { it.format.ordinal }
                )
        val labelCounts = mutableMapOf<String, Int>()
        for (choice in orderedChoices) {
            val stream = choice.stream
            val label = MovieboxPlayback.label(stream, choice.format)
            val group = "$label|${stream.quality}"
            val number = (labelCounts[group] ?: 0) + 1
            labelCounts[group] = number
            val finalLabel = label + if (number > 1) " • Server $number" else ""
            val type =
                when (choice.format) {
                    MovieboxPlayback.Format.DASH -> ExtractorLinkType.DASH
                    MovieboxPlayback.Format.HLS -> ExtractorLinkType.M3U8
                    MovieboxPlayback.Format.VIDEO -> ExtractorLinkType.VIDEO
                }
            callback(
                newExtractorLink(name, finalLabel, stream.url, type) {
                    this.headers = choice.headers
                    this.referer = choice.referer
                    stream.quality?.let { this.quality = it }
                }
            )
        }
        // Each validated stream/resource ID owns its captions; the selected serial index is
        // unchanged.
        for ((identity, audio) in captionIds) {
            val (subject, stream) = identity
            val params =
                listOf(
                    "get-stream-captions" to
                        "streamId=${URLEncoder.encode(stream,"UTF-8")}&subjectId=$subject",
                    "get-ext-captions" to
                        "episode=0&resourceId=${URLEncoder.encode(stream,"UTF-8")}&subjectId=$subject",
                )
            for ((endpoint, q) in params) {
                val url = "$api/wefeed-mobile-bff/subject-api/$endpoint?$q"
                var response = playbackRequest(url, modernPlaybackHeaders(url, modernBearer))
                if (response == null || response.first != 200) {
                    val bearer = legacyBearer()
                    if (bearer != null) {
                        val ts = System.currentTimeMillis().toString()
                        val pathQuery = URI(url).let { it.rawPath + "?" + it.rawQuery }
                        response =
                            playbackRequest(
                                url,
                                headersFor(ts, generateSignature("GET", pathQuery, ts), bearer),
                            )
                    }
                }
                if (response == null || response.first != 200) continue
                try {
                    MovieboxPlayback.captionEntries(response.second).forEach { (language, url) ->
                        if (subtitleUrls.add(url))
                            subtitleCallback(
                                newSubtitleFile(
                                    language +
                                        audio
                                            .takeIf { it.isNotBlank() }
                                            ?.let { " (${MovieboxPlayback.audioName(it)})" }
                                            .orEmpty(),
                                    url,
                                )
                            )
                    }
                } catch (_: Exception) {
                    Log.d(TAG, "[SUBTITLE] invalid $endpoint response")
                }
            }
        }
        if (choices.isEmpty())
            Log.e(
                TAG,
                "[PLAYBACK-H5] no validated resource subject=${selected.subjectId} se=$se ep=$ep",
            )
        return orderedChoices.isNotEmpty()
    }

    data class RankingResponse(val code: Int?, val data: RankingData?)

    data class RankingData(val categoryList: List<CategoryItem>?, val subjects: List<SubjectItem>?)

    data class CategoryItem(val name: String?, val type: String?)

    data class SubjectDetailResponse(val code: Int?, val data: SubjectDetailItem?)

    data class SubjectDetailItem(
        val subjectId: String?,
        val title: String?,
        val cover: CoverItem?,
        val subjectType: Int?,
        val description: String?,
        val releaseDate: String?,
        val imdbRatingValue: String?,
        val imdbRate: String?,
        val genre: String?,
        val staffList: List<StaffItem>?,
        val trailer: TrailerItem?,
    )

    data class SubjectItem(
        val subjectId: String?,
        val title: String?,
        val cover: CoverItem?,
        val subjectType: Int?,
    )

    data class CoverItem(val url: String?)

    data class StaffItem(val name: String?, val character: String?, val avatarUrl: String?)

    data class TrailerItem(
        @JsonProperty("VideoAddress") val videoAddressUpper: VideoAddressItem? = null,
        @JsonProperty("videoAddress") val videoAddressLower: VideoAddressItem? = null,
    )

    data class VideoAddressItem(val url: String?)

    data class SeasonInfoResponse(val code: Int?, val data: SeasonInfoData?)

    data class SeasonInfoData(
        val subjectId: String?,
        val subjectType: Int?,
        val seasons: List<SeasonItem>?,
    )

    data class SeasonItem(val se: Int?, val maxEp: Int?)
}

/** Parse H5 streams and validate media before offering them to the player. */
internal object MovieboxPlayback {
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
        val bytes: ByteArray,
        val truncated: Boolean = false,
    )

    data class Validated(val format: Format)

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
                    // Server-issued share paths for titles whose mobile metadata omits the slug.
                    // Keep each fallback tied to its exact subject ID.
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

    fun format(sample: Sample): Format? {
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

    /** Check the manifest and a bounded sample of its media before playback. */
    suspend fun validate(
        s: Stream,
        headers: Map<String, String>,
        fetch: suspend (String, Map<String, String>, Boolean) -> Sample? = { u, h, m ->
            sample(u, h, s.url, m)
        },
    ): Validated? {
        val first = fetch(s.url, headers, false) ?: return null
        val type = format(first) ?: return null
        if (type == Format.VIDEO) return Validated(type)
        val manifest = fetch(s.url, headers, true) ?: return null
        if (manifest.truncated || format(manifest) != type) return null
        val ok =
            if (type == Format.HLS) hls(manifest, headers, fetch, 0)
            else dash(manifest, headers, fetch)
        return if (ok) Validated(type) else null
    }

    private suspend fun binary(
        url: String,
        headers: Map<String, String>,
        fetch: suspend (String, Map<String, String>, Boolean) -> Sample?,
    ): Boolean {
        val r = fetch(url, headers, false) ?: return false
        return format(r) == Format.VIDEO ||
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

/** H5 navigation context only. Never resolves media or substitutes another subject. */
internal class MovieboxPlaybackContext(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val budgetMs: Long = 6000,
) {
    private val paths = linkedMapOf<String, Pair<Long, String>>()
    private val allowedHosts =
        MovieboxPlayback.webDomains.mapNotNull { URI(it).host }.toSet() + "moviebox.ph"

    fun path(value: String?, subject: String): String? {
        if (value.isNullOrBlank()) return null
        val clean = value.trim()
        if (clean.matches(Regex("[A-Za-z0-9_-]{1,220}")))
            return clean.takeUnless {
                it in setOf("detail", "movies", "moviedetail", "download-app")
            }
        return try {
            val uri = URI(clean)
            if (uri.rawAuthority != null && !uri.isAbsolute) return null
            if (
                uri.isAbsolute &&
                    (uri.scheme != "https" ||
                        uri.host !in allowedHosts ||
                        uri.userInfo != null ||
                        uri.port !in listOf(-1, 443))
            )
                return null
            if (uri.rawFragment != null) return null
            val declared =
                uri.rawQuery
                    .orEmpty()
                    .split('&')
                    .filter { it.substringBefore('=') == "id" }
                    .map { it.substringAfter('=', "") }
            if (declared.any { it != subject }) return null
            uri.path.orEmpty().trimEnd('/').substringAfterLast('/').takeIf {
                it.matches(Regex("[A-Za-z0-9_-]{1,220}")) &&
                    it !in setOf("detail", "movies", "moviedetail", "download-app")
            }
        } catch (_: Exception) {
            null
        }
    }

    fun knownPath(metadata: JSONObject, subject: String): String? {
        val declared = MovieboxPlayback.text(metadata, "subjectId")
        if (declared.isNotBlank() && declared != subject) return null
        val explicit =
            MovieboxPlayback.text(metadata, "detailPath").ifBlank {
                MovieboxPlayback.text(metadata, "detailUrl")
            }
        // A native /detail?id=... route is not an H5 content slug. Validate the
        // unmodified URL before extracting its path or dropping its ID query.
        return if (explicit.isNotBlank()) path(explicit, subject)
        else path(MovieboxPlayback.detailPath(metadata, subject), subject)
    }

    /** Only direct fields from the same Nuxt subject object are read; JS is never run. */
    fun catalogPath(html: String, subject: String): String? =
        try {
            if (html.length > 2_000_000) null
            else {
                val node =
                    Jsoup.parse(html).selectFirst("script#__NUXT_DATA__[type=application/json]")
                val table = JSONArray(node?.data() ?: "[]")
                fun field(row: JSONObject, key: String): String {
                    val index = row.opt(key) as? Number ?: return ""
                    val i = index.toLong()
                    if (i < 0 || i >= table.length().toLong() || i.toDouble() != index.toDouble())
                        return ""
                    val value = table.opt(i.toInt())
                    return if (value is String || value is Number) value.toString() else ""
                }
                (0 until minOf(table.length(), 30_000))
                    .mapNotNull { i ->
                        val row = table.optJSONObject(i) ?: return@mapNotNull null
                        if (field(row, "subjectId") != subject) return@mapNotNull null
                        path(field(row, "detailPath").ifBlank { field(row, "detailUrl") }, subject)
                    }
                    .distinct()
                    .singleOrNull()
            }
        } catch (_: Exception) {
            null
        }

    /**
     * The public catalog searches localized titles server-side. Preserve its IDs and canonical
     * titles; a translated query is never treated as an identity alias.
     */
    fun catalogSubjects(html: String): List<JSONObject> =
        try {
            if (html.length > 2_000_000) emptyList()
            else {
                val script =
                    Jsoup.parse(html).selectFirst("script#__NUXT_DATA__[type=application/json]")
                val table = JSONArray(script?.data() ?: "[]")
                fun value(row: JSONObject, key: String): Any? {
                    val n = row.opt(key) as? Number ?: return null
                    val i = n.toLong()
                    if (i < 0 || i >= table.length() || i.toDouble() != n.toDouble()) return null
                    return table.opt(i.toInt())
                }
                (0 until minOf(table.length(), 30_000))
                    .mapNotNull { i ->
                        val row = table.optJSONObject(i) ?: return@mapNotNull null
                        val id = value(row, "subjectId")?.toString().orEmpty()
                        val title = value(row, "title") as? String ?: return@mapNotNull null
                        val type =
                            (value(row, "subjectType") as? Number)
                                ?.takeIf { it.toDouble() in listOf(1.0, 2.0) }
                                ?.toInt()
                        if (
                            !id.matches(Regex("[0-9]{1,24}")) ||
                                title.isBlank() ||
                                type !in listOf(1, 2)
                        )
                            return@mapNotNull null
                        val cover = value(row, "cover") as? JSONObject
                        val poster =
                            cover
                                ?.let { value(it, "url") as? String }
                                ?.takeIf { MovieboxPlayback.http(it) }
                        JSONObject()
                            .put("subjectId", id)
                            .put("title", title)
                            .put("subjectType", type)
                            .put("cover", JSONObject().put("url", poster.orEmpty()))
                    }
                    .distinctBy { it.getString("subjectId") }
                    .take(30)
            }
        } catch (_: Exception) {
            emptyList()
        }

    suspend fun resolve(
        subject: String,
        selectedSubject: String,
        selectedTitle: String?,
        selectedPath: String?,
        metadata: JSONObject,
        read: suspend (String) -> String?,
    ): String {
        if (!subject.matches(Regex("[0-9]{1,24}"))) return ""
        val declared = MovieboxPlayback.text(metadata, "subjectId")
        if (declared.isNotBlank() && declared != subject) return ""
        if (subject == selectedSubject)
            path(selectedPath, subject)?.let {
                return it
            }
        val now = clock()
        synchronized(paths) {
            paths[subject]
                ?.takeIf { now - it.first in 0 until 60_000 }
                ?.let {
                    return it.second
                }
        }
        // Native and saved display titles can differ by language. Search both, but
        // only the exact selected subject may supply a navigation path.
        val titles =
            (listOf("title", "originalTitle").map { MovieboxPlayback.text(metadata, it) } +
                    listOfNotNull(selectedTitle.takeIf { subject == selectedSubject }))
                .map { it.trim().take(200) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)
        if (titles.isEmpty()) return ""
        val found =
            try {
                withTimeoutOrNull(budgetMs) {
                    for (title in titles) {
                        val url =
                            "https://moviebox.ph/web/searchResult?keyword=${URLEncoder.encode(title,"UTF-8")}"
                        val html =
                            try {
                                read(url)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        html
                            ?.let { catalogPath(it, subject) }
                            ?.let {
                                return@withTimeoutOrNull it
                            }
                    }
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return ""
        synchronized(paths) {
            if (paths.size >= 64) paths.remove(paths.keys.first())
            paths[subject] = (clock()) to found
        }
        return found
    }
}
