package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import org.json.JSONArray
import kotlinx.coroutines.*

class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

    // HEADERS PENTING (JANGAN DIUBAH)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    )

    // ==============================
    // 1. MAIN PAGE CONFIGURATION
    // ==============================
    override val mainPage = mainPageOf(
        // Kategori Utama
        "$mainUrl/" to "Latest",
        "$mainUrl/?filter=most-viewed" to "Trending / Most Viewed",
        "$mainUrl/?filter=popular" to "Top Rated",
        "$mainUrl/genres/porn-movies/" to "Porn Movies",
        "$mainUrl/xxxclips/" to "XXX Clips",
        
        // Kategori Tambahan
        "$mainUrl/movies/genre/18-teens/" to "18+ Teens",
        "$mainUrl/genre/all-girl/" to "All Girl",
        "$mainUrl/genre/all-sex/" to "All Sex",
        "$mainUrl/movies/genre/asian/" to "Asian",
        "$mainUrl/movies/genre/bbc/" to "BBC",
        "$mainUrl/movies/genre/bbw/" to "BBW",
        "$mainUrl/movies/genre/big-tits/" to "Big Boobs",
        "$mainUrl/movies/genre/big-butt/" to "Big Butt",
        "$mainUrl/movies/genre/big-dicks/" to "Big Cocks",
        "$mainUrl/movies/genre/blondes/" to "Blondes",
        "$mainUrl/movies/genre/blowjobs/" to "Blowjobs",
        "$mainUrl/movies/genre/cuckolds/" to "Cuckolds",
        "$mainUrl/movies/genre/cumshots/" to "Cumshots",
        "$mainUrl/movies/genre/deep-throat/" to "Deep Throat",
        "$mainUrl/movies/genre/facials/" to "Facials"
    )

    // Preserve a working archive path for pagination and learn exact links from the site.
    private val resolvedCategories = ConcurrentHashMap<String, String>()
    private val discoveredGenres = ConcurrentHashMap<String, String>()
    private val genrePath = Regex("""^/(?:movies/genre|genre|genres)/([^/]+)/?$""")
    private val listingSelector = "article.card, article.item, div.result-item"

    private fun categoryCandidates(data: String): List<String> {
        val uri = URI(data)
        val slug = genrePath.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
        return buildList {
            resolvedCategories[data]?.let { add(it) }
            if (slug != null) discoveredGenres[slug]?.let { add(it) }
            add(data)
            // Only retry the same slug. Never substitute the homepage or another genre.
            if (slug != null) {
                val origin = "${uri.scheme}://${uri.rawAuthority}"
                val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                listOf("movies/genre", "genre", "genres").forEach { prefix ->
                    add("$origin/$prefix/$slug/$query")
                }
            }
        }.distinct()
    }

    private fun pageUrl(data: String, page: Int): String {
        val uri = URI(data)
        val path = uri.rawPath.orEmpty().replace(Regex("""/page/\d+/?$"""), "")
            .trimEnd('/')
        val pagePath = if (page <= 1) "$path/" else "$path/page/$page/"
        return "${uri.scheme}://${uri.rawAuthority}$pagePath" +
            (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun listingItems(document: Document): List<SearchResponse> {
        return document.select(listingSelector).mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
    }

    private fun hasNextPage(document: Document, page: Int, hasItems: Boolean): Boolean {
        if (!hasItems) return false
        if (document.selectFirst(
                "a[rel=next], link[rel=next], a.next.page-numbers, " +
                    "nav.pg a.next, .pagination a.next, .nav-links a.next"
            ) != null) return true
        val navigation = document.select("nav.pg, .pagination, .nav-links")
        if (navigation.isEmpty()) return true
        return navigation.select("a[href]").any { link ->
            val path = runCatching { URI(link.absUrl("href")).path }.getOrNull().orEmpty()
            val number = Regex("""/page/(\d+)(?:/|$)""").find(path)
                ?.groupValues?.get(1)?.toIntOrNull()
            number != null && number > page
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        for (candidate in categoryCandidates(request.data)) {
            val document = try {
                getDocument(pageUrl(candidate, page))
            } catch (error: ErrorLoadingException) {
                if (error.message?.startsWith("MangoPorn: HTTP 404 (") == true) {
                    continue
                }
                throw error
            }
            val requestedSlug = genrePath.matchEntire(URI(candidate).path.orEmpty())
                ?.groupValues?.get(1)
            val actualPath = URI(document.baseUri()).path.orEmpty()
                .replace(Regex("""/page/\d+/?$"""), "")
            val actualSlug = genrePath.matchEntire(actualPath)?.groupValues?.get(1)
            if (requestedSlug != null && requestedSlug != actualSlug) continue
            val items = listingItems(document)
            if (items.isEmpty()) continue
            resolvedCategories[request.data] = candidate
            return newHomePageResponse(
                request.name, items, hasNext = hasNextPage(document, page, true)
            )
        }
        Log.w("MangoPorn", "No listing available for category: ${request.name}, page: $page")
        // A missing archive must not abort loading the other homepage rows.
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    private suspend fun getDocument(url: String): Document {
        val response = app.get(url, headers = headers)
        if (response.code !in 200..299) {
            val path = runCatching { URI(response.url).path }.getOrNull().orEmpty()
            throw ErrorLoadingException("MangoPorn: HTTP ${response.code} ($path)")
        }
        val document = response.document
        // Resolve relative assets against the actual page after HTTP redirects.
        document.setBaseUri(response.url)
        if (document.title().equals("Site Unavailable", ignoreCase = true) ||
            document.selectFirst("h1")?.text()?.trim().equals("Site Unavailable", ignoreCase = true)
        ) {
            throw ErrorLoadingException("MangoPorn: source returned Site Unavailable")
        }
        document.select("a[href]").forEach { link ->
            val target = httpUrl(link.attr("href"), document.baseUri()) ?: return@forEach
            val targetUri = URI(target)
            val sourceUri = URI(document.baseUri())
            if (!targetUri.host.equals(sourceUri.host, ignoreCase = true)) return@forEach
            val slug = genrePath.matchEntire(targetUri.path.orEmpty())?.groupValues?.get(1)
                ?: return@forEach
            if (targetUri.rawQuery == null) discoveredGenres[slug] = target
        }
        return document
    }

    private fun httpUrl(raw: String?, base: String): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("#") }
            ?: return null
        return runCatching {
            val uri = URI(base).resolve(value)
            uri.toString().takeIf {
                (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                    !uri.host.isNullOrBlank()
            }
        }.getOrNull()
    }

    private fun posterUrl(image: Element?): String? {
        image ?: return null
        val base = image.baseUri().ifBlank { mainUrl }
        return httpUrl(image.attr("data-wpfc-original-src"), base)
            ?: httpUrl(image.attr("data-src"), base)
            ?: httpUrl(image.attr("data-lazy-src"), base)
            ?: httpUrl(image.attr("src"), base)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst(".card__t a")
            ?: element.selectFirst("h3 > a")
            ?: element.selectFirst("div.title > a")
            ?: element.selectFirst("h2 > a, .entry-title a")
            ?: element.selectFirst("a.card__th")
            ?: element.selectFirst("div.image > a")
            ?: return null

        val image = element.selectFirst("img.card__img") ?: element.selectFirst("img")
        val title = titleElement.text().trim()
            .ifBlank { titleElement.attr("title").trim() }
            .ifBlank { image?.attr("alt")?.trim().orEmpty() }
            .takeIf { it.isNotBlank() } ?: return null
        val url = httpUrl(titleElement.attr("href"), element.baseUri().ifBlank { mainUrl })
            ?: return null

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl(image)
        }
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$fixedQuery"
        
        val document = getDocument(url)
        
        return listingItems(document)
    }

    // ==============================
    // 3. LOAD DETAIL
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = getDocument(url)

        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("MangoPorn: detail title missing")

        val description = document.selectFirst(".entry.vid__txt, div.wp-content p")?.text()?.trim()
        
        val imgElement = document.selectFirst(".dp__poster img.vid__poster, div.poster img")
        val poster = posterUrl(imgElement)

        val tags = document.select(".info a[href*='/movies/genre/'], .sgeneros a, .persons a[href*='/genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }
        
        val yearElement = document.selectFirst(".info a[href*='/movies/year/'], .textco a[href*='/year/']")
        val year = yearElement?.text()?.trim()?.toIntOrNull()
            ?: yearElement?.attr("href")?.let { Regex("""/year/(\d{4})(?:/|$)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        
        val actors = document.select(".row--stars a[href*='/movies/pornstar/'], #cast .persons a[href*='/pornstar/']").map {
            ActorData(Actor(it.text(), null)) 
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.actors = actors
        }
    }

    // ==============================
    // 4. LOAD LINKS
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)

        val potentialLinks = linkedSetOf<String>()
        val directMedia = linkedMapOf<String, String>()
        val emitted = AtomicBoolean(false)
        val mediaCallback: (ExtractorLink) -> Unit = { media ->
            callback(media)
            emitted.set(true)
        }

        // Host Links Manager creates its iframe after a click from data-servers.
        // Match the site's client: t=video is a direct source; other entries are embeds.
        document.select(".hlm[data-servers]").forEach { player ->
            val servers = try {
                JSONArray(player.attr("data-servers"))
            } catch (error: Exception) {
                Log.w("MangoPorn", "Invalid Host Links Manager server JSON")
                return@forEach
            }
            for (index in 0 until servers.length()) {
                val server = servers.optJSONObject(index) ?: continue
                val rawUrl = server.opt("u") as? String
                val url = httpUrl(rawUrl, document.baseUri())
                if (url == null) {
                    Log.w("MangoPorn", "Host Links Manager server has no usable direct URL")
                    continue
                }
                if (server.optString("t") == "video") {
                    directMedia[url] = server.optString("l").ifBlank { name }
                } else {
                    potentialLinks.add(url)
                }
            }
        }

        document.select("#playeroptionsul li a").forEach { link ->
            httpUrl(link.attr("href"), document.baseUri())?.let(potentialLinks::add)
        }

        document.select("#playcontainer iframe").forEach { iframe ->
            httpUrl(iframe.attr("src"), document.baseUri())?.let(potentialLinks::add)
        }

        fun getServerPriority(url: String): Int {
            return when {
                url.contains("dood") || url.contains("playmogo") -> 0
                url.contains("luluvid") -> 1
                url.contains("rpmplay") -> 2 
                url.contains("upns") -> 3
                url.contains("easyvidplayer") -> 4
                url.contains("embedseek") -> 5
                url.contains("seekplayer") -> 6
                url.contains("streamtape") -> 7
                url.contains("voe.sx") -> 8
                url.contains("vidhide") -> 9
                url.contains("filemoon") -> 10
                url.contains("mixdrop") -> 11
                url.contains("streamsb") -> 12
                else -> 20
            }
        }

        directMedia.forEach { (url, label) ->
            val type = if (URI(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            mediaCallback(newExtractorLink(name, label, url, type = type) {
                this.referer = httpUrl("/", document.baseUri()).orEmpty()
                this.quality = Qualities.Unknown.value
            })
        }

        if (potentialLinks.isNotEmpty()) {
            val sortedLinks = potentialLinks.sortedBy { getServerPriority(it) }
            coroutineScope {
                sortedLinks.map { link ->
                    async(Dispatchers.IO) {
                        try {
                            val finalUrl = link

                            // Isolate server failures here while preserving coroutine cancellation.
                            if (finalUrl.contains("luluvid.com")) {
                                Luluvid().getUrl(finalUrl, data, subtitleCallback, mediaCallback)
                            } else if (finalUrl.contains("rpmplay.online")) {
                                RpmPlay().getUrl(finalUrl, data, subtitleCallback, mediaCallback)
                            } else if (finalUrl.contains("upns.online")) {
                                UpnsOnline().getUrl(finalUrl, data, subtitleCallback, mediaCallback)
                            } else if (finalUrl.contains("easyvidplayer.com")) {
                                EasyVidPlayer().getUrl(finalUrl, data, subtitleCallback, mediaCallback)
                            } else if (finalUrl.contains("embedseek.online")) {
                                EmbedSeek().getUrl(finalUrl, data, subtitleCallback, mediaCallback)
                            } else if (finalUrl.contains("seekplayer.vip")) {
                                SeekPlayer().getUrl(finalUrl, data, subtitleCallback, mediaCallback)
                            } else if (finalUrl.contains("streamtape.com")) {
                                StreamtapeCustom().getUrl(finalUrl, data, subtitleCallback, mediaCallback)
                            } else {
                                // Eksekutor bawaan Cloudstream untuk server-server lain
                                loadExtractor(finalUrl, data, subtitleCallback, mediaCallback)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Abaikan error di satu server agar server lain tetap dicoba
                        }
                    }
                }.awaitAll()
            }
        }

        return emitted.get()
    }
}

// ==========================================
// KUMPULAN CUSTOM EXTRACTOR
// ==========================================

class Luluvid : ExtractorApi() {
    override var name = "Luluvid"
    override var mainUrl = "https://luluvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer).text
        val m3u8Regex = Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
        var match = m3u8Regex.find(response)
        
        if (match == null) {
            val unpacked = getAndUnpack(response)
            match = m3u8Regex.find(unpacked)
        }

        if (match != null) {
            val m3u8Url = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

// Delegate to the maintained extractor, which evaluates the botlink expression.
// The old parser joined the last string concatenation anywhere in the page,
// allowing unrelated scripts to corrupt the media token and ignored substring().
class StreamtapeCustom : com.lagradost.cloudstream3.extractors.StreamTape() {
    override var name = "Streamtape"
}

// Pasukan Kloningan Vidhide (Menggunakan VidhideExtractor dengan var)
class RpmPlay : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "RpmPlay"
    override var mainUrl = "https://my.rpmplay.online"
}

class UpnsOnline : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "UpnsOnline"
    override var mainUrl = "https://my.upns.online"
}

class EasyVidPlayer : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "EasyVidPlayer"
    override var mainUrl = "https://p.easyvidplayer.com"
}

class EmbedSeek : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "EmbedSeek"
    override var mainUrl = "https://my.embedseek.online"
}

class SeekPlayer : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "SeekPlayer"
    override var mainUrl = "https://vip.seekplayer.vip"
}
