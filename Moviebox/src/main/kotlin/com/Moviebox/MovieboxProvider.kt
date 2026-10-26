package com.Moviebox

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
                                    validatedUrls[cacheKey]?.let {
                                        MovieboxPlayback.Validated(stream, it)
                                    }
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

    data class StaffItem(
        val staffId: String?,
        val name: String?,
        val character: String?,
        val avatarUrl: String?,
    )

    data class TrailerItem(
        @JsonProperty("VideoAddress") val videoAddressUpper: VideoAddressItem? = null,
        @JsonProperty("videoAddress") val videoAddressLower: VideoAddressItem? = null,
    )

    data class VideoAddressItem(
        val url: String?,
        val definition: String? = null,
        val duration: Int? = null,
    )

    data class SeasonInfoResponse(val code: Int?, val data: SeasonInfoData?)

    data class SeasonInfoData(
        val subjectId: String?,
        val subjectType: Int?,
        val seasons: List<SeasonItem>?,
    )

    data class SeasonItem(val se: Int?, val maxEp: Int?)
}
