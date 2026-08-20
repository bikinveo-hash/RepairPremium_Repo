package com.AdiDrakor

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object AdiDrakorExtractor : AdiDrakor() {

    // ================== KISSKH SOURCE ==================
    // Logika pemilihan drama di bawah ini sudah diuji terpisah (35 kasus, termasuk
    // kasus nyata dari logcat 2026-08-13 11:07 di mana permintaan S1E1 malah
    // mendapat "The White Lotus - Season 3").
    private const val KK_TAG = "AdiDrakorKK"

    // Pola penanda season pada judul Kisskh. Sengaja ketat supaya judul seperti
    // "Seasons of Blossom", "S.W.A.T.", "MASH 4077", "The Boys 2" dan
    // "Greenland 2: Migration" TIDAK salah dianggap punya nomor season.
    private val KK_SEASON_PATTERNS = listOf(
        Regex("""season\s*0*(\d{1,2})""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[^a-z0-9])s\s*0*(\d{1,2})(?![a-z0-9])""", RegexOption.IGNORE_CASE),
        Regex("""(\d{1,2})(?:st|nd|rd|th)\s*season""", RegexOption.IGNORE_CASE)
    )

    private fun kkClean(s: String?): String =
        s?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase().orEmpty()

    /** Nomor season yang tertulis di judul Kisskh, null bila tidak ada. */
    private fun kkSeasonInTitle(title: String?): Int? {
        if (title == null) return null
        for (re in KK_SEASON_PATTERNS) {
            val v = re.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 0..50) return v
        }
        return null
    }

    /** Judul tanpa embel-embel season, untuk dibandingkan dengan judul TMDB. */
    private fun kkStripSeason(title: String): String =
        KK_SEASON_PATTERNS.fold(title) { acc, re -> re.replace(acc, " ") }

    /**
     * Containment arah balik (judul Kisskh lebih pendek dari judul TMDB) hanya boleh
     * untuk judul yang cukup panjang dan proporsional, supaya "The" tidak cocok
     * dengan "The Odyssey".
     */
    private fun kkRevOk(cleanTitle: String, cleanQuery: String): Boolean {
        if (cleanTitle.length < 6) return false
        if (!cleanQuery.contains(cleanTitle)) return false
        return cleanTitle.length * 10 >= cleanQuery.length * 6
    }

    /** rank kecil = lebih cocok; -1 = tolak. season null berarti film. */
    private fun kkRank(title: String?, cleanQuery: String, season: Int?): Int {
        val ct = kkClean(title)
        if (ct.isEmpty()) return -1
        val stripped = kkStripSeason(title ?: "")
        val cs = kkClean(stripped)

        val forward = ct.contains(cleanQuery) || cs.contains(cleanQuery)
        val backward = kkRevOk(ct, cleanQuery) || kkRevOk(cs, cleanQuery)
        if (!forward && !backward) return -1

        val exact = ct == cleanQuery || cs == cleanQuery
        if (season == null) return if (exact) 0 else 1

        val ts = kkSeasonInTitle(title)
        if (ts != null) {
            // Judul menyebut season lain -> TOLAK. Inilah perbaikan utamanya:
            // sebelumnya S1E1 bisa mendapat drama "... - Season 3".
            if (ts != season) return -1
            return if (exact) 0 else 1
        }
        // Judul tanpa penanda season = season 1 menurut konvensi Kisskh.
        if (season == 1) return if (exact) 2 else 3
        // S2 ke atas tanpa penanda: lebih baik tidak ada link daripada salah season.
        return -1
    }

    suspend fun invokeKisskh(
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://kisskh.ovh"
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        Log.d(KK_TAG, "[00-INPUT] title='$title' orgTitle='$orgTitle' altTitle='$altTitle' season=$season episode=$episode")

        suspend fun searchAndMatch(query: String): KisskhMedia? {
            return try {
                // [FIX-7] Query WAJIB di-encode. "Minions & Monsters" tanpa encode membuat
                // "&" dibaca sebagai pemisah parameter sehingga q terpotong jadi "Minions ".
                val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
                val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$encoded&type=0").text
                val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes)
                if (searchList == null) {
                    Log.e(KK_TAG, "[01-SEARCH] parse gagal untuk '$query'. head=${searchRes.take(200)}")
                    return null
                }

                val cleanQuery = kkClean(query)
                if (cleanQuery.isEmpty()) return null

                Log.d(KK_TAG, "[01-SEARCH] query='$query' hasil=${searchList.size}")

                // [FIX-8] Sebelumnya: find { contains } ?: firstOrNull { contains } -- dua
                // cabang yang IDENTIK (find memang firstOrNull berpredikat), jadi fallback-nya
                // dead code. Dan season sama sekali tidak dipakai untuk memilih drama.
                var best: KisskhMedia? = null
                var bestRank = Int.MAX_VALUE
                searchList.forEach { item ->
                    val r = kkRank(item.title, cleanQuery, season)
                    Log.d(KK_TAG, "[02-CAND] id=${item.id} rank=$r seasonDiJudul=${kkSeasonInTitle(item.title)} title='${item.title}'")
                    if (r in 0 until bestRank) {
                        bestRank = r
                        best = item
                    }
                }

                if (best == null) {
                    Log.w(KK_TAG, "[03-MATCH] tidak ada drama cocok untuk '$query' (season=$season)")
                } else {
                    Log.d(KK_TAG, "[03-MATCH] terpilih id=${best?.id} rank=$bestRank title='${best?.title}'")
                }
                best
            } catch (e: Exception) {
                Log.e(KK_TAG, "[01-SEARCH] gagal untuk '$query': ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        var matched = searchAndMatch(title)
        if (matched == null && orgTitle != null) matched = searchAndMatch(orgTitle)
        if (matched == null && altTitle != null) matched = searchAndMatch(altTitle)
        if (matched == null) {
            Log.e(KK_TAG, "[03-MATCH] STOP: tidak ada drama untuk judul mana pun")
            return
        }

        val dramaId = matched.id ?: return
        val detailRes = app.get("$mainUrl/api/DramaList/Drama/$dramaId?isq=false").parsedSafe<KisskhDetail>()
        if (detailRes == null) {
            Log.e(KK_TAG, "[04-DETAIL] STOP: detail drama $dramaId gagal di-parse")
            return
        }
        val episodes = detailRes.episodes
        if (episodes.isNullOrEmpty()) {
            Log.e(KK_TAG, "[04-DETAIL] STOP: drama $dramaId tidak punya episode")
            return
        }

        val targetEp = if (season == null) episodes.lastOrNull() else episodes.find { it.number?.toInt() == episode }
        if (targetEp == null) {
            val tersedia = episodes.mapNotNull { it.number?.toInt() }.sorted()
            Log.e(KK_TAG, "[05-EP] STOP: episode $episode tidak ada di drama '${matched.title}'. Tersedia=$tersedia")
            return
        }
        val epsId = targetEp.id ?: return
        Log.d(KK_TAG, "[05-EP] drama='${matched.title}' epNumber=${targetEp.number} epsId=$epsId")

        val kkeyVideo = app.get("$KISSKH_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
        val videoUrl = "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=null&time=null&kkey=$kkeyVideo"
        val sources = app.get(videoUrl).parsedSafe<KisskhSources>()

        var emitted = 0
        listOfNotNull(sources?.video, sources?.thirdParty).forEach { rawLink ->
            // [FIX-6] BUG LAMA: Kisskh kadang mengembalikan URL protocol-relative "//hls1...".
            // M3u8Helper melempar IllegalArgumentException "Expected URL scheme 'http' or
            // 'https'" untuk URL tanpa scheme, dan exception itu membatalkan seluruh
            // invokeKisskh sehingga subtitle pun tidak sempat dikirim.
            val link = if (rawLink.startsWith("//")) "https:$rawLink" else rawLink
            if (link.contains(".m3u8")) {
                M3u8Helper.generateM3u8("Kisskh", link, referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl))
                    .forEach { callback.invoke(it); emitted++ }
            } else if (link.contains(".mp4")) {
                callback.invoke(newExtractorLink("Kisskh", "Kisskh", link, INFER_TYPE) { this.referer = mainUrl })
                emitted++
            }
        }
        Log.d(KK_TAG, "[06-LINK] total ExtractorLink=$emitted")

        val kkeySub = app.get("$KISSKH_SUB_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
        val subJson = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkeySub").text
        val subs = tryParseJson<List<KisskhSubtitle>>(subJson)
        subs?.forEach { sub ->
            subtitleCallback.invoke(newSubtitleFile(sub.label ?: "Unknown", sub.src ?: return@forEach))
        }
        Log.d(KK_TAG, "[07-SUB] subtitle=${subs?.size ?: 0}")
    }

    private data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    private data class KisskhDetail(@JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    private data class KisskhKey(@JsonProperty("key") val key: String?)
    private data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    private data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)

    // ================== MOVIEBOX SOURCE ==================
    // Engine diambil dari MovieBoxProvider, TANPA membawa mainPage/search/
    // quickSearch/load/detail/recommendation miliknya, karena semua itu sudah
    // ditangani TMDB di AdiDrakor.
    //
    // Alur: TMDB load() -> LinkData -> loadLinks() -> invokeMoviebox()
    //       -> search/v2 -> season-info -> play-info -> ExtractorLink (+ Cookie)
    //
    // DIAGNOSTIK: filter logcat dengan tag "AdiDrakorMB".
    private const val MB_TAG = "AdiDrakorMB"

    /**
     * Dipanggil dari AdiDrakorPlugin.load(). Menyiapkan identity persisten
     * MovieBox sebelum request pertama. Hanya meneruskan Context; tidak
     * mengubah apa pun pada Kisskh maupun jalur TMDB.
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

        /** play-info untuk satu subject; null bila tidak ada stream yang layak. */
        suspend fun tryPlay(sid: String, pairs: List<Pair<Int, Int>>): List<MovieboxStreamItem>? {
            for ((se, epNum) in pairs) {
                // URUTAN QUERY WAJIB ALFABETIS (ep, se, subjectId) - ikut ditandatangani.
                val raw = MovieboxHelper.getSigned(
                    "/wefeed-mobile-bff/subject-api/play-info",
                    "ep=$epNum&se=$se&subjectId=$sid",
                    bearer
                )
                if (raw == null) {
                    Log.e(MB_TAG, "[08-PLAY] id=$sid se=$se ep=$epNum HTTP gagal / exception")
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
                bearer
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
            val namedQuality = getQualityFromName(stream.resolutions)
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
                    // Cookie di sini dibaca lagi oleh AdiDrakor.getVideoInterceptor()
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

    // ================== MOVIEBOX ENGINE (AUTH + SIGNED REQUEST) ==================
    // Dipindahkan apa adanya dari MovieBoxProvider. JANGAN mengubah apa pun di
    // dalam buildCanonical/generateSignature/headersFor: server memvalidasi
    // signature byte per byte.
    private object MovieboxHelper {

        const val API_URL = "https://api3.aoneroom.com"
        const val USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"

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
        //   sini : SharedPreferences("adidrakor_identity")["apkdeviceid"]
        //
        // Sengaja TERPISAH dari storage plugin MovieBox standalone, supaya
        // kedua plugin tidak saling bergantung pada identity masing-masing.
        //
        // device_id yang sebelumnya di-hardcode dipakai bersama oleh semua
        // instalasi, sehingga server memetakannya ke satu guest user dan
        // membatasi search/v2 serta play-info dengan HTTP 406 "find no content".
        // ---------------------------------------------------------------
        private const val ID_PREFS = "adidrakor_identity"
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
