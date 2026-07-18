// Bu plugin CloudStream OppaDrama — sumber https://oppa.biz

package com.OppaDrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OppaDramaProvider : MainAPI() {

    // Domain bisa berubah sewaktu-waktu (canonical di-redirect ke IP baru);[span_2](start_span)[span_2](end_span)
    // `canBeOverridden` default-nya `true` jadi user tinggal pakai "Clone site" di settings CloudStream.[span_3](start_span)[span_3](end_span)
    override var mainUrl        = "https://oppa.biz[span_4](start_span)"[span_4](end_span)
    override var name           = "OppaDrama[span_5](start_span)"[span_5](end_span)

    override val hasMainPage    = true[span_6](start_span)[span_6](end_span)
    override var lang           = "id[span_7](start_span)"[span_7](end_span)
    override val hasQuickSearch = true[span_8](start_span)[span_8](end_span)
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)[span_9](start_span)[span_9](end_span)

    // Slow Cloudflare fronted: jalankan homepage satu-satu dengan jeda supaya gak kena rate limit.[span_10](start_span)[span_10](end_span)
    override var sequentialMainPage            = true[span_11](start_span)[span_11](end_span)

    // ------------------------------------------------------------
    //  Homepage
    // ------------------------------------------------------------
    override val mainPage = mainPageOf(
        "${mainUrl}/series/?status=&type=&order=update"                                to "Latest Update",[span_12](start_span)[span_12](end_span)
        "${mainUrl}/series/?status=Ongoing&type=&order=update"                         to "Ongoing",[span_13](start_span)[span_13](end_span)
        "${mainUrl}/series/?status=Completed&type=Drama&order=update"                  to "Completed Drama",[span_14](start_span)[span_14](end_span)
        "${mainUrl}/series/?country%5B%5D=china&type=Drama&order=update"               to "Drama China",[span_15](start_span)[span_15](end_span)
        "${mainUrl}/series/?country%5B%5D=japan&type=Drama&order=update"               to "Drama Jepang",[span_16](start_span)[span_16](end_span)
        "${mainUrl}/series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",[span_17](start_span)[span_17](end_span)
        "${mainUrl}/series/?country%5B%5D=philippines&type=Drama&order=update"         to "Drama Philippines",[span_18](start_span)[span_18](end_span)
        "${mainUrl}/series/?country%5B%5D=taiwan&type=Drama&order=update"              to "Drama Taiwan",[span_19](start_span)[span_19](end_span)
        "${mainUrl}/series/?country%5B%5D=thailand&type=Drama&order=update"            to "Drama Thailand",[span_20](start_span)[span_20](end_span)
        "${mainUrl}/series/?country%5B%5D=usa&type=Drama&order=update"                 to "Drama Western",[span_21](start_span)[span_21](end_span)
        "${mainUrl}/series/?type=Movie&order=update"                                   to "All Movies",[span_22](start_span)[span_22](end_span)
        "${mainUrl}/series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Korean Movie",[span_23](start_span)[span_23](end_span)
        "${mainUrl}/series/?country%5B%5D=japan&type=Movie&order=update"               to "Japan Movie",[span_24](start_span)[span_24](end_span)
        "${mainUrl}/series/?country%5B%5D=china&type=Movie&order=update"               to "Chinese Movie",[span_25](start_span)[span_25](end_span)
        "${mainUrl}/series/?country%5B%5D=thailand&type=Movie&order=update"            to "Thailand Movie",[span_26](start_span)[span_26](end_span)
        "${mainUrl}/series/?country%5B%5D=taiwan&type=Movie&order=update"              to "Taiwan Movie",[span_27](start_span)[span_27](end_span)
        "${mainUrl}/series/?country%5B%5D=philippines&type=Movie&order=update"         to "Philippines Movie",[span_28](start_span)[span_28](end_span)
        "${mainUrl}/series/?country%5B%5D=india&type=Movie&order=update"               to "India Movie",[span_29](start_span)[span_29](end_span)
        "${mainUrl}/series/?country%5B%5D=united-states&type=Movie&order=update"       to "Western Movie[span_30](start_span)"[span_30](end_span)
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // request.data adalah URL lengkap (lihat mainPage di atas).[span_31](start_span)[span_31](end_span)
        val document = app.get(request.data, headers = browserHeaders()).document[span_32](start_span)[span_32](end_span)
        val home = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }[span_33](start_span)[span_33](end_span)
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())[span_34](start_span)[span_34](end_span)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor   = selectFirst("a") ?: return null[span_35](start_span)[span_35](end_span)
        val href     = fixUrlNull(anchor.attr("href")) ?: return null[span_36](start_span)[span_36](end_span)
        val titleRaw = anchor.attr("title").ifBlank { this.selectFirst("div.tt")?.text()?.trim() }[span_37](start_span)[span_37](end_span)
        val title    = titleRaw?.takeIf { it.isNotBlank() } ?: return null[span_38](start_span)[span_38](end_span)
        val poster   = fixUrlNull(this.selectFirst("img")?.getImageAttr())[span_39](start_span)[span_39](end_span)

        // URL mengandung "episode-N" untuk halaman episode tunggal.[span_40](start_span)[span_40](end_span)
        val looksLikeEpisode = Regex(
            "[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE
        ).containsMatchIn(href)[span_41](start_span)[span_41](end_span)

        val cleanTitle = if (looksLikeEpisode) {
            title.replace(Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()[span_42](start_span)[span_42](end_span)
        } else title[span_43](start_span)[span_43](end_span)

        return newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
            this.posterUrl = poster[span_44](start_span)[span_44](end_span)
        }
    }

    // ------------------------------------------------------------
    //  Search
    // ------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "${mainUrl}/?s=${query.encodeUrl()}",
            headers = browserHeaders()
        ).document[span_45](start_span)[span_45](end_span)
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }[span_46](start_span)[span_46](end_span)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)[span_47](start_span)[span_47](end_span)

    // ------------------------------------------------------------
    //  Load
    // ------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders()).document[span_48](start_span)[span_48](end_span)

        // Halaman series -> div.eplister[span_49](start_span)[span_49](end_span)
        if (document.selectFirst("div.eplister ul > li > a") != null) {
            return loadSeries(url, document)[span_50](start_span)[span_50](end_span)
        }
        // Halaman episode tunggal[span_51](start_span)[span_51](end_span)
        return loadEpisode(url, document)[span_52](start_span)[span_52](end_span)
    }

    private suspend fun loadSeries(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null[span_53](start_span)[span_53](end_span)
        val poster = pickPoster(document)[span_54](start_span)[span_54](end_span)

        val info = parseInfo(document)[span_55](start_span)[span_55](end_span)
        val tags  = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }[span_56](start_span)[span_56](end_span)
        val actors = document
            .select("div.spe span:has(b:matchesOwn(^Artis\$)) a")[span_57](start_span)[span_57](end_span)
            .map { it.text().trim() }[span_58](start_span)[span_58](end_span)
            .filter { it.isNotBlank() }[span_59](start_span)[span_59](end_span)
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")[span_60](start_span)[span_60](end_span)
        val recommendations = document.select("div.listupd article.bs").mapNotNull { it.toRecommendation() }[span_61](start_span)[span_61](end_span)

        // Episode list – newest first di DOM; reverse untuk natural numbering[span_62](start_span)[span_62](end_span)
        val episodeAnchors = document.select("div.eplister ul > li > a").toList()[span_63](start_span)[span_63](end_span)
        val episodes = episodeAnchors.reversed().mapIndexed { index, anchor ->
            val href     = anchor.attr("href")[span_64](start_span)[span_64](end_span)
            val epNumber = anchor.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull() ?: (index + 1)[span_65](start_span)[span_65](end_span)
            val epTitle  = anchor.selectFirst("div.epl-title")?.text()?.trim() ?: "Episode $epNumber[span_66](start_span)"[span_66](end_span)
            val epPoster = fixUrlNull(anchor.selectFirst("img")?.getImageAttr())[span_67](start_span)[span_67](end_span)

            newEpisode(href) {
                this.name      = epTitle[span_68](start_span)[span_68](end_span)
                this.episode   = epNumber[span_69](start_span)[span_69](end_span)
                this.posterUrl = epPoster[span_70](start_span)[span_70](end_span)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl       = poster[span_71](start_span)[span_71](end_span)
            this.year            = info.year[span_72](start_span)[span_72](end_span)
            this.plot            = info.plot[span_73](start_span)[span_73](end_span)
            this.tags            = tags[span_74](start_span)[span_74](end_span)
            this.showStatus      = info.status[span_75](start_span)[span_75](end_span)
            this.duration        = info.duration[span_76](start_span)[span_76](end_span)
            this.recommendations = recommendations[span_77](start_span)[span_77](end_span)
            if (info.rating != null) {
                this.score = Score.from(info.rating.toString(), 10)[span_78](start_span)[span_78](end_span)
            }
            if (actors.isNotEmpty()) {
                this.actors = actors.map { ActorData(Actor(it)) }[span_79](start_span)[span_79](end_span)
            }
            if (!trailer.isNullOrBlank()) {
                this.trailers.add(TrailerData(trailer, null, false))[span_80](start_span)[span_80](end_span)
            }
        }
    }

    private suspend fun loadEpisode(url: String, document: org.jsoup.nodes.Document): LoadResponse? {
        val title      = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null[span_81](start_span)[span_81](end_span)
        val seriesName = document.selectFirst("h2[itemprop=partOfSeries], div.infolimit h2")[span_82](start_span)[span_82](end_span)
            ?.text()?.trim()?.takeIf { it.isNotBlank() }[span_83](start_span)[span_83](end_span)
        val poster = pickPoster(document)[span_84](start_span)[span_84](end_span)

        val info = parseInfo(document)[span_85](start_span)[span_85](end_span)
        val tags  = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }[span_86](start_span)[span_86](end_span)
        val actors = document
            .select("div.spe span:has(b:matchesOwn(^Artis\$)) a")[span_87](start_span)[span_87](end_span)
            .map { it.text().trim() }[span_88](start_span)[span_88](end_span)
            .filter { it.isNotBlank() }[span_89](start_span)[span_89](end_span)
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")[span_90](start_span)[span_90](end_span)
        val recommendations = document.select("div.listupd article.bs").mapNotNull { it.toRecommendation() }[span_91](start_span)[span_91](end_span)

        val displayTitle = if (seriesName != null && !title.contains(seriesName, ignoreCase = true)) {
            "$seriesName - $title[span_92](start_span)"[span_92](end_span)
        } else title[span_93](start_span)[span_93](end_span)

        return newMovieLoadResponse(displayTitle, url, TvType.Movie, url) {
            this.posterUrl       = poster[span_94](start_span)[span_94](end_span)
            this.year            = info.year[span_95](start_span)[span_95](end_span)
            this.plot            = info.plot[span_96](start_span)[span_96](end_span)
            this.tags            = tags[span_97](start_span)[span_97](end_span)
            this.duration        = info.duration[span_98](start_span)[span_98](end_span)
            this.recommendations = recommendations[span_99](start_span)[span_99](end_span)
            if (info.rating != null) {
                this.score = Score.from(info.rating.toString(), 10)[span_100](start_span)[span_100](end_span)
            }
            if (actors.isNotEmpty()) {
                this.actors = actors.map { ActorData(Actor(it)) }[span_101](start_span)[span_101](end_span)
            }
            if (!trailer.isNullOrBlank()) {
                this.trailers.add(TrailerData(trailer, null, false))[span_102](start_span)[span_102](end_span)
            }
        }
    }

    private fun Element.toRecommendation(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null[span_103](start_span)[span_103](end_span)
        val href   = fixUrlNull(anchor.attr("href")) ?: return null[span_104](start_span)[span_104](end_span)
        val title  = anchor.attr("title").ifBlank { this.selectFirst("div.tt")?.text()?.trim() }[span_105](start_span)[span_105](end_span)
            ?.takeIf { it.isNotBlank() } ?: return null[span_106](start_span)[span_106](end_span)
        val poster = fixUrlNull(this.selectFirst("img")?.getImageAttr())[span_107](start_span)[span_107](end_span)
        val looksLikeEpisode = Regex(
            "[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE
        ).containsMatchIn(href)[span_108](start_span)[span_108](end_span)
        val type = if (looksLikeEpisode) TvType.TvSeries else TvType.Movie[span_109](start_span)[span_109](end_span)
        val cleanTitle = if (looksLikeEpisode) {
            title.replace(Regex("\\s*Episode\\s*\\d+\\s*$", RegexOption.IGNORE_CASE), "").trim()[span_110](start_span)[span_110](end_span)
        } else title[span_111](start_span)[span_111](end_span)
        return newMovieSearchResponse(cleanTitle, href, type) {
            this.posterUrl = poster[span_112](start_span)[span_112](end_span)
        }
    }

    // ------------------------------------------------------------
    //  loadLinks
    // ------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data, headers = browserHeaders()).document[span_113](start_span)[span_113](end_span)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: failed to fetch $data: ${e.message}")[span_114](start_span)[span_114](end_span)
            return false[span_115](start_span)[span_115](end_span)
        }

        var dispatched = false[span_116](start_span)[span_116](end_span)

        // (1) Primary player iframe[span_117](start_span)[span_117](end_span)
        document.selectFirst("div.player-embed iframe")?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }[span_118](start_span)[span_118](end_span)
            if (src.isNotBlank() && loadExtractor(httpsify(src), data, subtitleCallback, callback)) {
                dispatched = true[span_119](start_span)[span_119](end_span)
            }
        }

        // (2) Mirror dropdown – value-nya adalah base64-encoded <iframe>[span_120](start_span)[span_120](end_span)
        val mirrors = document.select("select.mirror option[value]:not([disabled])")[span_121](start_span)[span_121](end_span)
        for (option in mirrors) {
            val encoded = option.attr("value").trim()[span_122](start_span)[span_122](end_span)
            if (encoded.isBlank() || encoded.equals("Pilih Server Video", ignoreCase = true)) continue[span_123](start_span)[span_123](end_span)
            try {
                val decoded = base64Decode(encoded.replace("\\s".toRegex(), ""))[span_124](start_span)[span_124](end_span)
                val mirrorSrc = Jsoup.parse(decoded).selectFirst("iframe")?.let { el ->
                    el.attr("src").ifBlank { el.attr("data-src") }[span_125](start_span)[span_125](end_span)
                }
                if (!mirrorSrc.isNullOrBlank() &&
                    loadExtractor(httpsify(mirrorSrc), data, subtitleCallback, callback)
                ) {
                    dispatched = true[span_126](start_span)[span_126](end_span)
                }
            } catch (_: Exception) {
                // skip broken mirror
            }
        }

        // (3) Direct download links[span_127](start_span)[span_127](end_span)
        for (a in document.select("div.dlbox li span.e a[href]")) {
            val href = a.attr("href").trim()[span_128](start_span)[span_128](end_span)
            if (href.isNotBlank() && loadExtractor(httpsify(href), data, subtitleCallback, callback)) {
                dispatched = true[span_129](start_span)[span_129](end_span)
            }
        }
        return dispatched[span_130](start_span)[span_130](end_span)
    }

    // ============================================================
    //  Helpers
    // ============================================================

    private data class SeriesInfo(
        val status: ShowStatus,
        val year: Int?,
        val plot: String?,
        val rating: Double?,
        val duration: Int?
    )[span_131](start_span)[span_131](end_span)

    private fun pickPoster(document: org.jsoup.nodes.Document): String? {
        val raw = document.selectFirst("div.bigcontent img, div.thumb img")?.getImageAttr() ?: return null[span_132](start_span)[span_132](end_span)
        return fixUrlNull(raw)[span_133](start_span)[span_133](end_span)
    }

    private fun parseInfo(document: org.jsoup.nodes.Document): SeriesInfo {
        val plot = document.select("div.entry-content p, div.desc p")[span_134](start_span)[span_134](end_span)
            .joinToString("\n") { it.text() }[span_135](start_span)[span_135](end_span)
            .trim()[span_136](start_span)[span_136](end_span)
            .ifBlank { null }[span_137](start_span)[span_137](end_span)

        var status: ShowStatus = ShowStatus.Completed[span_138](start_span)[span_138](end_span)
        var year: Int?          = null[span_139](start_span)[span_139](end_span)
        var duration: Int?      = null[span_140](start_span)[span_140](end_span)
        var rating: Double?     = null[span_141](start_span)[span_141](end_span)

        for (span in document.select("div.spe > span")) {
            val label = span.selectFirst("b")?.text()?.trim()?.removeSuffix(":") ?: continue[span_142](start_span)[span_142](end_span)
            val value = span.ownText().trim()[span_143](start_span)[span_143](end_span)
            when (label.lowercase()) {
                "status" -> status = when (value.lowercase()) {
                    "ongoing" -> ShowStatus.Ongoing[span_144](start_span)[span_144](end_span)
                    else      -> ShowStatus.Completed[span_145](start_span)[span_145](end_span)
                }
                "dirilis" -> year = value.substringBefore('-').trim().takeLast(4).toIntOrNull()[span_146](start_span)[span_146](end_span)
                "durasi"  -> duration = parseDurationMinutes(value)[span_147](start_span)[span_147](end_span)
                "rating"  -> rating = value.toDoubleOrNull()[span_148](start_span)[span_148](end_span)
            }
        }

        if (rating == null) {
            val ratingText = document.selectFirst("div.rating strong")?.text()[span_149](start_span)[span_149](end_span)
            if (ratingText != null) {
                rating = ratingText.replace("Rating", "", ignoreCase = true).trim().toDoubleOrNull()[span_150](start_span)[span_150](end_span)
            }
        }

        return SeriesInfo(status, year, plot, rating, duration)[span_151](start_span)[span_151](end_span)
    }

    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null[span_152](start_span)[span_152](end_span)
        val hours   = Regex("(\\d+)\\s*hr").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0[span_153](start_span)[span_153](end_span)
        val minutes = Regex("(\\d+)\\s*min").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0[span_154](start_span)[span_154](end_span)
        val total   = hours * 60 + minutes[span_155](start_span)[span_155](end_span)
        return if (total > 0) total else null[span_156](start_span)[span_156](end_span)
    }

    // FIX TERBARU: Mengatasi masalah lazy load dan memproteksi pemuatan gambar dengan memaksakan https://
    private fun Element.getImageAttr(): String? {
        fun cleanup(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return url
                .replace(Regex("[?&]resize=\\d+,\\d+"), "")
                .replace(Regex("[?&]quality=\\d+"), "")
                .replace("http://", "https://") 
        }
        
        val dataSrc     = attr("abs:data-src").takeIf { it.isNotBlank() }
        val dataLazySrc = attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
        val srcset      = attr("abs:srcset").takeIf { it.isNotBlank() }?.substringBefore(" ")
        val src         = attr("abs:src").takeIf { it.isNotBlank() }

        return cleanup(dataSrc ?: dataLazySrc ?: srcset ?: src)
    }

    /**
     * Browser-like headers supaya request dari plugin gak gampang di-flag
     * Cloudflare sebagai bot.[span_157](start_span)[span_157](end_span)
     */
    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",[span_158](start_span)[span_158](end_span)
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",[span_159](start_span)[span_159](end_span)
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",[span_160](start_span)[span_160](end_span)
        "Accept-Encoding" to "gzip, deflate",[span_161](start_span)[span_161](end_span)
        "Sec-Fetch-Dest" to "document",[span_162](start_span)[span_162](end_span)
        "Sec-Fetch-Mode" to "navigate",[span_163](start_span)[span_163](end_span)
        "Sec-Fetch-Site" to "none",[span_164](start_span)[span_164](end_span)
        "Sec-Fetch-User" to "?1",[span_165](start_span)[span_165](end_span)
        "Upgrade-Insecure-Requests" to "1[span_166](start_span)"[span_166](end_span)
    )

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")[span_167](start_span)[span_167](end_span)

    companion object {
        private const val TAG = "OppaDrama[span_168](start_span)"[span_168](end_span)
    }
}
