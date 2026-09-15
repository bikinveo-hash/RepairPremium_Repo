package com.adixtream

import android.content.Context
import android.util.Base64
import android.util.Log
import android.os.Build
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object AdiXtreamExtractor : AdiXtream() {

    // ================== MOVIEBOX SOURCE ==================
    // Engine diambil dari MovieBoxProvider, TANPA membawa mainPage/search/
    // quickSearch/load/detail/recommendation miliknya, karena semua itu sudah
    // ditangani TMDB di AdiXtream.
    //
    // Alur: TMDB load() -> LinkData -> loadLinks() -> invokeMoviebox()
    //       -> search/v2 -> season-info -> play-info -> ExtractorLink (+ Cookie)
    //
    // DIAGNOSTIK: filter logcat dengan tag "AdiXtreamMB".
    private const val MB_TAG = "AdiXtreamMB"

    /**
     * Dipanggil dari AdiXtreamPlugin.load(). Menyiapkan identity persisten
     * MovieBox sebelum request pertama. Hanya meneruskan Context; tidak
     * mengubah jalur TMDB maupun sumber Idlix.
     */
    fun attachContext(context: Context) = MovieboxHelper.attachContext(context)

    // Berapa banyak subject MovieBox yang boleh dicoba untuk satu judul.
    // TV murah karena season-info langsung membuang subject kosong (1 request/subject);
    // movie mahal karena setiap subject mencoba 4 kombinasi play-info.
    private const val MB_MAX_SUBJECTS_TV = 6
    private const val MB_MAX_SUBJECTS_MOVIE = 3

    suspend fun invokeMoviebox(
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
        // LinkData.year untuk TV = tahun SEASON, sedangkan releaseDate Moviebox
        // = tahun SERIES. Pakai airedYear (tahun rilis series) supaya season >= 2
        // tidak gagal match.
        val matchYear = if (season != null) (airedYear ?: year) else year
        val wantedType = if (season != null) 2 else 1

        Log.d(
            MB_TAG,
            "[00-INPUT] title='$title' orgTitle='$orgTitle' altTitle='$altTitle' " +
                "year=$year airedYear=$airedYear season=$season episode=$episode " +
                "=> matchYear=$matchYear wantedType=$wantedType"
        )

        val bearer = MovieboxHelper.getBearerToken()
        if (bearer == null) {
            Log.e(MB_TAG, "[01-AUTH] STOP: bearer token null (ranking-list / header x-user gagal)")
            return
        }
        Log.d(MB_TAG, "[01-AUTH] ok (len=${bearer.length})")

        /**
         * Mengembalikan SEMUA subject yang lolos validasi, sudah diurutkan dari
         * yang paling mirip. Sebelumnya fungsi ini hanya mengembalikan satu subject
         * dan itulah penyebab House of the Dragon gagal: MovieBox punya 6 subject
         * berjudul sama, yang pertama adalah entri kosong tanpa stream.
         */
        suspend fun searchSubjects(query: String): List<MovieboxSubject> {
            val cleanQuery = query.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
            if (cleanQuery.isEmpty()) {
                Log.w(MB_TAG, "[02-SEARCH] skip: query '$query' kosong setelah normalisasi")
                return emptyList()
            }

            val body = JSONObject()
                .put("page", 1)
                .put("perPage", 10)
                .put("keyword", query)
                .put("tabId", "")
                .toString()

            Log.d(MB_TAG, "[02-SEARCH] keyword='$query' normalized='$cleanQuery'")

            val raw = MovieboxHelper.postSigned(
                "/wefeed-mobile-bff/subject-api/search/v2",
                body,
                bearer
            )
            if (raw == null) {
                Log.e(MB_TAG, "[02-SEARCH] STOP: HTTP gagal / bukan 200 untuk keyword='$query'")
                return emptyList()
            }

            val parsed = tryParseJson<MovieboxSearchResponse>(raw)
            if (parsed == null) {
                Log.e(MB_TAG, "[02-SEARCH] STOP: JSON gagal di-parse. head=${raw.take(300)}")
                return emptyList()
            }

            val groups = parsed.data?.results.orEmpty()
            val subjects = groups
                .flatMap { it.subjects ?: emptyList() }
                .filter { !it.subjectId.isNullOrBlank() }
                .distinctBy { it.subjectId }

            Log.d(MB_TAG, "[02-SEARCH] code=${parsed.code} groups=${groups.size} subjects=${subjects.size}")
            subjects.forEachIndexed { i, sub ->
                Log.d(
                    MB_TAG,
                    "[03-CAND] #$i id=${sub.subjectId} type=${sub.subjectType} " +
                        "releaseDate=${sub.releaseDate} title='${sub.title}'"
                )
            }

            // rank 0 = judul identik, 1 = judul + embel-embel ("... S1-S3",
            // "... [Indonesian]"), 2/3 = mengandung sebagian. Semakin kecil semakin mirip.
            val ranked = ArrayList<Pair<Int, MovieboxSubject>>()
            for (sub in subjects) {
                val st = sub.subjectType
                if (st != null && st != wantedType) {
                    Log.d(MB_TAG, "[04-REJECT] id=${sub.subjectId} alasan=subjectType $st != $wantedType")
                    continue
                }
                val cleanTitle = sub.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase().orEmpty()
                if (cleanTitle.isEmpty()) {
                    Log.d(MB_TAG, "[04-REJECT] id=${sub.subjectId} alasan=title kosong")
                    continue
                }
                val rank = when {
                    cleanTitle == cleanQuery -> 0
                    cleanTitle.startsWith(cleanQuery) -> 1
                    cleanTitle.contains(cleanQuery) -> 2
                    cleanQuery.contains(cleanTitle) -> 3
                    else -> -1
                }
                if (rank < 0) {
                    Log.d(MB_TAG, "[04-REJECT] id=${sub.subjectId} alasan=title '$cleanTitle' vs '$cleanQuery'")
                    continue
                }
                // subjectType tak dikenal hanya boleh lewat kalau judulnya identik.
                if (st == null && rank != 0) {
                    Log.d(MB_TAG, "[04-REJECT] id=${sub.subjectId} alasan=subjectType null & judul tidak identik")
                    continue
                }

                val subjectYear = sub.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                // [FIX-3] MovieBox kadang memecah/mengagregasi serial, sehingga releaseDate
                // subject bisa jauh lebih baru dari tahun rilis series. Season tidak mungkin
                // tayang sebelum series-nya mulai, jadi untuk TV hanya batas bawah yang
                // divalidasi. Movie tetap +/-1 seperti semula.
                val yearOk = when {
                    matchYear == null || subjectYear == null -> true
                    wantedType == 2 -> subjectYear >= matchYear - 1
                    else -> abs(subjectYear - matchYear) <= 1
                }
                if (!yearOk) {
                    Log.d(MB_TAG, "[04-REJECT] id=${sub.subjectId} alasan=year $subjectYear vs $matchYear")
                    continue
                }
                ranked.add(rank to sub)
            }

            // rank 3 = judul subject hanyalah POTONGAN dari query (mis. "The Haunted"
            // untuk query "The Haunted Hotel"). Itu paling rawan salah judul, jadi hanya
            // dipakai kalau benar-benar tidak ada kandidat yang lebih mirip.
            val strong = ranked.filter { it.first <= 2 }
            val out = (if (strong.isNotEmpty()) strong else ranked)
                .sortedBy { it.first }
                .map { it.second }
            Log.d(
                MB_TAG,
                "[05-MATCH] lolos=${out.size} untuk '$query' => " +
                    out.joinToString { "" + it.subjectId + "('" + it.title + "')" }.ifBlank { "(kosong)" }
            )
            return out
        }

        // [FIX-1] Rantai fallback lama (title -> orgTitle -> altTitle) semuanya memakai
        // substringBefore(":"), padahal untuk judul Inggris title == orgTitle sehingga
        // keyword yang sama dikirim berulang. Selain itu substringBefore(":") merusak
        // "Mission: Impossible" menjadi "Mission". Judul utuh dicoba lebih dulu, lalu
        // varian tanpa subtitle, dan daftarnya di-distinct.
        val queries = listOfNotNull(
            title,
            title.substringBefore(":"),
            orgTitle,
            orgTitle?.substringBefore(":"),
            altTitle,
            altTitle?.substringBefore(":")
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        Log.d(MB_TAG, "[02-SEARCH] daftar keyword yang akan dicoba = $queries")

        var candidates: List<MovieboxSubject> = emptyList()
        for (q in queries) {
            candidates = searchSubjects(q)
            if (candidates.isNotEmpty()) break
        }
        if (candidates.isEmpty()) {
            Log.e(MB_TAG, "[05-MATCH] STOP: tidak ada subject setelah ${queries.size} keyword: $queries")
            return
        }

        // Playback menggunakan exact official runtime identity/session profile
        // yang sudah terbukti menghasilkan FULL_CONTENT pada MovieBox standalone.
        // Search + season-info tetap memakai jalur katalog lama karena keduanya
        // hanya dipakai untuk memetakan judul TMDB -> subjectId MovieBox.
        val playbackSession = MovieboxHelper.getPlaybackSession()
        if (playbackSession == null) {
            Log.e(MB_TAG, "[05-PLAYBACK-SESSION] STOP: exact runtime session gagal")
            return
        }
        Log.d(MB_TAG, "[05-PLAYBACK-SESSION] exact runtime session siap")

        /** Daftar season milik satu subject menurut server (kosong = tidak diketahui). */
        suspend fun fetchSeasons(sid: String): List<Int> {
            val raw = MovieboxHelper.getSigned(
                "/wefeed-mobile-bff/subject-api/season-info",
                "subjectId=$sid",
                bearer
            )
            val list = raw
                ?.let { tryParseJson<MovieboxSeasonInfoResponse>(it) }
                ?.data?.seasons.orEmpty()
            val dump = list.joinToString { "se=" + it.se + "/maxEp=" + it.maxEp }.ifBlank { "(kosong)" }
            Log.d(MB_TAG, "[06-SEASON] subjectId=$sid server=$dump")
            return list.mapNotNull { it.se }.sorted()
        }

        /** play-info exact-current untuk satu subject; null bila tidak ada stream layak. */
        suspend fun tryPlay(sid: String, pairs: List<Pair<Int, Int>>): List<MovieboxStreamItem>? {
            for ((se, epNum) in pairs) {
                val raw = MovieboxHelper.getPlaybackPlayInfo(
                    subjectId = sid,
                    se = se,
                    ep = epNum,
                    session = playbackSession
                )
                if (raw == null) {
                    Log.e(MB_TAG, "[08-PLAY] id=$sid se=$se ep=$epNum exact play-info gagal")
                    continue
                }

                val play = tryParseJson<MovieboxPlayInfoResponse>(raw)
                if (play == null) {
                    Log.e(MB_TAG, "[08-PLAY] id=$sid se=$se ep=$epNum JSON gagal. head=${raw.take(200)}")
                    continue
                }

                val all = play.data?.streams.orEmpty()
                val usable = all
                    .filter { !it.url.isNullOrBlank() && !it.signCookie.isNullOrBlank() }
                    .distinctBy { it.url }

                val noUrl = all.count { it.url.isNullOrBlank() }
                val noCookie = all.count { it.signCookie.isNullOrBlank() }

                Log.d(
                    MB_TAG,
                    "[08-PLAY] id=$sid se=$se ep=$epNum code=${play.code} msg=${play.message} " +
                        "streams=${all.size} usable=${usable.size} tanpaUrl=$noUrl tanpaSignCookie=$noCookie"
                )

                if (usable.isNotEmpty()) return usable
            }
            return null
        }

        // ---------- PEMILIHAN SUBJECT ----------
        // [FIX-5] AKAR MASALAH House of the Dragon: MovieBox mengembalikan 6 subject
        // berjudul "House of the Dragon"; yang pertama (2195332290044216368) adalah
        // entri kosong -- season-info kosong dan play-info balas code=0 msg=ok
        // streams=0. Versi lama berhenti di situ. Sekarang subject dicoba berurutan
        // sampai ada yang benar-benar punya stream.
        //
        // Untuk TV, season-info dipakai sebagai penyaring murah sekaligus penentu
        // indexing: subject yang tidak memuat season yang diminta langsung dilewati,
        // jadi tidak mungkin memutar episode dari season lain.
        val ep = episode ?: 1
        val pool = candidates.take(if (season == null) MB_MAX_SUBJECTS_MOVIE else MB_MAX_SUBJECTS_TV)
        var chosenId: String? = null
        var streams: List<MovieboxStreamItem>? = null

        if (season == null) {
            val moviePairs = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
            for (sub in pool) {
                val sid = sub.subjectId ?: continue
                Log.d(MB_TAG, "[07-TRY] MOVIE id=$sid title='${sub.title}' pairs=$moviePairs")
                val found = tryPlay(sid, moviePairs)
                if (found != null) {
                    chosenId = sid
                    streams = found
                    break
                }
            }
        } else {
            // Subject yang season-info-nya kosong disimpan untuk percobaan terakhir.
            val unknown = ArrayList<MovieboxSubject>()
            for (sub in pool) {
                val sid = sub.subjectId ?: continue
                val available = fetchSeasons(sid)
                if (available.isEmpty()) {
                    unknown.add(sub)
                    continue
                }
                val se = when {
                    available.contains(season) -> season
                    // Hanya digeser bila daftar server memang dimulai dari 0.
                    available.first() == 0 && available.contains(season - 1) -> season - 1
                    else -> null
                }
                if (se == null) {
                    Log.d(MB_TAG, "[07-TRY] SKIP id=$sid: season $season tidak ada (server $available)")
                    continue
                }
                Log.d(MB_TAG, "[07-TRY] TV id=$sid title='${sub.title}' se=$se ep=$ep (server $available)")
                val found = tryPlay(sid, listOf(se to ep))
                if (found != null) {
                    chosenId = sid
                    streams = found
                    break
                }
            }

            if (streams == null && unknown.isNotEmpty()) {
                // season-info tidak memberi info apa pun -> pakai heuristik lama.
                // Nomor episode TETAP dipertahankan, hanya indexing season yang dicoba,
                // sehingga tidak mungkin memutar episode dari season lain.
                val pairs = if (season == 1) listOf(1 to ep, 0 to ep) else listOf(season to ep)
                for (sub in unknown) {
                    val sid = sub.subjectId ?: continue
                    Log.d(MB_TAG, "[07-TRY] TV-fallback id=$sid title='${sub.title}' pairs=$pairs")
                    val found = tryPlay(sid, pairs)
                    if (found != null) {
                        chosenId = sid
                        streams = found
                        break
                    }
                }
            }
        }

        val subjectId = chosenId
        val finalStreams = streams
        if (subjectId == null || finalStreams == null) {
            Log.e(
                MB_TAG,
                "[08-PLAY] STOP: tidak ada stream layak setelah mencoba ${pool.size} subject: " +
                    pool.joinToString { "" + it.subjectId }
            )
            return
        }
        Log.d(MB_TAG, "[08-PLAY] SUBJECT TERPAKAI id=$subjectId streams=${finalStreams.size}")

        // ---------- SUBTITLE ----------
        val streamId = finalStreams.firstOrNull()?.id
        if (!streamId.isNullOrBlank()) {
            // URUTAN QUERY WAJIB ALFABETIS (streamId, subjectId).
            val rawSub = MovieboxHelper.getSigned(
                "/wefeed-mobile-bff/subject-api/get-stream-captions",
                "streamId=$streamId&subjectId=$subjectId",
                playbackSession.bearer
            )
            if (rawSub == null) {
                Log.w(MB_TAG, "[09-SUB] get-stream-captions gagal (stream tetap dilanjutkan)")
            } else {
                val caps = tryParseJson<MovieboxCaptionResponse>(rawSub)?.data?.extCaptions.orEmpty()
                var sent = 0
                caps.forEach { cap ->
                    val subUrl = cap.url ?: return@forEach
                    val label = cap.lanName ?: cap.lan ?: cap.language ?: "Unknown"
                    subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                    sent++
                }
                Log.d(MB_TAG, "[09-SUB] extCaptions=${caps.size} terkirim=$sent")
            }
        } else {
            Log.w(MB_TAG, "[09-SUB] streamId kosong, subtitle dilewati")
        }

        // ---------- STREAM ----------
        var emitted = 0
        finalStreams.forEach { stream ->
            val streamUrl = stream.url ?: return@forEach
            val cleanCookie = (stream.signCookie ?: return@forEach).trimEnd(';')

            // [AUDIT-A5] ExtractorApi.inferTypeFromUrl() memetakan tipe dari PATH url
            // (.m3u8 -> M3U8, .mpd -> DASH, .torrent, magnet:) dan mengabaikan query string.
            // contains(".m3u8") salah kalau string itu hanya muncul di query, dan aturan
            // "selain m3u8 berarti DASH" salah untuk mp4 progresif. INFER_TYPE adalah cara
            // yang didokumentasikan framework, jadi pemetaan diserahkan ke sana.
            //
            // [AUDIT-A6] .mpd/.m3u8 adalah manifest MULTI-BITRATE, jadi field "resolutions"
            // bukan resolusi tertinggi. Logcat 11:07 memberi label "MovieBox 480p" untuk
            // .../_1080_h265_29/index_web.mpd -- pengguna melewatkan stream 1080p karena
            // dikira 480p. Untuk manifest dipakai bobot P1080 seperti MovieBoxProvider asli;
            // "resolutions" hanya dipercaya untuk file progresif.
            val path = streamUrl.substringBefore('?').substringBefore('#')
            val adaptive = path.endsWith(".mpd") || path.endsWith(".m3u8")
            val namedQuality = movieboxQualityFromName(stream.resolutions)
                .takeIf { it != Qualities.Unknown.value }

            val quality = if (adaptive) Qualities.P1080.value else (namedQuality ?: Qualities.P1080.value)
            val label = when {
                adaptive -> {
                    val kind = if (path.endsWith(".m3u8")) "HLS" else "DASH"
                    stream.codecName?.takeIf { it.isNotBlank() }
                        ?.let { "MovieBox $kind ${it.uppercase()}" } ?: "MovieBox $kind"
                }
                namedQuality != null -> "MovieBox ${namedQuality}p"
                else -> "MovieBox"
            }

            callback.invoke(
                newExtractorLink("MovieBox", label, streamUrl, INFER_TYPE) {
                    this.referer = MovieboxHelper.API_URL
                    this.quality = quality
                    // Cookie di sini dibaca lagi oleh AdiXtream.getVideoInterceptor()
                    // supaya ExoPlayer ikut mengirimnya ke CDN.
                    this.headers = mapOf(
                        "User-Agent" to MovieboxHelper.USER_AGENT,
                        "Cookie" to cleanCookie,
                        "Referer" to MovieboxHelper.API_URL
                    )
                }
            )
            emitted++
            Log.d(MB_TAG, "[10-LINK] dibuat: $label q=$quality url=${streamUrl.substringBefore('?')}")
        }
        Log.d(MB_TAG, "[10-LINK] SELESAI, total ExtractorLink=$emitted")
    }


    private fun movieboxQualityFromName(value: String?): Int {
        if (value.isNullOrBlank()) return Qualities.Unknown.value
        val clean = value.lowercase().replace("p", "").trim()
        return when (clean) {
            "4k" -> Qualities.P2160.value
            else -> clean.toIntOrNull() ?: Qualities.Unknown.value
        }
    }

    // ================== MOVIEBOX ENGINE (AUTH + SIGNED REQUEST) ==================
    // Jalur katalog/search tetap memakai profil lama yang sudah bekerja untuk
    // pemetaan subject. Jalur PLAYBACK memakai exact runtime identity current APK
    // 4.0.02.0831.03 / 999999999 yang sudah diverifikasi FULL_CONTENT.
    private object MovieboxHelper {

        const val API_URL = "https://api3.aoneroom.com"
        const val USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        private const val PLAYBACK_API_BASE = "https://api6.aoneroom.com"

        /**
         * x-client-info dirakit saat request, bukan konstanta, karena device_id
         * berasal dari identity persisten per-instalasi.
         *
         * Field lain TIDAK berubah sedikit pun dari versi sebelumnya.
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
        // IDENTITY  (meniru Lmh/b;->h pada APK MovieBox: UUID -> MD5 -> persist)
        //
        //   APK  : MMKV("vshow")["apkdeviceid"]  <- Lph/a$a;->d(UUID) = MD5 hex 32
        //   sini : SharedPreferences("adixtream_identity")["apkdeviceid"]
        //
        // Sengaja TERPISAH dari storage plugin MovieBox standalone, supaya
        // kedua plugin tidak saling bergantung pada identity masing-masing.
        //
        // device_id yang sebelumnya di-hardcode dipakai bersama oleh semua
        // instalasi, sehingga server memetakannya ke satu guest user dan
        // membatasi search/v2 serta play-info dengan HTTP 406 "find no content".
        // ---------------------------------------------------------------
        private const val ID_PREFS = "adixtream_identity"
        private const val ID_KEY = "apkdeviceid"
        private val ID_FORMAT = Regex("^[0-9a-f]{32}$")

        @Volatile private var appContext: Context? = null
        @Volatile private var cachedDeviceId: String? = null
        @Volatile private var persisted = false

        fun attachContext(context: Context) {
            appContext = context.applicationContext
            val id = deviceId()
            Log.d(MB_TAG, "[IDENTITY] device_id=$id len=${id.length} " +
                    "valid=${ID_FORMAT.matches(id)} persisted=$persisted")
        }

        /**
         * Storage adalah sumber kebenaran. Nilai dibuat sekali lalu dipakai
         * selamanya. Nilai in-memory hanya dipakai bila storage sedang tidak
         * tersedia, dan akan dipersist pada kesempatan pertama sehingga identity
         * tidak berganti antar-restart.
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
                    Log.e(MB_TAG, "[IDENTITY] storage tidak tersedia: ${e.message}")
                }
            }

            return cached ?: md5(UUID.randomUUID().toString()).also { cachedDeviceId = it }
        }

        // Double base64 decode, persis MovieBoxProvider.
        private val SECRET_BYTES: ByteArray by lazy {
            val step1 = String(
                Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", Base64.DEFAULT),
                Charsets.UTF_8
            )
            Base64.decode(step1, Base64.DEFAULT)
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /**
         * Canonical string mengikuti GatewaySignManager.doSign pada APK resmi.
         * Tujuh baris dipisah "\n":
         *   1. HTTP method (huruf besar)
         *   2. accept
         *   3. content-type
         *   4. panjang body   -> kosong bila tanpa body
         *   5. timestamp
         *   6. md5 hex body   -> kosong bila tanpa body
         *   7. path (+query)
         *
         * Tanpa body baris 4 dan 6 kosong, sehingga fungsi ini aman untuk GET
         * maupun POST. Baris 4 dan 6 HARUS diisi bersamaan atau kosong bersamaan.
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

        // NO_WRAP wajib. Base64.DEFAULT menambahkan newline dan merusak header.
        private fun generateSignature(method: String, pathWithQuery: String, ts: String, body: String = ""): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val hmacBytes = mac.doFinal(buildCanonical(method, pathWithQuery, ts, body).toByteArray(Charsets.UTF_8))
            return "$ts|2|${Base64.encodeToString(hmacBytes, Base64.NO_WRAP)}"
        }

        private fun generateGuestToken(ts: String): String = "$ts,${md5(ts.reversed())}"

        // ---------------------------------------------------------------
        // EXACT CURRENT PLAYBACK PROFILE
        // ---------------------------------------------------------------
        // Private/local only. Blob ini device-bound via Build.FINGERPRINT dan
        // berasal dari runtime identity MovieBox resmi yang sudah diverifikasi.
        private const val ORACLE_BLOB_B64 = "DTIykuYnWieJjJgXTGK+QzvFGBfR7pw7CKX532Rg/rxEImeG4XFYNNeXjUMSJKoba4VVBda+0mtT8JXQYST0rlQ8cofmIFoniYzSREIn+R861RlRje/Zf1/zntQte/ymRD0x0LN7CGfWn9lFR3LpAX6OWkbB6oY+NKPOlDpgtO1UPHKJ6TpKZN/CtAVVfrlIft0WRcapxnAGr8LTbGD+vCVdfbO+eAZHkYLJGERl6Rd+qXFh4sS4GTSX7/BJYOi8GWMPluI7TWzcwMlMAyD9D3DFRlDS4oU8SfqE/0Rg6LwFYA+D6C1bJ4mM3kcRIPsPcMVHTMb/jz80rMfYZzel+RMyasLuJxwpkdqCG0RrpEM5xQ4X9PiDM0SKx89hMrHsFzJ8wvI6W3fsx49UGzP9H2XSAg2Gut5gXPiVgjh386tEMnzC8SxMdtrBhSlCfq9Ift0WDIyy02tS+Z+PIm7m6BNiI4noJ2Fr0sOOVBsz/wNsyQQHm7vSYVruloUiPw=="
        private const val ORACLE_KEY_DOMAIN = "MovieBoxOracleV1|"

        data class ExactPlaybackSession(
            val bearer: String,
            val clientInfo: String
        )

        private data class OracleIdentity(
            val userId: String,
            val deviceId: String,
            val versionName: String,
            val versionCode: Long,
            val osVersion: String,
            val model: String,
            val installCh: String,
            val gaid: String,
            val net: String,
            val region: String,
            val timezone: String,
            val spCode: String,
            val installStore: String,
            val systemLanguage: String
        )

        private data class PlaybackSessionProfile(
            val identity: OracleIdentity,
            val clientInfo: String
        )

        private fun oracleDecryptJson(): JSONObject? {
            return try {
                val encrypted = Base64.decode(ORACLE_BLOB_B64, Base64.DEFAULT)
                val key = MessageDigest.getInstance("SHA-256")
                    .digest((ORACLE_KEY_DOMAIN + Build.FINGERPRINT).toByteArray(Charsets.UTF_8))
                val plain = ByteArray(encrypted.size)
                for (i in encrypted.indices) {
                    plain[i] = (encrypted[i].toInt() xor key[i % key.size].toInt()).toByte()
                }
                JSONObject(String(plain, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(MB_TAG, "[PLAYBACK] runtime identity decrypt gagal: ${e.javaClass.simpleName}")
                null
            }
        }

        private fun oracleString(o: JSONObject, key: String): String {
            val value = o.opt(key)
            return if (value == null || value == JSONObject.NULL) "" else value.toString()
        }

        private fun oracleIdentity(): OracleIdentity? {
            val o = oracleDecryptJson() ?: return null
            val versionCode = oracleString(o, "version_code").toLongOrNull() ?: return null

            val identity = OracleIdentity(
                userId = oracleString(o, "user_id"),
                deviceId = oracleString(o, "device_id"),
                versionName = oracleString(o, "version_name"),
                versionCode = versionCode,
                osVersion = oracleString(o, "os_version"),
                model = oracleString(o, "model"),
                installCh = oracleString(o, "install_ch"),
                gaid = oracleString(o, "gaid"),
                net = oracleString(o, "net"),
                region = oracleString(o, "region"),
                timezone = oracleString(o, "timezone"),
                spCode = oracleString(o, "sp_code"),
                installStore = oracleString(o, "install_store"),
                systemLanguage = oracleString(o, "system_language")
            )

            if (
                identity.deviceId.isBlank() ||
                identity.versionName != "4.0.02.0831.03" ||
                identity.versionCode != 999999999L
            ) {
                Log.e(MB_TAG, "[PLAYBACK] runtime identity tidak cocok dengan build target")
                return null
            }

            return identity
        }

        private fun realUserIdIsGuest(value: String): Boolean =
            value.isBlank() ||
                value.equals("null", ignoreCase = true) ||
                value.equals("none", ignoreCase = true) ||
                value.equals("guest", ignoreCase = true)

        private fun buildPlaybackSessionProfile(): PlaybackSessionProfile? {
            val id = oracleIdentity() ?: return null

            val info = JSONObject()
                .put("package_name", "com.community.oneroom")
                .put("version_name", id.versionName)
                .put("version_code", id.versionCode)
                .put("os", "android")
                .put("os_version", id.osVersion)

            if (id.installCh.isNotBlank()) info.put("install_ch", id.installCh)

            info.put("device_id", id.deviceId)
                .put("install_store", id.installStore)

            if (id.gaid.isNotBlank()) info.put("gaid", id.gaid)

            info.put("brand", Build.BRAND ?: "")
                .put("model", id.model)
                .put("system_language", id.systemLanguage)
                .put("net", id.net)
                .put("region", id.region)
                .put("timezone", id.timezone)
                .put("sp_code", id.spCode)

            return PlaybackSessionProfile(
                identity = id,
                clientInfo = info.toString()
            )
        }

        private fun playbackGetCanonical(pathWithCanonicalQuery: String, ts: String): String =
            listOf("GET", "", "", "", ts, "", pathWithCanonicalQuery).joinToString("\n")

        private fun playbackGetSignature(pathWithCanonicalQuery: String, ts: String): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val bytes = mac.doFinal(
                playbackGetCanonical(pathWithCanonicalQuery, ts).toByteArray(Charsets.UTF_8)
            )
            return "$ts|2|${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

        private fun playbackGuestHeaders(
            ts: String,
            signature: String,
            profile: PlaybackSessionProfile
        ): Map<String, String> = mapOf(
            "x-client-token" to generateGuestToken(ts),
            "x-tr-signature" to signature,
            "x-client-info" to profile.clientInfo,
            "x-client-status" to "1"
        )

        private fun playbackPlayInfoHeaders(
            signature: String,
            session: ExactPlaybackSession
        ): Map<String, String> = mapOf(
            "authorization" to "Bearer ${session.bearer}",
            "x-tr-signature" to signature,
            "x-client-info" to session.clientInfo,
            "x-client-status" to "1"
        )

        suspend fun getPlaybackSession(): ExactPlaybackSession? {
            val profile = buildPlaybackSessionProfile() ?: return null
            val ts = System.currentTimeMillis().toString()
            val path = "/wefeed-mobile-bff/tab/ranking-list"
            val query = "page=1&perPage=1&tabId=0"
            val signature = playbackGetSignature("$path?$query", ts)

            return try {
                val response = app.get(
                    "$PLAYBACK_API_BASE$path?$query",
                    headers = playbackGuestHeaders(ts, signature, profile)
                )

                val xUserHeader = response.headers["x-user"] ?: return null
                val xUser = JSONObject(xUserHeader)
                val bearer = xUser.optString("token", "").ifBlank { return null }

                val sessionUserId = when {
                    xUser.has("userId") && xUser.opt("userId") != JSONObject.NULL ->
                        xUser.opt("userId")?.toString().orEmpty()
                    xUser.has("user_id") && xUser.opt("user_id") != JSONObject.NULL ->
                        xUser.opt("user_id")?.toString().orEmpty()
                    else -> ""
                }

                val realUserId = profile.identity.userId
                val identityMatches =
                    realUserIdIsGuest(realUserId) ||
                        (sessionUserId.isNotBlank() && sessionUserId == realUserId)

                if (!identityMatches) {
                    Log.e(MB_TAG, "[PLAYBACK] session user tidak cocok dengan runtime identity")
                    null
                } else {
                    ExactPlaybackSession(
                        bearer = bearer,
                        clientInfo = profile.clientInfo
                    )
                }
            } catch (e: Exception) {
                Log.e(MB_TAG, "[PLAYBACK] gagal memperoleh bearer: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        suspend fun getPlaybackPlayInfo(
            subjectId: String,
            se: Int,
            ep: Int,
            session: ExactPlaybackSession
        ): String? {
            val ts = System.currentTimeMillis().toString()
            val path = "/wefeed-mobile-bff/subject-api/play-info"

            // Current official APK outgoing order.
            val finalUrlQuery = "subjectId=$subjectId&se=$se&ep=$ep"

            // Gateway signing sorts keys lexically.
            val canonicalQuery = "ep=$ep&se=$se&subjectId=$subjectId"
            val signature = playbackGetSignature("$path?$canonicalQuery", ts)

            return try {
                val response = app.get(
                    "$PLAYBACK_API_BASE$path?$finalUrlQuery",
                    headers = playbackPlayInfoHeaders(signature, session)
                )
                if (response.code == 200) response.text else null
            } catch (e: Exception) {
                Log.e(MB_TAG, "[PLAYBACK] play-info gagal: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        private fun headersFor(ts: String, signature: String, bearer: String?): Map<String, String> {
            val h = mutableMapOf(
                "user-agent" to USER_AGENT,
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

        suspend fun getSigned(path: String, query: String, bearer: String?): String? {
            val ts = System.currentTimeMillis().toString()
            val pathWithQuery = if (query.isBlank()) path else "$path?$query"
            return try {
                app.get(
                    "$API_URL$pathWithQuery",
                    headers = headersFor(ts, generateSignature("GET", pathWithQuery, ts), bearer)
                ).text
            } catch (e: Exception) {
                null
            }
        }

        /**
         * POST ber-signature.
         *
         * PENTING: RequestBody dibuat dari ByteArray, BUKAN String. Overload
         * String pada OkHttp menambahkan "; charset=utf-8" ke media type, lalu
         * BridgeInterceptor menimpa header Content-Type. Akibatnya yang dikirim
         * "application/json; charset=utf-8" sedangkan yang ditandatangani
         * "application/json" -> server menolak dengan 407.
         */
        suspend fun postSigned(path: String, body: String, bearer: String?): String? {
            val ts = System.currentTimeMillis().toString()
            val sig = generateSignature("POST", path, ts, body)
            return try {
                val res = app.post(
                    "$API_URL$path",
                    headers = headersFor(ts, sig, bearer),
                    requestBody = body.toByteArray(Charsets.UTF_8)
                        .toRequestBody("application/json".toMediaTypeOrNull())
                )
                if (res.code == 200) res.text else null
            } catch (e: Exception) {
                null
            }
        }

        // Token guest diambil dari header response "x-user" pada endpoint ranking-list.
        suspend fun getBearerToken(): String? {
            return try {
                val ts = System.currentTimeMillis().toString()
                val path = "/wefeed-mobile-bff/tab/ranking-list"
                val query = "page=1&perPage=1&tabId=0"

                val response = app.get(
                    "$API_URL$path?$query",
                    headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), null)
                )

                val xUserHeader = response.headers["x-user"] ?: return null
                Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(xUserHeader)?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            }
        }
    }
}
