package com.Mangoporn

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.Requests
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Protocol
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

    private companion object {
        const val MAX_DOCUMENT_REQUESTS = 4
        const val REQUEST_TIMEOUT_SECONDS = 8L
        const val HOME_CACHE_MAX_AGE_MS = 600_000L
    }

    // Cloudstream requests all home rows concurrently. Bound the pressure on
    // this source without changing the application's shared HTTP client.
    private val documentRequests = Semaphore(MAX_DOCUMENT_REQUESTS)
    private val homePages = ConcurrentHashMap<String, Pair<Long, HomePageResponse>>()
    @Volatile
    private var useHttp1 = false
    private val http1Requests by lazy {
        Requests(
            baseClient = app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .build()
        )
    }

    // HEADERS PENTING (JANGAN DIUBAH)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    )

    // ==============================
    // 1. MAIN PAGE CONFIGURATION (UPDATED)
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            request.data
        } else {
            val uri = URI(request.data)
            val base = request.data.substringBefore('?').substringBefore('#').trimEnd('/')
            "$base/page/$page/" + (uri.rawQuery?.let { "?$it" } ?: "")
        }

        val document = try {
            getDocument(url)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            Log.w("MangoPorn", "Category request failed: ${request.name}; ${error.javaClass.simpleName}")
            return cachedHomePage(url, page)
        } catch (error: ErrorLoadingException) {
            Log.w("MangoPorn", "Category unavailable: ${request.name}; ${error.message}")
            return cachedHomePage(url, page)
        }
        
        val items = document.select("article.card, article.item").mapNotNull {
            toSearchResult(it)
        }

        val hasNext = if (document.selectFirst("nav.pg") != null) {
            document.selectFirst("nav.pg a.next.page-numbers") != null
        } else {
            items.isNotEmpty()
        }
        if (page == 1 && items.isEmpty()) {
            cachedHomePage(url, page)?.let { return it }
        }
        val result = newHomePageResponse(request.name, items, hasNext = hasNext)
        if (page == 1 && items.isNotEmpty()) {
            homePages[url] = System.currentTimeMillis() to result
        }
        return result
    }

    private fun cachedHomePage(url: String, page: Int): HomePageResponse? {
        if (page != 1) return null
        val cached = homePages[url] ?: return null
        if (System.currentTimeMillis() - cached.first !in 0L..HOME_CACHE_MAX_AGE_MS) {
            homePages.remove(url, cached)
            return null
        }
        return cached.second
    }

    private suspend fun getDocument(url: String): Document = documentRequests.withPermit {
        val response = try {
            if (useHttp1) {
                http1Requests.get(url, headers = headers, timeout = REQUEST_TIMEOUT_SECONDS)
            } else {
                app.get(url, headers = headers, timeout = REQUEST_TIMEOUT_SECONDS)
            }
        } catch (error: IOException) {
            // A stalled HTTP/2 response must not cancel every home row. Retry
            // once over HTTP/1.1, preserving cookies, TLS checks and headers.
            currentCoroutineContext().ensureActive()
            useHttp1 = true
            Log.w("MangoPorn", "Document transport retry: ${error.javaClass.simpleName}")
            http1Requests.get(url, headers = headers, timeout = REQUEST_TIMEOUT_SECONDS)
        }
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
        document
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
            ?: httpUrl(image.attr("src"), base)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst(".card__t a")
            ?: element.selectFirst("h3 > a")
            ?: element.selectFirst("div.title > a")
            ?: element.selectFirst("a.card__th")
            ?: element.selectFirst("div.image > a")
            ?: return null

        val image = element.selectFirst("img.card__img") ?: element.selectFirst("img")
        val title = titleElement.text().trim().ifBlank { image?.attr("alt")?.trim().orEmpty() }
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
        
        return document.select("article.card, div.result-item, article.item").mapNotNull {
            toSearchResult(it)
        }
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
