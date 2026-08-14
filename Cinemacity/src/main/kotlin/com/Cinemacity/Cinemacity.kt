package com.Cinemacity

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
// [REVISI]: Import eksplisit method LoadResponse.Companion untuk scope DSL
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

class Cinemacity : MainAPI() {

    override var mainUrl = "https://cinemacity.cc"
    override var name = "CinemaCity"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val loginCookie = "" // [REDACTED_SECRET] — isi manual

        private val seasonRegex = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val episodeRegex = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val imdbRegex = Regex("""tt\d+""")
        private val dleHashRegex = Regex("""dle_login_hash\s*=\s*'([^']+)'""")
        private val subtitleRegex = Regex("""\[(.+?)](https?://.+)""")

        private val cfMarkers = listOf(
            "<title>just a moment",
            "id=\"challenge-form\"",
            "cf-browser-verification",
            "checking your browser before accessing"
        )

        private const val TAG = "Phisher"
    }

    private suspend fun appGet(url: String, headers: Map<String, String> = emptyMap()) =
        app.get(url, headers = headers, interceptor = CinemacityCFBypassInterceptor)

    private fun isCloudflareBlocked(code: Int, text: String): Boolean {
        val lower = text.lowercase()
        return cfMarkers.any { lower.contains(it) }
    }

    private fun siteCookieHeader(): Map<String, String> =
        mapOf("Cookie" to buildCookieValue())

    private fun buildCookieValue(): String {
        val cf = CinemacityPlugin.cfCookies
        // [REVISI]: Mengganti isEmpty() dengan isNullOrEmpty() karena cf bertipe String?
        return if (cf.isNullOrEmpty()) loginCookie else "$loginCookie; $cf"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/tv-series/" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = appGet(url, siteCookieHeader()).document

        val items = doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

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

    override suspend fun search(query: String): List<SearchResponse> {
        val seedUrl = "$mainUrl/?do=search&subaction=search&search_start=0&full_search=0&story="
        val seed = appGet(seedUrl, siteCookieHeader())

        if (isCloudflareBlocked(seed.code, seed.text)) {
            throw ErrorLoadingException(
                "CinemaCity: Cloudflare blocked. Go to Settings → Bypass Cloudflare."
            )
        }

        val doc = seed.document
        val dleHash = doc.select("input[name=dle_hash]").firstOrNull()?.attr("value")
            ?.takeIf { it.isNotBlank() }
            ?: dleHashRegex.find(seed.text)?.groupValues?.getOrNull(1)

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

    override suspend fun load(url: String): LoadResponse? {
        val res = appGet(url, siteCookieHeader())
        if (isCloudflareBlocked(res.code, res.text)) {
            throw ErrorLoadingException(
                "CinemaCity: Cloudflare blocked. Go to Settings → Bypass Cloudflare."
            )
        }
        val doc = res.document

        val title = doc.select("meta[property=og:title]").attr("content")
            .ifBlank { doc.title() }
        val poster = doc.select("meta[property=og:image]").attr("content")
            .takeIf { it.isNotBlank() }
        val background = doc.select("div.dar-full_bg a").attr("data-vbg")
            .ifBlank { doc.select("div.dar-full_bg.e-cover > div").attr("data-vbg") }
            .takeIf { it.isNotBlank() }
        val plot = doc.select("#about div.ta-full_text1").text().trim()
            .takeIf { it.isNotBlank() }

        val tvType = if (url.contains("/tv-series/")) TvType.TvSeries else TvType.Movie

        val imdbId = doc.select("div.ta-full_rating1 > div")
            .firstOrNull()?.attr("onclick")
            ?.let { imdbRegex.find(it)?.value }

        val script = doc.select("script:containsData(atob)").getOrNull(1)?.data()
            ?: throw ErrorLoadingException("PlayerJS not found; only torrent links available")

        val decoded = base64Decode(
            script.substringAfter("atob(\"").substringBefore("\")")
        )

        val raw = decoded.substringAfter("new Playerjs(").substringBeforeLast(");")
        val playerRoot = JSONObject(raw)

        val fileValue = playerRoot.opt("file")
            ?: throw ErrorLoadingException("PlayerJS: missing file field")

        val fileArray = normalizeFile(fileValue)

        Log.d(TAG, fileArray.toString())

        val movieData = buildMovieData(playerRoot, fileArray)

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

    private fun normalizeFile(fileValue: Any): JSONArray {
        if (fileValue is JSONArray) return fileValue

        if (fileValue is String) {
            val s = fileValue.trim()
            if (s.isBlank()) throw ErrorLoadingException("PlayerJS: empty file string")

            if (s.startsWith("[") && s.endsWith("]")) return JSONArray(s)

            if (s.startsWith("{") && s.endsWith("}")) {
                return JSONArray().put(JSONObject(s))
            }

            return JSONArray().put(JSONObject().put("file", s))
        }

        throw ErrorLoadingException("PlayerJS: unsupported file type")
    }

    private fun buildMovieData(playerRoot: JSONObject, arr: JSONArray): String? {
        val first = arr.optJSONObject(0) ?: return null
        if (first.has("folder")) return null

        val streamUrl = first.optString("file").takeIf { it.isNotBlank() } ?: return null

        val rootSub = playerRoot.opt("subtitle") as? String
        val subtitleSource = rootSub ?: (arr.optJSONObject(0)?.opt("subtitle") as? String)

        return JSONObject()
            .put("streamUrl", streamUrl)
            .put("subtitleTracks", parseSubtitles(subtitleSource))
            .toString()
    }

    private fun buildEpisodes(arr: JSONArray): List<Episode> {
        val episodes = mutableListOf<Episode>()

        for (i in 0 until arr.length()) {
            val season = arr.getJSONObject(i)
            val seasonNo = seasonRegex.find(season.optString("title"))
                ?.groupValues?.getOrNull(1)?.toIntOrNull()

            val folder = season.optJSONArray("folder") ?: continue

            for (j in 0 until folder.length()) {
                val ep = folder.getJSONObject(j)
                val epNo = episodeRegex.find(ep.optString("title"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

                val urls = mutableListOf<String>()

                ep.optString("file").takeIf { it.isNotBlank() }?.let { urls.add(it) }

                ep.optJSONArray("folder")?.let { nested ->
                    for (k in 0 until nested.length()) {
                        val src = nested.optJSONObject(k) ?: continue
                        src.optString("file").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    }
                }

                if (urls.isEmpty()) continue

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val obj = JSONObject(data)

        obj.optJSONArray("subtitleTracks")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                subtitleCallback(
                    newSubtitleFile(
                        s.getString("language"),
                        s.getString("subtitleUrl")
                    )
                )
            }
        }

        val urls = mutableListOf<String>()
        obj.optJSONArray("streams")?.let { streams ->
            for (i in 0 until streams.length()) {
                streams.optString(i).takeIf { it.isNotBlank() }
                    ?.let { urls.add(it) }
            }
        }

        if (urls.isEmpty()) {
            obj.optString("streamUrl").takeIf { it.isNotBlank() }
                ?.let { urls.add(it) }
        }

        if (urls.isEmpty()) return false

        val linkHeaders = mapOf("Cookie" to buildCookieValue())

        urls.forEach { streamUrl ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • HLS • Master ",
                    url = streamUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.headers = linkHeaders
                }
            )
        }

        return true
    }
}
