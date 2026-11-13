package com.kurakura21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder

/** Catalogue metadata stays separate from the bounded, HTTP-only playback resolver. */
class KuraKura21Provider : MainAPI() {
    override var name = "KURAKURA21"
    override var mainUrl = "https://kurakura21.com"
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = false
    private val headers get() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8", "Referer" to "$mainUrl/"
    )
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru", "$mainUrl/genre/bioskop/" to "Bioskop",
        "$mainUrl/genre/jav-sub-indo/" to "JAV Sub Indo", "$mainUrl/genre/jav-english-sub/" to "JAV English Sub",
        "$mainUrl/genre/indo-18/" to "Indonesia 18+", "$mainUrl/genre/korea-18/" to "Korea 18+",
        "$mainUrl/genre/barat-18/" to "Barat 18+", "$mainUrl/genre/cina-18/" to "China 18+"
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val doc = getPage(url)
        return newHomePageResponse(request.name, parseListing(doc),
            hasNext = doc.selectFirst("a.next.page-numbers, a[rel=next], .pagination a.next, .wp-pagenavi a.nextpostslink") != null)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return parseListing(getPage("$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}"))
    }
    override suspend fun load(url: String): LoadResponse? {
        val selected = pageUrl(url) ?: return null
        val doc = getPage(selected)
        val title = KuraKura21Metadata.detailTitle(doc) ?: return null
        val root = KuraKura21Metadata.detailRoot(doc)
        val structured = KuraKura21Metadata.movieJson(doc, title)
        val poster = KuraKura21Metadata.detailPoster(doc, root, structured)
        val plot = KuraKura21Metadata.plot(doc, root, structured)
        val genres = KuraKura21Metadata.genres(root, structured)
        return newMovieLoadResponse(title, selected, TvType.NSFW, selected) {
            posterUrl = poster
            this.plot = plot
            tags = genres
            year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.value?.toIntOrNull()
            recommendations = parseListing(doc).filter { KuraKura21Metadata.pageKey(it.url) != KuraKura21Metadata.pageKey(selected) }.take(12)
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean {
        val selected = pageUrl(data) ?: return false
        return KuraKura21Resolver(mainUrl, headers, name).load(selected, subtitleCallback, callback)
    }
    private suspend fun getPage(url: String): Document {
        val response = app.get(url, headers = headers, timeout = 12)
        if (response.code !in 200..299) throw ErrorLoadingException("KURAKURA21 HTTP ${response.code}")
        return response.document.apply { setBaseUri(response.url) }
    }
    private fun pageUrl(raw: String): String? = KuraKura21Metadata.absolute(raw, mainUrl)?.takeIf(KuraKura21Metadata::isDetail)
    private fun parseListing(doc: Document): List<SearchResponse> {
        val cards = doc.select("article.item, article.post, .gmr-item-modulepost, .items .item, .movies .item, .movie-item, .ml-item, .result-item, .latest-movie .item, .post-item, .movie")
        val records = cards.mapNotNull { card ->
            // The detail article can contain recommendation cards; never pair their link with its main poster.
            if (card.selectFirst("h1.entry-title, h1[itemprop=name]") != null) return@mapNotNull null
            val link = card.selectFirst("h2.entry-title > a[rel=bookmark], h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .title a[href]")
                ?: card.selectFirst("a[title][href]") ?: return@mapNotNull null
            val url = KuraKura21Metadata.absolute(link.attr("href"), doc.baseUri())?.takeIf(KuraKura21Metadata::isDetail) ?: return@mapNotNull null
            val image = card.selectFirst(".content-thumbnail img.wp-post-image, img.wp-post-image, img")
            val title = KuraKura21Metadata.cardTitle(link, image).takeIf(String::isNotBlank) ?: return@mapNotNull null
            KuraKura21Metadata.Card(title, url, KuraKura21Metadata.image(image, doc.baseUri()))
        }.ifEmpty {
            doc.select("h2 > a[href], h3 > a[href], .entry-title a[href]").mapNotNull { link ->
                val url = KuraKura21Metadata.absolute(link.attr("href"), doc.baseUri())?.takeIf(KuraKura21Metadata::isDetail) ?: return@mapNotNull null
                val image = link.parent()?.parent()?.selectFirst("img")
                val title = KuraKura21Metadata.cardTitle(link, image).takeIf(String::isNotBlank) ?: return@mapNotNull null
                KuraKura21Metadata.Card(title, url, KuraKura21Metadata.image(image, doc.baseUri()))
            }
        }
        return records.groupBy { KuraKura21Metadata.pageKey(it.url) }.values.map { duplicates ->
            val item = duplicates.first()
            val title = duplicates.maxByOrNull { it.title.length }!!.title
            val picture = duplicates.mapNotNull { it.image }.maxByOrNull { it.score }
            newMovieSearchResponse(title, item.url, TvType.NSFW) { posterUrl = picture?.url }
        }
    }
}

/** A single display-title normalizer and scoped metadata/image parsers. No guessed image URLs. */
internal object KuraKura21Metadata {
    data class Image(val url: String, val score: Double)
    data class Card(val title: String, val url: String, val image: Image?)
    fun cleanTitle(raw: String?): String = Parser.unescapeEntities(raw.orEmpty(), false)
        .replace(Regex("[\\s\\p{Z}\\u200B]+"), " ").trim()
        .replace(Regex("^permalink\\s+to\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim()
    private fun text(element: Element?): String {
        val copy = element?.clone() ?: return ""
        copy.select(".screen-reader-text, .sr-only, script, style").remove()
        return cleanTitle(copy.text())
    }
    fun cardTitle(link: Element, image: Element?): String {
        val heading = text(link)
        val attribute = cleanTitle(link.attr("title"))
        // WordPress widgets truncate visible headings. Use the full same-link title only in that case.
        if (heading.endsWith('…') || heading.endsWith("...")) {
            val prefix = heading.removeSuffix("...").removeSuffix("…").trim()
            if (prefix.isNotEmpty() && attribute.startsWith(prefix, true)) return attribute
        }
        return heading.ifBlank { attribute }.ifBlank { cleanTitle(image?.attr("alt")) }
    }
    fun detailRoot(doc: Document): Element = doc.selectFirst("article.hentry:has(.content-moviedata), article.hentry:has(h1), article:has(h1.entry-title)") ?: doc.body()
    fun detailTitle(doc: Document): String? = text(doc.selectFirst("h1.entry-title, h1[itemprop=name], .sheader h1, h1"))
        .ifBlank { cleanTitle(doc.selectFirst("meta[property=og:title]")?.attr("content")) }.takeIf(String::isNotBlank)
    fun absolute(raw: String?, base: String): String? = runCatching {
        val value = Parser.unescapeEntities(raw.orEmpty(), false).trim()
        if (value.isEmpty() || value.startsWith('#')) return null
        URI(base).resolve(value).takeIf { it.scheme?.lowercase() in setOf("http", "https") &&
            !it.host.isNullOrBlank() && it.userInfo == null }?.toString()
    }.getOrNull()
    fun isDetail(url: String): Boolean = runCatching {
        val uri = URI(url)
        val parts = uri.path.trim('/').split('/').filter(String::isNotEmpty)
        uri.scheme in setOf("https", "http") && uri.host?.lowercase() in setOf("kurakura21.com", "www.kurakura21.com") &&
            parts.size == 1 && parts[0] !in setOf("genre", "page", "search", "tag", "category", "wp-admin", "wp-content")
    }.getOrDefault(false)
    fun pageKey(url: String): String = runCatching { URI(url).host.lowercase().removePrefix("www.") + URI(url).path.trimEnd('/') }.getOrDefault(url)
    private fun width(url: String): Double = Regex("-(\\d{2,5})x\\d{2,5}\\.(?:jpe?g|png|webp|avif)", RegexOption.IGNORE_CASE)
        .find(URI(url).path)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    private fun imageCandidate(raw: String?, base: String, score: Double): Image? {
        val url = absolute(raw, base) ?: return null
        if (Regex("(?:^|[/_.-])(?:placeholder|transparent|spacer|blank|loading|no-image)(?:[/_.-]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(URI(url).path)) return null
        return Image(url, maxOf(score, width(url)))
    }
    fun image(element: Element?, base: String): Image? {
        if (element == null) return null
        val candidates = mutableListOf<Image>()
        for (attribute in listOf("data-original", "data-orig-file", "data-full-url", "data-large-file", "data-src", "data-lazy-src", "data-wpfc-original-src", "src")) {
            val raw = element.attr(attribute)
            val known = absolute(raw, base)?.let(::width) ?: 0.0
            val rank = if (known > 0) known else if (attribute in setOf("data-original", "data-orig-file", "data-full-url")) 100_000.0 else 1.0
            imageCandidate(raw, base, rank)?.let(candidates::add)
        }
        for (attribute in listOf("data-srcset", "srcset")) for (entry in element.attr(attribute).split(',')) {
            val parts = entry.trim().split(Regex("\\s+"))
            if (parts.size != 2) continue
            val descriptor = parts[1]
            val score = when {
                descriptor.matches(Regex("[0-9]+w")) -> descriptor.dropLast(1).toDoubleOrNull()
                descriptor.matches(Regex("[0-9.]+x")) -> descriptor.dropLast(1).toDoubleOrNull()?.times(element.attr("width").toDoubleOrNull() ?: 100.0)
                else -> null
            } ?: continue
            if (score > 0 && score.isFinite()) imageCandidate(parts[0], base, score)?.let(candidates::add)
        }
        return candidates.maxByOrNull { it.score }
    }
    fun detailPoster(doc: Document, root: Element, json: JSONObject?): String? {
        val primary = root.selectFirst(".gmr-movie-data > figure > img.wp-post-image, .poster img, .sheader img, .mvic-desc img")
        image(primary, doc.baseUri())?.let { return it.url }
        absolute(doc.selectFirst("meta[property=og:image]")?.attr("content"), doc.baseUri())?.let { return it }
        val structured = json?.opt("image")
        val raw = when (structured) { is String -> structured; is JSONObject -> structured.optString("url"); is JSONArray -> structured.optString(0); else -> null }
        absolute(raw, doc.baseUri())?.let { return it }
        // A detail-root poster is allowed, but never grab an unrelated recommendation/landscape content image.
        return image(root.selectFirst("img.wp-post-image"), doc.baseUri())?.url
    }
    fun genres(root: Element, json: JSONObject?): List<String> {
        val values = root.select(".content-moviedata .gmr-moviedata a[href*='/genre/'][rel~=category], .genres a[href], .sgeneros a[href], [itemprop=genre]")
            .filter { it.closest("aside, nav, footer, .widget, .related, .related-posts, .gmr-related-posts") == null }
            .map { cleanTitle(it.text()) }.filter(String::isNotBlank)
        if (values.isNotEmpty()) return values.distinctBy(String::lowercase).take(20)
        val raw = json?.opt("genre")
        return when (raw) { is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it) as? String }; is String -> raw.split(','); else -> emptyList() }
            .map(::cleanTitle).filter(String::isNotBlank).distinctBy(String::lowercase).take(20)
    }
    fun plot(doc: Document, root: Element, json: JSONObject?): String? {
        val elements = root.select(".synopsis, [itemprop=description], .description > p, .entry-content-single > p, .entry-content > p, .wp-content > p")
        val paragraphs = elements.filter { it.closest(".related, .related-posts, .gmr-related-posts, .gmr-relatedpost, aside, nav, footer") == null }
            .map { text(it) }.filter(String::isNotBlank).distinct()
        if (paragraphs.isNotEmpty()) return paragraphs.joinToString("\n\n").take(10_000)
        val metadata = (json?.opt("description") as? String)?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
        return metadata?.let { org.jsoup.Jsoup.parseBodyFragment(it).text().replace(Regex("[\\s\\p{Z}]+"), " ").trim() }?.takeIf(String::isNotBlank)
    }
    fun movieJson(doc: Document, title: String): JSONObject? {
        val records = mutableListOf<JSONObject>()
        fun collect(value: Any?, depth: Int) {
            if (depth > 5 || records.size > 100) return
            when (value) {
                is JSONArray -> for (i in 0 until minOf(value.length(), 100)) collect(value.opt(i), depth + 1)
                is JSONObject -> { records.add(value); collect(value.opt("@graph"), depth + 1); collect(value.opt("mainEntity"), depth + 1) }
            }
        }
        for (script in doc.select("script[type=application/ld+json]")) if (script.data().length <= 500_000)
            runCatching { collect(JSONTokener(script.data()).nextValue(), 0) }
        return records.firstOrNull { item ->
            val type = item.opt("@type")
            val types = if (type is JSONArray) (0 until type.length()).map { type.optString(it) } else listOf(type as? String)
            val url = item.opt("url") as? String
            types.any { it in setOf("Movie", "TVEpisode", "VideoObject") } &&
                cleanTitle(item.optString("name")).equals(title, true) &&
                (url == null || absolute(url, doc.baseUri())?.let(::pageKey) == pageKey(doc.baseUri()))
        }
    }
}
