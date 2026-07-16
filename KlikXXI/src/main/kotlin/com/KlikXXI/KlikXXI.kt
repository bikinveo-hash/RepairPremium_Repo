package com.KlikXXI

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KlikXXI : MainAPI() {
    // Pemulihan Domain Utama berdasarkan Hasil Analisis SPA Berkas Artefak Aktual
    override var mainUrl = "https://klikxxi.shop"
    override var name    = "KlikXXI"
    override val hasMainPage       = true
    override var lang              = "id"
    override val hasDownloadSupport = true
    override val supportedTypes    = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "KlikXXI"

    // ─────────────────────────────────────────────────────────────────────────
    //  MAIN PAGE
    // ─────────────────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/?s=&search=advanced&post_type=movie&index=&orderby=&genre=&movieyear=&country=&quality=" to "Latest Movies",
        "$mainUrl/tv"                     to "TV Series",
        "$mainUrl/category/action/"       to "Action",
        "$mainUrl/category/adventure/"    to "Adventure",
        "$mainUrl/category/crime/"        to "Crime",
        "$mainUrl/category/drama/"        to "Drama",
        "$mainUrl/category/korea/"        to "Korea",
        "$mainUrl/category/fantasy/"      to "Fantasy",
        "$mainUrl/category/horror/"       to "Horror",
        "$mainUrl/category/india-series/" to "India Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("?") ->
                if (page <= 1) request.data
                else request.data.replace("/?", "/page/$page/?")
            else ->
                if (page <= 1) request.data
                else "${request.data.removeSuffix("/")}/page/$page/"
        }
        val items = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
            .select("article.item, article.item-infinite, div.gmr-item-modulepost")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SEARCH
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv", headers = mapOf("Referer" to "$mainUrl/")).document
            .select("article.item, article.item-infinite")
            .mapNotNull { it.toSearchResult() }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOAD (Detail Halaman Film/Series)
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc   = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
        val title = doc.selectFirst("h1.entry-title")?.text()
            ?.replace("Streaming Film", "")?.trim() ?: ""

        val posterRaw = doc
            .selectFirst(".gmr-movie-data img, .content-thumbnail img, figure img")
            ?.let { it.attr("data-lazy-src").ifEmpty { it.attr("src") } }
        val poster = fixUrlNull(posterRaw)?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")

        val tags    = doc.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val year    = doc.selectFirst(".gmr-moviedata:contains(Year) a")?.text()?.toIntOrNull()
        val plot    = doc.selectFirst(".entry-content-single p, .gmr-movie-content")?.text()
        val rating  = doc.selectFirst(".gmr-rating-item")
            ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()

        val epElements = doc.select(".gmr-season-episodes a.button-shadow")
        return if (epElements.isNotEmpty()) {
            val episodes = epElements.mapNotNull {
                val href   = it.attr("href")
                val epName = it.text()
                if (epName.contains("Batch", true)) return@mapNotNull null
                newEpisode(href) {
                    this.name    = epName
                    this.season  = Regex("""S(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    this.episode = Regex("""Eps(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                }
            }.reversed()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = plot
                this.tags = tags; this.score = Score.from10(rating)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = plot
                this.tags = tags; this.score = Score.from10(rating)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOAD LINKS (Gerbang Utama Ekstraksi Kompatibilitas Framework)
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isDataJob: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "══ loadLinks ══ $data")

        val doc    = app.get(data, headers = mapOf("Referer" to "$mainUrl/")).document
        val ajaxId = doc.selectFirst(".gmr-server-wrap, #muvipro_player_content_id")
            ?.attr("data-id")

        if (ajaxId.isNullOrBlank()) {
            Log.e(TAG, "❌ data-id tidak ditemukan di halaman.")
            return false
        }
        Log.d(TAG, "✅ data-id terpilih: $ajaxId")

        // NOT VERIFIED: Struktur player tabs dapat berganti bergantung pada pembaruan tema WordPress
        val servers = doc
            .select("ul.muvipro-player-tabs li a, .gmr-player-nav li a")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (href.startsWith("#p")) href.removePrefix("#p") else null
            }.distinct()

        Log.d(TAG, "✅ ${servers.size} Server Tab Ditemukan: $servers")
        if (servers.isEmpty()) {
            Log.e(TAG, "❌ Tidak ada server tab tersedia.")
            return false
        }

        servers.forEach { serverNum ->
            Log.d(TAG, "→ Memproses Tab Server: p$serverNum")

            val ajaxResponse = try {
                app.post(
                    url     = "$mainUrl/wp-admin/admin-ajax.php",
                    data    = mapOf(
                        "action"  to "muvipro_player_content",
                        "tab"     to "p$serverNum",
                        "post_id" to ajaxId
                    ),
                    referer = data,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
                    )
                ).text
            } catch (e: Exception) {
                Log.e(TAG, "❌ Jaringan AJAX gagal pada p$serverNum: ${e.message}")
                return@forEach
            }

            // Ekstraksi rute src iframe dari payload markup WordPress
            val rawSrc =
                Regex("""(?i)src='([^"']+)""").find(ajaxResponse)?.groupValues?.get(1)
                    ?: Regex("""(?i)src="([^"']+)""").find(ajaxResponse)?.groupValues?.get(1)

            if (rawSrc == null) {
                Log.w(TAG, "⚠️ Tidak ada tautan iframe src di p$serverNum")
                return@forEach
            }

            val iframeUrl = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
            Log.d(TAG, "   Iframe Target URL: $iframeUrl")

            // Pendelegasian ke Modul Ekstraktor Regex Murni
            if (iframeUrl.contains("strp2p.site", ignoreCase = true) 
                || iframeUrl.contains("upns.one", ignoreCase = true)
                || iframeUrl.contains("klikxxi", ignoreCase = true)) {
                Log.d(TAG, "   → Mendelegasikan ke Mesin Ekstraktor Strp2p")
                Strp2p().getSafeUrl(iframeUrl, data, subtitleCallback, callback)
            } else {
                Log.d(TAG, "   → Menggunakan Fallback Core Ekstraktor Framework")
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a     = selectFirst(".entry-title a") ?: return null
        val title = a.text()
        val href  = a.attr("href")
        val poster = fixUrlNull(
            selectFirst("img")?.let {
                it.attr("data-lazy-src").ifEmpty { it.attr("src") }
            }
        )?.replace(Regex("-[0-9]+x[0-9]+(?=\\.)"), "")
        val quality = getQualityFromString(
            selectFirst(".gmr-quality-item, .quality, .qualitylabel, span.quality")?.text())
        val score = Score.from10(
            selectFirst(".gmr-rating-item, .rating, star-rating")
                ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull())
        val isTv = selectFirst(".gmr-numbeps") != null || href.contains("/tv/")
        return if (isTv)
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster; this.quality = quality; this.score = score }
        else
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster; this.quality = quality; this.score = score }
    }
}
