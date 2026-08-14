package com.Cinemacity

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * PORT v1 — mengikuti CINEMACITY PORTING CONTRACT v1 + dua koreksi FINAL REVIEW.
 *
 * Nama class dipertahankan `Cinemacity` seperti aslinya
 * (com.Cinemacity.Cinemacity extends MainAPI).
 *
 * SCOPE v1 : main page · search · load (movie + series) · subtitle ·
 *            loadLinks jalur HLS · cookie · Cloudflare interceptor
 * DITUNDA  : jalur download (buildDownloadLinks / makeDownloadHref /
 *            extractQuality), WebView challenge Cloudflare
 */
class Cinemacity : MainAPI() {

    override var mainUrl = "https://cinemacity.cc"          // [PROVEN] <init> @0003
    override var name = "CinemaCity"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        /**
         * [PROVEN] private static final, ditulis SEKALI di <clinit> @000a,
         * dibaca load @046b dan loadLinks @02e5/@02fa.
         *
         * NILAI SENGAJA TIDAK DICANTUMKAN. Nilai aslinya adalah kredensial
         * hardcoded yang kita redaksi sepanjang investigasi. Isi sendiri
         * dari plugin asli sebelum dipakai.
         */
        private const val loginCookie = "" // [REDACTED_SECRET] — isi manual

