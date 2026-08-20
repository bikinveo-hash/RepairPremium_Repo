package com.Moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.content.Context
import android.util.Base64
import android.util.Log
import java.util.UUID
import java.net.URLEncoder
import java.net.URLDecoder

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // Disusun dari daftar kategori yang server kirim (lihat mainPageEntries).
    override val mainPage = mainPageOf(*mainPageEntries())

    companion object {
        private const val TAG = "MovieBox"
        private const val CS_USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        /**
         * x-client-info dirakit saat request, bukan konstanta, karena device_id
         * berasal dari identity persisten per-instalasi.
         *
         * Isi field lain TIDAK berubah sedikit pun dari versi sebelumnya.
         * x-client-info tidak ikut ditandatangani (buildCanonical hanya memakai
         * method/accept/content-type/panjang body/ts/md5 body/path), jadi
         * perubahan ini tidak dapat memengaruhi signature.
         */
        private fun clientInfo(): String =
            "{\"package_name\":\"com.community.oneroom\",\"version_name\":\"3.0.13.0325.03\"," +
            "\"version_code\":50020088,\"os\":\"android\",\"os_version\":\"13\"," +
            "\"device_id\":\"${deviceId()}\",\"install_store\":\"ps\"," +
            "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"US\"," +
            "\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

        // ---------------------------------------------------------------
        // KATEGORI HOME  (self-healing)
        //
        // Setiap respons tab/ranking-list membawa data.categoryList lengkap
        // (name + type). Daftar itu disimpan, lalu dipakai menyusun mainPage
        // pada pemuatan berikutnya. Daftar di bawah hanya seed untuk
        // instalasi baru.
        //
        // Server membalas categoryType yang tidak dikenal dengan feed default
        // (HTTP 200, tanpa error), sehingga kategori basi tampil sebagai
        // daftar film yang sama berulang-ulang. Itu yang diperbaiki di sini.
        //
        // Parameter request TIDAK diubah: tabId=0, perPage=10, tanpa
        // rankingListId -- terbukti identik dengan default APK (Ltl/d$a;->a).
        // ---------------------------------------------------------------
        private const val CAT_KEY = "categorylist"

        private val SEED_CATEGORIES = listOf(
            "4809349160627587984" to "Semua",
            "4380734070238626200" to "K-Drama",
            "5283462032510044280" to "Indo Drama",
            "8617025562613270856" to "Anime",
            "5307082080063488480" to "Barat",
            "8624142774394406504" to "C-Drama",
            "1164329479448281992" to "Thai-Drama",
            "5720220657917522824" to "Reality"
        )

        @Volatile private var categories: List<Pair<String, String>> = SEED_CATEGORIES

        // ---------------------------------------------------------------
        // BARIS FILTER  (subject-api/list, bukan tab/ranking-list)
        //
        // "Horror" sudah tidak ada di categoryList server, jadi tidak mungkin
        // didapat lewat ranking-list. Layar Filter APK memakai endpoint lain:
        //
        //   GET  subject-api/filter-items  tl.c->b   (daftar opsi filter)
        //   POST subject-api/list          tl.c->a   @Query(host) @Body()
        //
        // Nilai filter berupa string apa adanya dari filter-items
        // ("Horror", "Indonesia"), bukan id.
        // ---------------------------------------------------------------
        private const val FILTER_PREFIX = "filter:"

        private val EXTRA_ROWS = listOf(
            FILTER_PREFIX + "subjectType=1&genre=Horror&country=Indonesia" to "Horror Indonesia"
        )

        private fun prefs() =
            try { appContext?.getSharedPreferences(ID_PREFS, Context.MODE_PRIVATE) }
            catch (e: Exception) { null }

        /** Dipakai oleh mainPage. Sudah terisi karena attachContext() dipanggil duluan. */
        private fun mainPageEntries(): Array<Pair<String, String>> =
            (categories + EXTRA_ROWS).toTypedArray()

        private fun loadCategories() {
            val parsed = prefs()?.getString(CAT_KEY, null)
                ?.split("\n")
                ?.mapNotNull { line ->
                    val p = line.split("\t")
                    if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) p[0] to p[1] else null
                }
                ?.takeIf { it.isNotEmpty() }
            if (parsed != null) categories = parsed
            Log.d(TAG, "[CATEGORY] dimuat ${categories.size} kategori " +
                    "(${if (parsed != null) "tersimpan" else "seed"}): " +
                    categories.joinToString(", ") { it.second })
        }

        /** Dipanggil dari getMainPage. Menyimpan hanya bila daftar server berubah. */
        private fun rememberCategories(fresh: List<CategoryItem>) {
            val list = fresh.mapNotNull { c ->
                val t = c.type
                val n = c.name
                if (t.isNullOrBlank() || n.isNullOrBlank()) null else t to n
            }
            if (list.isEmpty() || list == categories) return
            categories = list
            try {
                prefs()?.edit()?.putString(
                    CAT_KEY, list.joinToString("\n") { "${it.first}\t${it.second}" }
                )?.apply()
                Log.d(TAG, "[CATEGORY] daftar server berubah -> disimpan ${list.size}: " +
                        list.joinToString(", ") { it.second })
            } catch (e: Exception) {
                Log.e(TAG, "[CATEGORY] gagal menyimpan: ${e.message}")
            }
        }

        // ---------------------------------------------------------------
        // IDENTITY  (meniru Lmh/b;->h pada APK: UUID -> MD5 -> persist)
        //
        //   APK : MMKV("vshow")["apkdeviceid"]      <- Lph/a$a;->d(UUID)
        //   sini: SharedPreferences("moviebox_identity")["apkdeviceid"]
        //
        // md5() yang sudah ada di companion ini identik dengan Lph/a$a;->d:
        // MD5 hex huruf kecil 32 karakter. Cabang Android-ID pada APK sengaja
        // TIDAK ditiru; jalur UUID adalah cabang yang sama yang dipakai APK
        // pada Android modern, dan tidak menyentuh identifier perangkat.
        // ---------------------------------------------------------------
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
            val id = deviceId()
            // Logging sementara: verifikasi stabil setelah restart, lalu boleh dihapus.
            Log.d(TAG, "[IDENTITY] device_id=$id len=${id.length} " +
                    "valid=${ID_FORMAT.matches(id)} persisted=$persisted")
        }

        /**
         * Storage adalah sumber kebenaran. Nilai hanya dibuat sekali, lalu
         * dipakai selamanya. Nilai in-memory hanya dipakai bila storage sedang
         * tidak tersedia, dan akan dipersist pada kesempatan pertama sehingga
         * identity tidak berganti antar-restart.
         */
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
            val step1 = String(Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", Base64.DEFAULT), Charsets.UTF_8)
            Base64.decode(step1, Base64.DEFAULT)
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /**
         * Canonical string mengikuti GatewaySignManager.doSign pada APK resmi.
         *
         * Tujuh baris dipisah "\n":
         *   1. HTTP method (huruf besar)
         *   2. accept
         *   3. content-type
         *   4. panjang body      -> kosong bila tanpa body
         *   5. timestamp
         *   6. md5 hex body      -> kosong bila tanpa body
         *   7. path (+query)
         *
         * Tanpa body, baris 4 dan 6 kosong sehingga hasilnya IDENTIK dengan
         * canonical GET yang selama ini bekerja. Karena itu satu fungsi ini
         * aman dipakai untuk GET maupun POST.
         *
         * Kegagalan search sebelumnya terjadi karena percobaan hanya mengisi
         * SALAH SATU dari baris 4 atau 6, tidak pernah keduanya sekaligus.
         */
        private fun buildCanonical(method: String, pathWithQuery: String, ts: String, body: String): String {
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

        private fun generateSignature(method: String, pathWithQuery: String, ts: String, body: String = ""): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val hmacBytes = mac.doFinal(buildCanonical(method, pathWithQuery, ts, body).toByteArray(Charsets.UTF_8))
            return "$ts|2|${Base64.encodeToString(hmacBytes, Base64.NO_WRAP)}"
        }

        private fun generateGuestToken(ts: String): String = "$ts,${md5(ts.reversed())}"

        private fun enc(str: String?): String =
            if (str.isNullOrBlank()) "" else URLEncoder.encode(str, "UTF-8")

        private fun dec(str: String?): String =
            if (str.isNullOrBlank()) "" else URLDecoder.decode(str, "UTF-8")
    }

    private fun headersFor(ts: String, signature: String, bearer: String?): Map<String, String> {
        val h = mutableMapOf(
            "user-agent" to CS_USER_AGENT,
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to generateGuestToken(ts),
            "x-tr-signature" to signature,
            "x-client-info" to clientInfo(),
            "x-client-status" to "0"
        )
        if (!bearer.isNullOrBlank()) h["authorization"] = "Bearer $bearer"
        return h
    }

    private suspend fun getSigned(path: String, query: String, bearer: String?): String? {
        val ts = System.currentTimeMillis().toString()
        val pathWithQuery = if (query.isBlank()) path else "$path?$query"
        return try {
            app.get(
                "$mainUrl$pathWithQuery",
                headers = headersFor(ts, generateSignature("GET", pathWithQuery, ts), bearer)
            ).text
        } catch (e: Exception) {
            null
        }
    }

    /**
     * POST ber-signature.
     *
     * PENTING: RequestBody dibuat dari ByteArray, bukan String.
     * Overload String pada OkHttp menambahkan "; charset=utf-8" ke media type
     * kalau belum ada, lalu BridgeInterceptor menimpa header Content-Type dari
     * body tersebut. Akibatnya yang DIKIRIM "application/json; charset=utf-8"
     * sedangkan yang DITANDATANGANI "application/json" -> server menolak 407.
     * Overload ByteArray memakai media type apa adanya.
     */
    private suspend fun postSigned(path: String, body: String, bearer: String?): String? {
        val ts = System.currentTimeMillis().toString()
        val sig = generateSignature("POST", path, ts, body)
        return try {
            val res = app.post(
                "$mainUrl$path",
                headers = headersFor(ts, sig, bearer),
                requestBody = body.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json".toMediaTypeOrNull())
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

        val response = app.get(
            "$mainUrl$path?$query",
            headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), null)
        )

        val xUserHeader = response.headers["x-user"] ?: return null
        return """"token"\s*:\s*"([^"]+)"""".toRegex().find(xUserHeader)?.groupValues?.get(1)
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
            newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, detailUrl, TvType.Movie) {
                this.posterUrl = poster
            }
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
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        if (request.data.startsWith(FILTER_PREFIX)) return filterPage(page, request)

        Log.d(TAG, "[CATEGORY] name=${request.name} categoryType=${request.data} page=$page")
        val bearerToken = getBearerToken() ?: return null
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "categoryType=${request.data}&page=$page&perPage=10&tabId=0"

        val response = app.get(
            "$mainUrl$path?$query",
            headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), bearerToken)
        )

        val jsonRes = response.parsedSafe<RankingResponse>() ?: return null
        val dataObj = jsonRes.data ?: return null

        // Daftar kategori terbaru ikut menumpang di setiap respons. Nol request tambahan.
        dataObj.categoryList?.let { rememberCategories(it) }

        val homeItems = dataObj.subjects?.mapNotNull { item ->
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
        Log.d(TAG, "[CATEGORY] name=${request.name} HTTP=${response.code} " +
                "subjects=${homeItems.size} firstSubjectId=${first?.subjectId} " +
                "firstTitle=${first?.title}")

        return newHomePageResponse(request.name, homeItems)
    }

    /**
     * Baris home yang bersumber dari layar Filter APK, bukan dari kategori.
     *
     * request.data berformat "filter:k=v&k=v"; pasangan tersebut dikirim apa
     * adanya sebagai field body POST subject-api/list, ditambah page/perPage.
     * Nama field dan nilainya terbukti dari runtime: body datar menghasilkan
     * 10/10 item bergenre Horror dan bernegara Indonesia, sedangkan body tanpa
     * filter mengembalikan katalog campur termasuk subjectType 6.
     */
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

        val raw = postSigned("/wefeed-mobile-bff/subject-api/list", body.toString(), bearerToken)
            ?: return null

        val items = try {
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

        Log.d(TAG, "[CATEGORY] name=${request.name} items=${items?.length() ?: 0} mapped=${out.size} " +
                "firstTitle=${items?.optJSONObject(0)?.optString("title")}")

        return newHomePageResponse(request.name, out)
    }

    // 2. SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "[SEARCH] keyword=$query")
        val bearerToken = getBearerToken()
        if (bearerToken == null) {
            Log.e(TAG, "[SEARCH] bearer token null")
            return emptyList()
        }
        val body = JSONObject()
            .put("page", 1)
            .put("perPage", 10)
            .put("keyword", query)
            .put("tabId", "")
            .toString()

        val raw = postSigned("/wefeed-mobile-bff/subject-api/search/v2", body, bearerToken)
        val out = parseSearchResults(raw)
        Log.d(TAG, "[SEARCH] returning=${out.size}")
        return out
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    data class EpData(
        val subjectId: String,
        val se: Int,
        val ep: Int,
        val subjectType: Int = 1
    )

    private suspend fun fetchRecommendations(subjectId: String, bearer: String?): List<SearchResponse> {
        Log.d(TAG, "[RECOMMEND] subjectId=$subjectId")
        val body = JSONObject()
            .put("subjectId", subjectId)
            .put("page", 1)
            .put("perPage", 6)
            .toString()
        val raw = postSigned("/wefeed-mobile-bff/subject-api/detail-rec", body, bearer)
        val out = parseRecommendations(raw)
            .filterNot { it.url.substringAfter("id=").substringBefore("&") == subjectId }
        Log.d(TAG, "[RECOMMEND] returning=${out.size}")
        return out
    }

    // 3. LOAD
    override suspend fun load(url: String): LoadResponse? {
        val cleanId = when {
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            url.contains("/") -> url.substringAfterLast("/").substringBefore("?")
            else -> url.trim()
        }

        val bearerToken = getBearerToken() ?: return null

        val ts = System.currentTimeMillis().toString()
        val pathGet = "/wefeed-mobile-bff/subject-api/get"
        val queryGet = "subjectId=$cleanId"

        val responseGet = app.get(
            "$mainUrl$pathGet?$queryGet",
            headers = headersFor(ts, generateSignature("GET", "$pathGet?$queryGet", ts), bearerToken)
        )

        val detailRes = responseGet.parsedSafe<SubjectDetailResponse>()
        val subject = detailRes?.data ?: return null

        val displayTitle = subject.title ?: "MovieBox Content"
        val poster = subject.cover?.url
        val typeInt = subject.subjectType ?: 1
        val description = subject.description
        val yearInt = subject.releaseDate?.take(4)?.toIntOrNull()
        val ratingStr = subject.imdbRatingValue ?: subject.imdbRate

        // TRAILER - sudah berfungsi, tidak diubah.
        val trailerUrl = subject.trailer?.let { it.videoAddressUpper ?: it.videoAddressLower }?.url

        val genreTags = subject.genre?.split(",")?.map { it.trim() } ?: emptyList()

        val castActors = subject.staffList?.mapNotNull { staff ->
            val staffName = staff.name ?: return@mapNotNull null
            ActorData(
                actor = Actor(staffName, staff.avatarUrl),
                roleString = staff.character
            )
        } ?: emptyList()

        val recs = fetchRecommendations(cleanId, bearerToken)

        val tsSeason = System.currentTimeMillis().toString()
        val pathSeason = "/wefeed-mobile-bff/subject-api/season-info"
        val querySeason = "subjectId=$cleanId"

        val responseSeason = app.get(
            "$mainUrl$pathSeason?$querySeason",
            headers = headersFor(tsSeason, generateSignature("GET", "$pathSeason?$querySeason", tsSeason), bearerToken)
        )

        val seasonRes = responseSeason.parsedSafe<SeasonInfoResponse>()
        val seasons = seasonRes?.data?.seasons

        val episodesList = mutableListOf<Episode>()

        seasons?.forEach { seasonItem ->
            val seNum = seasonItem.se ?: 1
            val maxEp = seasonItem.maxEp ?: 1

            for (epNum in 1..maxEp) {
                episodesList.add(
                    newEpisode(EpData(cleanId, seNum, epNum, typeInt)) {
                        this.name = "Episode $epNum"
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
                    newEpisode(EpData(cleanId, 1, 1, 2)) {
                        this.name = "Episode 1"
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
                    this.trailers.add(TrailerData(trailerUrl, mainUrl, true))
                }
            }
        } else {
            newMovieLoadResponse(displayTitle, url, TvType.Movie, EpData(cleanId, 0, 0, 1)) {
                this.posterUrl = poster
                this.plot = description
                this.year = yearInt
                this.score = Score.from(ratingStr, 10)
                this.actors = castActors
                this.tags = genreTags
                this.recommendations = recs
                if (!trailerUrl.isNullOrBlank()) {
                    this.trailers.add(TrailerData(trailerUrl, mainUrl, true))
                }
            }
        }
    }

    // 4. INTERCEPTOR COOKIE EXOPLAYER
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val cookie = extractorLink.headers["Cookie"]
            if (!cookie.isNullOrBlank()) {
                val newRequest = request.newBuilder()
                    .header("Cookie", cookie)
                    .header("User-Agent", CS_USER_AGENT)
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
    }

    // SUBTITLE
    // Struktur terbukti dari server:
    //   data.extCaptions[] { id, lan, lanName, url, size, delay }
    // URL berakhiran .srt dengan query Policy/Signature -> dipakai apa adanya.
    private suspend fun loadSubtitles(
        subjectId: String,
        streamId: String?,
        bearer: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (streamId.isNullOrBlank()) return
        // parameter diurutkan alfabetis, sama seperti endpoint lain yang bekerja
        val raw = getSigned(
            "/wefeed-mobile-bff/subject-api/get-stream-captions",
            "streamId=$streamId&subjectId=$subjectId",
            bearer
        ) ?: return
        try {
            val caps = JSONObject(raw).optJSONObject("data")?.optJSONArray("extCaptions")
            var sent = 0
            for (i in 0 until (caps?.length() ?: 0)) {
                val c = caps!!.optJSONObject(i) ?: continue
                val url = c.optString("url", "")
                if (url.isBlank()) continue
                val label = c.optString("lanName", "").ifBlank {
                    c.optString("lan", "").ifBlank { "Unknown" }
                }
                subtitleCallback(SubtitleFile(label, url))
                sent++
            }
            Log.d(TAG, "[SUBTITLE] extCaptions=${caps?.length() ?: 0} sent=$sent")
        } catch (e: Exception) {
            Log.e(TAG, "[SUBTITLE] parse gagal: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // 5. LOAD LINKS
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = AppUtils.tryParseJson<EpData>(data) ?: return false
        val bearerToken = getBearerToken() ?: return false

        val candidatePairs = if (epData.subjectType == 1 || (epData.se == 0 && epData.ep == 0)) {
            listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
        } else {
            listOf(epData.se to epData.ep, 1 to 1, 0 to 0)
        }

        var foundStream: StreamItem? = null

        for ((se, ep) in candidatePairs) {
            val ts = System.currentTimeMillis().toString()
            val path = "/wefeed-mobile-bff/subject-api/play-info"
            val query = "ep=$ep&se=$se&subjectId=${epData.subjectId}"

            val response = app.get(
                "$mainUrl$path?$query",
                headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), bearerToken)
            )

            val playData = response.parsedSafe<PlayInfoResponse>()
            val stream = playData?.data?.streams?.firstOrNull()

            if (stream?.url != null && !stream.signCookie.isNullOrBlank()) {
                foundStream = stream
                break
            }
        }

        val targetStream = foundStream ?: return false
        val mpdUrl = targetStream.url ?: return false
        val cleanCookie = (targetStream.signCookie ?: return false).trimEnd(';')

        loadSubtitles(epData.subjectId, targetStream.id, bearerToken, subtitleCallback)

        callback(
            newExtractorLink(
                source = name,
                name = "MovieBox (DASH HEVC)",
                url = mpdUrl,
                type = ExtractorLinkType.DASH
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "User-Agent" to CS_USER_AGENT,
                    "Cookie" to cleanCookie,
                    "Referer" to mainUrl
                )
            }
        )

        return true
    }

    // MODELS
    data class RankingResponse(val code: Int?, val data: RankingData?)
    data class RankingData(
        val categoryList: List<CategoryItem>?,
        val subjects: List<SubjectItem>?
    )
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
        val trailer: TrailerItem?
    )

    data class SubjectItem(
        val subjectId: String?,
        val title: String?,
        val cover: CoverItem?,
        val subjectType: Int?
    )
    data class CoverItem(val url: String?)

    data class StaffItem(
        val staffId: String?,
        val name: String?,
        val character: String?,
        val avatarUrl: String?
    )

    data class TrailerItem(
        @JsonProperty("VideoAddress") val videoAddressUpper: VideoAddressItem? = null,
        @JsonProperty("videoAddress") val videoAddressLower: VideoAddressItem? = null
    )
    data class VideoAddressItem(
        val url: String?,
        val definition: String? = null,
        val duration: Int? = null
    )

    data class SeasonInfoResponse(val code: Int?, val data: SeasonInfoData?)
    data class SeasonInfoData(
        val subjectId: String?,
        val subjectType: Int?,
        val seasons: List<SeasonItem>?
    )
    data class SeasonItem(
        val se: Int?,
        val maxEp: Int?
    )

    data class PlayInfoResponse(val code: Int?, val message: String?, val data: PlayData?)
    data class PlayData(val streams: List<StreamItem>?)
    data class StreamItem(
        val format: String?,
        val id: String?,
        val url: String?,
        val resolutions: String?,
        val size: String?,
        val duration: Long?,
        val codecName: String?,
        val signCookie: String?
    )
}