        // [PROVEN] dibuat sekali di load @12c5 / @12ce, IGNORE_CASE
        private val seasonRegex = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val episodeRegex = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)

        // [PROVEN] load @0770
        private val imdbRegex = Regex("""tt\d+""")

        // [PROVEN] search: fallback bila input[name=dle_hash] tidak ada
        private val dleHashRegex = Regex("""dle_login_hash\s*=\s*'([^']+)'""")

        // [PROVEN] parseSubtitles: pola per-entri
        private val subtitleRegex = Regex("""\[(.+?)](https?://.+)""")

        // [PROVEN] isCloudflareBlocked: empat penanda
        private val cfMarkers = listOf(
            "<title>just a moment",
            "id=\"challenge-form\"",
            "cf-browser-verification",
            "checking your browser before accessing"
        )

        private const val TAG = "Phisher"   // [PROVEN] tag Log.d di load @12e5
    }

    // ---------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------

    /**
     * [PROVEN] appGet -> Requests.get$default @0107, MASK 3580.
     * Yang DIISI pemanggil: url, headers, interceptor. Sisanya bawaan.
     */
    private suspend fun appGet(url: String, headers: Map<String, String> = emptyMap()) =
        app.get(url, headers = headers, interceptor = CinemacityCFBypassInterceptor)

    /** [PROVEN] isCloudflareBlocked(code, text) */
    private fun isCloudflareBlocked(code: Int, text: String): Boolean {
        if (code != 403 && code != 503) {
            // Plugin asli memeriksa getCode lalu getText. Nilai kode persisnya
            // tidak ikut ter-slice, jadi pemeriksaan teks tetap dijalankan.
        }
        val lower = text.lowercase()
        return cfMarkers.any { lower.contains(it) }
    }

    /** Cookie untuk request ke cinemacity.cc. */
    private fun siteCookieHeader(): Map<String, String> =
        mapOf("Cookie" to buildCookieValue())

    /**
     * [PROVEN] loadLinks @02cc–@0305:
     *   cfCookies kosong  -> loginCookie saja
     *   cfCookies terisi  -> loginCookie + "; " + cfCookies
     */
    private fun buildCookieValue(): String {
        val cf = CinemacityPlugin.cfCookies
        return if (cf.isEmpty()) loginCookie else "$loginCookie; $cf"
    }

    // ---------------------------------------------------------------
    // MAIN PAGE
    // ---------------------------------------------------------------

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/movies/" to "Movies",          // [PROVEN] literal '/movies/'
        "$mainUrl/tv-series/" to "TV Series"     // [PROVEN] literal '/tv-series/'
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // [PROVEN] literal '/page/' dipakai untuk paginasi (getMainPage @0084)
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = appGet(url, siteCookieHeader()).document

        // [PROVEN] selector daftar item
        val items = doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    /**
     * [PROVEN] toSearchResult memakai:
     *   href  : 'div.dar-short_bg a ' -> attr href
     *   title : 'div.dar-short_bg.e-cover > div > span'
     *   link  : 'div.dar-short_bg.e-cover > div span:nth-child(2) > a'
     *   poster: attr 'data-vbg' / 'src'
     * Tipe ditentukan oleh keberadaan '/tv-series/' pada href.
     */
    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = this.select("div.dar-short_bg a").firstOrNull()?.attr("href")
            ?: this.select("a:not([data-highslide])").firstOrNull()?.attr("href")
            ?: return null
        val fixedHref = fixUrl(href)

        val title = this.select("div.dar-short_bg.e-cover > div > span")
            .firstOrNull()?.text()?.trim()
            ?: this.select("div.dar-short_bg.e-cover > div span:nth-child(2) > a")
                .firstOrNull()?.text()?.trim()
            ?: return null

        val poster = this.select("[data-vbg]").firstOrNull()?.attr("data-vbg")
            ?.takeIf { it.isNotBlank() }
            ?: this.select("img").firstOrNull()?.attr("src")

        // [PROVEN] pemilih tipe berdasarkan '/tv-series/'
        return if (fixedHref.contains("/tv-series/")) {
            newTvSeriesSearchResponse(title, fixedHref, TvType.TvSeries) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        } else {
            newMovieSearchResponse(title, fixedHref, TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        }
    }

    // ---------------------------------------------------------------
    // SEARCH — dua tahap DataLife Engine
    // ---------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        // [PROVEN] tahap 1: ambil dle_hash
        val seedUrl = "$mainUrl/?do=search&subaction=search&search_start=0&full_search=0&story="
        val seed = appGet(seedUrl, siteCookieHeader())

        if (isCloudflareBlocked(seed.code, seed.text)) {
            // [PROVEN] pesan asli
            throw ErrorLoadingException(
                "CinemaCity: Cloudflare blocked. Go to Settings → Bypass Cloudflare."
            )
        }

        val doc = seed.document
        val dleHash = doc.select("input[name=dle_hash]").firstOrNull()?.attr("value")
            ?.takeIf { it.isNotBlank() }
            ?: dleHashRegex.find(seed.text)?.groupValues?.getOrNull(1)

        // [PROVEN] tahap 2: POST ajax.php, mask 57308 -> url, headers, data, interceptor
        val postHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to seedUrl,
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Cookie" to buildCookieValue()
        )
        val data = mutableMapOf("story" to query)
        if (!dleHash.isNullOrBlank()) data["dle_hash"] = dleHash

        val res = app.post(
            "$mainUrl/engine/mods/dle_search/ajax.php",
            headers = postHeaders,
            data = data,
            interceptor = CinemacityCFBypassInterceptor
        )

        return res.document.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val res = appGet(url, siteCookieHeader())
        if (isCloudflareBlocked(res.code, res.text)) {
            throw ErrorLoadingException(
                "CinemaCity: Cloudflare blocked. Go to Settings → Bypass Cloudflare."
            )
        }
        val doc = res.document

        // ---------- metadata lokal [PROVEN] ----------
        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.title() }
        val poster = doc.select("meta[property=og:image]").attr("content")
            .takeIf { it.isNotBlank() }
        val background = doc.select("div.dar-full_bg a").attr("data-vbg")
            .ifBlank { doc.select("div.dar-full_bg.e-cover > div").attr("data-vbg") }
            .takeIf { it.isNotBlank() }
        val plot = doc.select("#about div.ta-full_text1").text().trim()
            .takeIf { it.isNotBlank() }

        // [PROVEN] '/tv-series/' menentukan tipe
        val tvType = if (url.contains("/tv-series/")) TvType.TvSeries else TvType.Movie

        // ---------- IMDb -> TMDB -> Cinemeta [PROVEN rantainya] ----------
        val imdbId = doc.select("div.ta-full_rating1 > div")
            .firstOrNull()?.attr("onclick")
            ?.let { imdbRegex.find(it)?.value }

        // ---------- PlayerJS [PROVEN] ----------
        // @11fc select -> @1205 getOrNull(1) -> @120e data()
        val script = doc.select("script:containsData(atob)").getOrNull(1)?.data()
            ?: throw ErrorLoadingException("PlayerJS not found; only torrent links available")

        // @1219 substringAfter -> @121f substringBefore -> @1223 base64Decode (SATU kali)
        val decoded = base64Decode(
            script.substringAfter("atob(\"").substringBefore("\")")
        )

        // @122c -> @1232 -> @1236
        val raw = decoded.substringAfter("new Playerjs(").substringBeforeLast(");")
        val playerRoot = JSONObject(raw)

        // @123c opt("file"); @1240 null -> error
        val fileValue = playerRoot.opt("file")
            ?: throw ErrorLoadingException("PlayerJS: missing file field")

        val fileArray = normalizeFile(fileValue)

        // [PROVEN] plugin asli mencetak array ternormalisasi dengan tag 'Phisher'
        Log.d(TAG, fileArray.toString())

        // ---------- MOVIE streamUrl ----------
        // KOREKSI A.3: dihitung LEBIH DULU, tanpa dipagari TvType.
        val movieData = buildMovieData(playerRoot, fileArray)

        // ---------- pemilih cabang [PROVEN] @1391 ----------
        return if (tvType != TvType.TvSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                addImdbId(imdbId)
            }
        } else {
            val episodes = buildEpisodes(fileArray)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                addImdbId(imdbId)
            }
        }
    }

    /**
     * [PROVEN] normalisasi @1243–@12c2. Empat cabang, hasil selalu JSONArray.
     */
    private fun normalizeFile(fileValue: Any): JSONArray {
        if (fileValue is JSONArray) return fileValue

        if (fileValue is String) {
            val s = fileValue.trim()
            if (s.isBlank()) throw ErrorLoadingException("PlayerJS: empty file string")

            // "[...]" -> JSONArray langsung
            if (s.startsWith("[") && s.endsWith("]")) return JSONArray(s)

            // "{...}" -> dibungkus jadi array satu objek
            if (s.startsWith("{") && s.endsWith("}")) {
                return JSONArray().put(JSONObject(s))
            }

            // string polos -> DIBUNGKUS menjadi {"file": s}   @12ac–@12be
            return JSONArray().put(JSONObject().put("file", s))
        }

        throw ErrorLoadingException("PlayerJS: unsupported file type")
    }

    /**
     * MOVIE. [PROVEN] @12ee–@1383.
     *   arr[0] non-null  (@12f4)
     *   DAN arr[0] TIDAK punya "folder"  (@1303 if-nez)
     *   -> streamUrl = arr[0].optString("file"), disaring isBlank
     * Bila streamUrl kosong (@1360) JSON tidak dibangun.
     */
    private fun buildMovieData(playerRoot: JSONObject, arr: JSONArray): String? {
        val first = arr.optJSONObject(0) ?: return null
        if (first.has("folder")) return null

        val streamUrl = first.optString("file").takeIf { it.isNotBlank() } ?: return null

        // Subtitle movie: akar dulu, fallback arr[0]  [PROVEN] @132a / @133c
        val rootSub = playerRoot.opt("subtitle") as? String
        val subtitleSource = rootSub ?: (arr.optJSONObject(0)?.opt("subtitle") as? String)

        return JSONObject()
            .put("streamUrl", streamUrl)
            .put("subtitleTracks", parseSubtitles(subtitleSource))
            .toString()
    }

    /**
     * TV SERIES. [PROVEN] @1394–@151f.
     *
     * file[] -> Season { title, folder[] }
     *              folder[] -> Episode { title, file, subtitle, folder[] }
     */
    private fun buildEpisodes(arr: JSONArray): List<Episode> {
        val episodes = mutableListOf<Episode>()

        for (i in 0 until arr.length()) {
            val season = arr.getJSONObject(i)                       // @139c
            val seasonNo = seasonRegex.find(season.optString("title"))
                ?.groupValues?.getOrNull(1)?.toIntOrNull()          // @13a7–@13d8

            val folder = season.optJSONArray("folder") ?: continue  // @13dc

            for (j in 0 until folder.length()) {
                val ep = folder.getJSONObject(j)                    // @13f3
                val epNo = episodeRegex.find(ep.optString("title"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()      // @13fa–@142b

                val urls = mutableListOf<String>()                  // @142f

                // file langsung DULU  @1437 -> @1459
                ep.optString("file").takeIf { it.isNotBlank() }?.let { urls.add(it) }

                // folder bersarang SELALU diproses  @145e -> @147d -> @14a0
                ep.optJSONArray("folder")?.let { nested ->
                    for (k in 0 until nested.length()) {
                        val src = nested.optJSONObject(k) ?: continue
                        src.optString("file").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    }
                }

                // @14c2/@14c6 : kosong -> Episode TIDAK dibuat
                if (urls.isEmpty()) continue

                // [PROVEN] kunci "musim:episode" @14c8–@14db
                // (dibentuk di plugin asli untuk mengambil metadata episode.
                //  Sumber metadata itu di luar scope v1, jadi kunci hanya
                //  dipakai sebagai penanda.)
                val key = "$seasonNo:$epNo"

                // streams[] tetap String utuh  @14fc
                val data = JSONObject()
                    .put("streams", JSONArray(urls))
                    .put("subtitleTracks", parseSubtitles(ep.optString("subtitle")))
                    .toString()

                episodes.add(
                    newEpisode(data) {
                        this.name = ep.optString("title").takeIf { it.isNotBlank() }
                        this.season = seasonNo
                        this.episode = epNo
                    }
                )
            }
        }
        return episodes
    }

    /**
     * [PROVEN] parseSubtitles: split ',', pola \[(.+?)](https?://.+)
     * -> JSONArray of { language, subtitleUrl }
     */
    private fun parseSubtitles(source: String?): JSONArray {
        val out = JSONArray()
        if (source.isNullOrBlank()) return out
        source.split(",").forEach { part ->
            val m = subtitleRegex.find(part.trim()) ?: return@forEach
            val lang = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val subUrl = m.groupValues.getOrNull(2)?.trim().orEmpty()
            if (lang.isNotEmpty() && subUrl.isNotEmpty()) {
                out.put(
                    JSONObject()
                        .put("language", lang)
                        .put("subtitleUrl", subUrl)
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // LOADLINKS — [PROVEN] TANPA network request
    // ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val obj = JSONObject(data)                                  // @01a9

        // ---------- subtitleTracks : elemen JSONObject ----------
        obj.optJSONArray("subtitleTracks")?.let { subs ->           // @01b0
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)                       // @01d0
                subtitleCallback(
                    newSubtitleFile(
                        s.getString("language"),                    // @01d9
                        s.getString("subtitleUrl")                  // @01e1
                    )
                )
            }
        }

        // ---------- streams : elemen String ----------
        val urls = mutableListOf<String>()                          // @0255
        obj.optJSONArray("streams")?.let { streams ->               // @025c
            for (i in 0 until streams.length()) {
                streams.optString(i).takeIf { it.isNotBlank() }     // @026b/@027b
                    ?.let { urls.add(it) }
            }
        }

        // ---------- fallback HANYA bila streams kosong ----------
        if (urls.isEmpty()) {                                       // @029c/@02a0
            obj.optString("streamUrl").takeIf { it.isNotBlank() }
                ?.let { urls.add(it) }
        }

        // ---------- early return bila tetap kosong ----------
        if (urls.isEmpty()) return false                            // @02c1/@02c5

        // ---------- header sama untuk semua link (KOREKSI E.2) ----------
        val linkHeaders = mapOf("Cookie" to buildCookieValue())     // @02cc–@0302

        urls.forEach { streamUrl ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • HLS • Master ",                 // @0345 (spasi akhir)
                    url = streamUrl,
                    type = INFER_TYPE                               // @0350
                ) {
                    this.referer = mainUrl
                    this.headers = linkHeaders
                }
            )
        }

        // DITUNDA v1: buildDownloadLinks / makeDownloadHref / extractQuality
        // (§F.4 kontrak — pemilihan MP4, {base}, dan label kualitas belum terbukti)

        return true
    }
}
