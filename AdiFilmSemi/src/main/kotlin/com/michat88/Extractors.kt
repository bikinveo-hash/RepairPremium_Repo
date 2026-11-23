package com.michat88

import android.media.MediaCodecList
import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal inline fun <T> sourceCatching(block: ()->T): Result<T> = try { Result.success(block()) }
    catch(e: CancellationException) { throw e } catch(e: Exception) { Result.failure(e) }

/** Request-local source tasks and serialized callbacks. Nothing owns or replaces Moviebox state. */
internal class MultiSource(private val tmdbKey: String,
    private val fourK: suspend (MediaIdentity.Request, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit = { request, sub, link ->
        FourKHDHubSource().resolve(request, sub, link)
    },
    private val oppa: suspend (MediaIdentity.Request,(SubtitleFile)->Unit,(ExtractorLink)->Unit)->Unit = { r,s,l -> OppaSource().resolve(r,s,l) },
    private val enrich: (suspend (MediaIdentity.Request)->MediaIdentity.Request)? = null,
    private val timeouts: List<Long> = listOf(95_000,65_000,50_000)) {
    private suspend fun metadata(r: MediaIdentity.Request): MediaIdentity.Request {
        if(enrich!=null) return enrich.invoke(r)
        if(r.tmdbId?.matches(Regex("[0-9]+"))!=true) return r
        return withTimeoutOrNull(6000) { sourceCatching {
            val kind=if(r.type==1) "movie" else "tv"
            val raw=app.get("https://api.themoviedb.org/3/$kind/${r.tmdbId}?api_key=$tmdbKey&append_to_response=translations,alternative_titles,external_ids&language=en-US",timeout=5)
                .takeIf { it.code==200 }?.text ?: return@sourceCatching r
            val o=JSONObject(raw)
            if(MediaIdentity.text(o,"id")!=r.tmdbId) return@sourceCatching r
            val translations=MediaIdentity.objects(o.optJSONObject("translations")?.optJSONArray("translations"))
                .filter { MediaIdentity.text(it,"iso_639_1") in setOf("id","en") }
                .mapNotNull { it.optJSONObject("data") }.flatMap { listOfNotNull(MediaIdentity.text(it,"title","name")) }
            val alternatives=o.optJSONObject("alternative_titles")
            val aliases=MediaIdentity.objects(alternatives?.optJSONArray("titles") ?: alternatives?.optJSONArray("results"))
                .filter { MediaIdentity.text(it,"iso_3166_1") in setOf("ID","US","GB") }
                .mapNotNull { MediaIdentity.text(it,"title") }
            val seasonYear=MediaIdentity.objects(o.optJSONArray("seasons"))
                .firstOrNull { it.optInt("season_number",-1)==r.season }?.let { MediaIdentity.text(it,"air_date")?.take(4)?.toIntOrNull() }
            r.copy(seasonYear=r.seasonYear ?: seasonYear,titles=(translations+listOfNotNull(MediaIdentity.text(o,"title","name"),MediaIdentity.text(o,"original_title","original_name"))+r.titles+aliases)
                .filter { it.isNotBlank() }.distinctBy(MediaIdentity::normalize),
                imdbId=r.imdbId ?: o.optJSONObject("external_ids")?.let { MediaIdentity.text(it,"imdb_id") })
        }.getOrDefault(r) } ?: r
    }
    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
    private fun labeled(group: String, l: ExtractorLink): ExtractorLink {
        val label=l.name.removePrefix("Moviebox • ").ifBlank { "Auto" }
        return ExtractorLink(source=group,name="$group • $label",url=l.url,referer=l.referer,
            quality=l.quality,headers=l.headers,extractorData=l.extractorData,type=l.type,audioTracks=l.audioTracks)
    }
    suspend fun resolve(r: MediaIdentity.Request, sub: (SubtitleFile)->Unit, link: (ExtractorLink)->Unit,
        moviebox: suspend ((SubtitleFile)->Unit,(ExtractorLink)->Unit)->Unit): Boolean = supervisorScope {
        val lock=Any()
        val urls=hashSetOf<String>()
        val subs=hashSetOf<String>()
        var active=true
        val job=currentCoroutineContext().job
        val preferredReady = CompletableDeferred<Unit>()
        fun validUrl(url: String): Boolean = sourceCatching { val u=URI(url); u.scheme in setOf("http","https") && !u.host.isNullOrBlank() && u.userInfo==null }.getOrDefault(false)
        fun emit(group: String, sourceJob: Job): (ExtractorLink)->Unit = { l -> synchronized(lock) {
            job.ensureActive(); sourceJob.ensureActive()
            if (active && validUrl(l.url) && urls.add(l.url)) {
                link(labeled(group, l))
                if (group == "Moviebox" || group == "OppaDrama") preferredReady.complete(Unit)
            }
        } }
        fun emitSub(sourceJob: Job): (SubtitleFile)->Unit = { s -> synchronized(lock) {
            job.ensureActive(); sourceJob.ensureActive()
            if(active && validUrl(s.url) && subs.add(s.url)) sub(s)
        } }
        // Metadata hydration is independent of Moviebox and shared by the additional sources.
        val selected=async(Dispatchers.IO) { if(MediaIdentity.valid(r)) metadata(r) else r }
        val (movieboxTimeout, fourKTimeout, oppaTimeout) = timeouts
        fun launchSource(
            name: String,
            timeout: Long,
            resolve: suspend ((SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit,
        ) = launch(Dispatchers.IO) {
            try {
                val completed = withTimeoutOrNull(timeout) {
                    val sourceJob = currentCoroutineContext().job
                    resolve(emitSub(sourceJob), emit(name, sourceJob))
                    true
                }
                if (completed == null) Log.d("MultiSource", "$name timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("MultiSource", "$name " + e.javaClass.simpleName)
            }
        }

        try {
            val movieboxJob = launchSource("Moviebox", movieboxTimeout, moviebox)
            val oppaJob = launchSource("OppaDrama", oppaTimeout) { subtitle, emitLink ->
                val request = selected.await()
                if (MediaIdentity.valid(request)) oppa(request, subtitle, emitLink)
            }
            val preferredDone = launch {
                joinAll(movieboxJob, oppaJob)
                preferredReady.complete(Unit)
            }
            // Give the preferred sources a head start while keeping the fallback inside the player timeout.
            withTimeoutOrNull(PREFERRED_SOURCE_HEAD_START_MS) { preferredReady.await() }
            val fourKJob = launchSource("FourKHDHub", fourKTimeout) { subtitle, emitLink ->
                val request = selected.await()
                if (MediaIdentity.valid(request)) fourK(request, subtitle, emitLink)
            }
            val pencurimovieJob = launchSource("Pencurimovie", PENCURIMOVIE_TIMEOUT_MS) { subtitle, emitLink ->
                val request = selected.await()
                if (MediaIdentity.valid(request)) PencurimovieSource().resolve(request, subtitle, emitLink)
            }
            joinAll(preferredDone, fourKJob, pencurimovieJob)
            currentCoroutineContext().ensureActive()
            synchronized(lock) { urls.isNotEmpty() }
        } finally { synchronized(lock) { active=false } }
    }
    companion object {
        private const val PREFERRED_SOURCE_HEAD_START_MS = 8_000L
        private const val PENCURIMOVIE_TIMEOUT_MS = 35_000L

        fun isAdditional(link: ExtractorLink): Boolean =
            link.source in setOf("FourKHDHub", "OppaDrama", "Pencurimovie")
        /** Native media client retains resolver headers, Range and segment requests. */
        fun additionalInterceptor(): okhttp3.Interceptor = okhttp3.Interceptor { chain -> chain.proceed(chain.request()) }
    }
}

/** Catalog IDs are never treated as TMDB IDs. Missing identity evidence fails closed. */
internal object MediaIdentity {
    data class Request(val titles: List<String>, val year: Int?, val type: Int,
        val season: Int?, val episode: Int?, val tmdbId: String?, val imdbId: String? = null, val seasonYear: Int? = null)
    data class Candidate(val key: String, val titles: List<String>, val year: Int?, val type: Int,
        val tmdbId: String? = null, val imdbId: String? = null, val season: Int? = null)
    data class Episode(val url: String, val season: Int?, val number: Int?)
    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun valid(r: Request): Boolean = r.type in 1..2 && r.titles.any { normalize(it).isNotEmpty() } &&
        (r.type == 1 || (r.season != null && r.season >= 0 && r.episode != null && r.episode > 0))
    fun matches(r: Request, c: Candidate): Boolean {
        if (!valid(r) || c.type != r.type) return false
        if (r.tmdbId != null && c.tmdbId != null && r.tmdbId != c.tmdbId) return false
        if (r.imdbId != null && c.imdbId != null && r.imdbId != c.imdbId) return false
        if (r.type == 2 && c.season != null && c.season != r.season) return false
        val id = (r.tmdbId != null && r.tmdbId == c.tmdbId) || (r.imdbId != null && r.imdbId == c.imdbId)
        val sameYear = r.year != null && r.year == c.year ||
            (r.type == 2 && c.season == r.season && r.seasonYear != null && r.seasonYear == c.year)
        if (r.year != null && c.year != null && !sameYear) return false
        if (id) return true
        // No fuzzy/token-overlap fallback: aliases must come from selected TMDB metadata.
        return sameYear && c.titles.any { title ->
            val n = normalize(title)
            n.isNotEmpty() && r.titles.any { normalize(it) == n }
        }
    }
    fun select(r: Request, rows: List<Candidate>): Candidate? = rows.distinctBy { it.key }
        .filter { matches(r, it) }.singleOrNull()
    fun episode(r: Request, rows: List<Episode>): Episode? = rows.distinctBy { it.url }
        .filter { it.season == r.season && it.number == r.episode }.singleOrNull()
    fun text(o: JSONObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
        o.optString(k).takeIf { it.isNotBlank() && it != "null" }
    }
    fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
    private fun year(s: String?): Int? = s?.let { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
    fun catalogTitle(raw: String): String = raw
        .replace(Regex("(?i)^(nonton|streaming|download)\\s+"), "")
        .replace(Regex("(?i)\\s*(subtitle\\s+indonesia|sub\\s+indo)\\s*$"), "")
        .replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "").trim()
    fun oppa(key: String, doc: Document): Candidate? {
        val rawTitle = doc.selectFirst(".infolimit h2, h1.entry-title, h1[itemprop=name], h1.title")?.text() ?: return null
        val title = catalogTitle(rawTitle)
        val facts = doc.select(".spe span, .info-content .info, .infox .info").map { it.text() }
        val type = facts.firstOrNull { Regex("(?i)^(tipe|type)\\s*:").containsMatchIn(it) } ?: return null
        val kind = when {
            type.contains("Movie", true) -> 1
            Regex("(?i)drama|series|tv show|anime").containsMatchIn(type) -> 2
            else -> return null
        }
        val release = facts.firstOrNull { Regex("(?i)^(rilis|released|release|tahun|aired|tayang)\\s*:").containsMatchIn(it) }
        val seasonMatch = Regex("(?i)\\s+(?:season|s)\\s*(\\d+)\\s*$").find(title)
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
        val plain = seasonMatch?.let { title.substring(0,it.range.first).trim() } ?: title
        val aliases = facts.filter { Regex("(?i)^(judul lain|other name|native title|alternative titles?)\\s*:").containsMatchIn(it) }
            .flatMap { it.substringAfter(':').split(',',';') }.map { it.trim() }.filter { it.isNotEmpty() }
        val links = doc.select(".spe a[href], .info-content a[href], .single-info .infox a[href]").map { it.attr("href") }
        val tmdb = links.firstNotNullOfOrNull { Regex("themoviedb\\.org/${if(kind==1) "movie" else "tv"}/(\\d+)").find(it)?.groupValues?.get(1) }
        val imdb = links.firstNotNullOfOrNull { Regex("imdb\\.com/title/(tt\\d+)").find(it)?.groupValues?.get(1) }
        return Candidate(key,listOf(plain)+aliases.map(::catalogTitle),year(release) ?: Regex("\\((?:19|20)\\d{2}\\)").find(rawTitle)?.value?.let(::year),kind,tmdb,imdb,season)
    }
    fun oppaEpisodes(doc: Document, c: Candidate): List<Episode> = doc.select(
        ".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li").mapNotNull { li ->
        val a = li.selectFirst("a[href]") ?: return@mapNotNull null
        val title = li.selectFirst(".epl-title, .playinfo h4, .lchx")?.text()
            ?: a.attr("title").ifBlank { a.text() }
        val number = Regex("(?i)(?:episode|eps|ep)\\s*(\\d+)\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: return@mapNotNull null
        val season = Regex("(?i)(?:season|s)\\s*(\\d+)\\b").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: c.season ?: 1 // unnumbered catalog is season one only; never assume requested season.
        a.absUrl("href").takeIf { it.isNotBlank() }?.let { Episode(it,season,number) }
    }
}

/** Finds the matching Pencurimovie page and delegates its iframes to CloudStream. */
private class PencurimovieSource {
    private val releaseYear = Regex("""\b(?:19|20)\d{2}\b""")
    private val titleYear = Regex("""\(((?:19|20)\d{2})\)\s*$""")
    private val seasonNumber = Regex("""(?i)\bseason\s*0*(\d+)\b""")
    private val episodeNumber = Regex("""(?i)\bepisode\s*0*(\d+)\b""")

    suspend fun resolve(
        request: MediaIdentity.Request,
        sub: (SubtitleFile) -> Unit,
        link: (ExtractorLink) -> Unit,
    ) {
        val titles = request.titles.filter(String::isNotBlank).distinctBy(MediaIdentity::normalize)
        val titleKeys = titles.map(MediaIdentity::normalize).toSet()
        val visited = hashSetOf<String>()
        val matches = linkedMapOf<String, Document>()

        for (title in titles.take(6)) {
            val searchPath = "/?s=" + URLEncoder.encode(title, "UTF-8")
            val search = getPage(BASE_URL + searchPath)
                ?: getPage(LEGACY_BASE_URL + searchPath)
                ?: continue
            for (card in search.select("div.ml-item a[href]").take(30)) {
                val cardTitle = card.attr("oldtitle").ifBlank { card.attr("title") }
                    .ifBlank { card.selectFirst(".mli-info h2, h2, h3")?.text().orEmpty() }
                if (MediaIdentity.normalize(MediaIdentity.catalogTitle(cardTitle)) !in titleKeys) continue
                val candidateUrl = catalogUrl(card.absUrl("href")) ?: continue
                val path = catalogPath(candidateUrl)
                if (!visited.add(path)) continue
                val detail = getPage(candidateUrl) ?: continue
                val candidate = catalogEntry(detail) ?: continue
                if (MediaIdentity.matches(request, candidate)) {
                    matches[catalogPath(detail.location())] = detail
                }
            }
            if (matches.isNotEmpty()) break
        }

        val detail = matches.values.singleOrNull() ?: return
        val playerPage = if (request.type == 2) {
            val episode = MediaIdentity.episode(request, episodes(detail)) ?: return
            getPage(episode.url) ?: return
        } else {
            detail
        }
        val pageUrl = playerPage.location().toHttpUrlOrNull() ?: return
        val mirrors = playerPage.select("div.movieplay iframe").mapNotNull { iframe ->
            sequenceOf(iframe.attr("data-src"), iframe.attr("src"))
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull(pageUrl::resolve)
                .firstOrNull { it.username.isEmpty() && it.password.isEmpty() && it != pageUrl }
                ?.toString()
        }.distinct()
        coroutineScope {
            val slots = Semaphore(4)
            mirrors.map { url ->
                launch {
                    slots.withPermit {
                        sourceCatching { loadExtractor(url, pageUrl.toString(), sub, link) }
                            .onFailure { Log.d("Pencurimovie", "Mirror failed: " + it.javaClass.simpleName) }
                    }
                }
            }.joinAll()
        }
    }

    private fun catalogUrl(url: String): String? = url.toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() &&
            CATALOG_DOMAINS.any { domain -> it.host == domain || it.host.endsWith(".$domain") }
    }?.newBuilder()?.scheme("https")?.fragment(null)?.build()?.toString()

    private fun catalogPath(url: String): String =
        url.toHttpUrlOrNull()?.encodedPath?.trimEnd('/').orEmpty()

    private suspend fun getPage(url: String): Document? = sourceCatching {
        val response = app.get(url, timeout = 8)
        val location = catalogUrl(response.url ?: url)
        if (response.code == 200 && location != null) Jsoup.parse(response.textLarge, location) else null
    }.getOrNull()

    private fun catalogEntry(doc: Document): MediaIdentity.Candidate? {
        val heading = doc.selectFirst("div.mvic-desc h3")?.text()?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        val type = if (doc.selectFirst("div.tvseason") != null ||
            catalogPath(doc.location()).startsWith("/series/")) 2 else 1
        val release = doc.selectFirst("div.mvic-info p:contains(Release)")?.text()
        val year = release?.let { releaseYear.find(it)?.value?.toIntOrNull() }
            ?: titleYear.find(heading)?.groupValues?.get(1)?.toIntOrNull()

        val infoLinks = doc.select("div.mvic-info a[href]").mapNotNull { it.absUrl("href").toHttpUrlOrNull() }
        val tmdbKind = if (type == 1) "movie" else "tv"
        val tmdb = infoLinks.firstNotNullOfOrNull { url ->
            val segments = url.pathSegments
            if (url.host in TMDB_HOSTS && segments.firstOrNull() == tmdbKind) {
                segments.getOrNull(1)?.substringBefore('-')?.takeIf { TMDB_ID.matches(it) }
            } else null
        }
        val imdb = infoLinks.firstNotNullOfOrNull { url ->
            val segments = url.pathSegments
            if (url.host in IMDB_HOSTS && segments.firstOrNull() == "title") {
                segments.getOrNull(1)?.takeIf { IMDB_ID.matches(it) }
            } else null
        }
        return MediaIdentity.Candidate(
            doc.location(), listOf(MediaIdentity.catalogTitle(heading)), year, type, tmdb, imdb
        )
    }

    private fun episodes(doc: Document): List<MediaIdentity.Episode> =
        doc.select("div.tvseason").flatMap { group ->
            val season = group.selectFirst("strong")?.text()?.let {
                seasonNumber.find(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: return@flatMap emptyList()
            group.select("div.les-content a[href]").mapNotNull { anchor ->
                val episode = episodeNumber.find(anchor.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val url = catalogUrl(anchor.absUrl("href")) ?: return@mapNotNull null
                MediaIdentity.Episode(url, season, episode)
            }
        }.distinctBy { Triple(catalogPath(it.url), it.season, it.number) }

    private companion object {
        const val BASE_URL = "https://ww44.pencurimovie.baby"
        const val LEGACY_BASE_URL = "https://ww21.pencurimovie.sbs"
        val CATALOG_DOMAINS = setOf("pencurimovie.baby", "pencurimovie.sbs")
        val TMDB_HOSTS = setOf("themoviedb.org", "www.themoviedb.org")
        val IMDB_HOSTS = setOf("imdb.com", "www.imdb.com")
        val TMDB_ID = Regex("""\d+""")
        val IMDB_ID = Regex("""tt\d+""")
    }
}

/** Matches the requested title and episode before opening its download mirrors. */
private class FourKHDHubSource {
    private data class Mirror(val url: String, val quality: Int)

    private val yearPattern = Regex("""\b(?:19|20)\d{2}\b""")
    private val titleYear = Regex("""\s*\(((?:19|20)\d{2})\)\s*$""")
    private val seasonNumber = Regex("""(?i)\b(?:s(?:eason)?[\s-]*)?0*(\d{1,2})\b""")
    private val episodeNumber = Regex("""(?i)\b(?:episode|ep|e)[\s-]*0*(\d{1,3})(?!\d)""")
    private val episodeCode = Regex("""(?i)\bS0*(\d{1,2})E0*(\d{1,3})(?=[^\w]|$)""")
    private val resolution = Regex("""(?i)\b(480|540|576|720|1080|1440|2160)p\b""")

    suspend fun resolve(
        request: MediaIdentity.Request,
        sub: (SubtitleFile) -> Unit,
        link: (ExtractorLink) -> Unit,
    ) = coroutineScope {
        val domains = FourKHDHubDomains.get()
        val base = domains?.catalog ?: "https://4khdhub.dad"
        val visited = hashSetOf<String>()
        val matches = linkedMapOf<String, List<Mirror>>()
        val titleKeys = request.titles.map(MediaIdentity::normalize).filter(String::isNotEmpty).toSet()

        for (title in request.titles.filter(String::isNotBlank).distinctBy(MediaIdentity::normalize).take(6)) {
            val search = getPage("$base/?s=" + URLEncoder.encode(title, "UTF-8")) ?: continue
            val resultHost = search.location().toHttpUrlOrNull()?.host ?: continue
            for (card in search.select("div.card-grid a[href]").take(24)) {
                val cardTitle = card.selectFirst("h3")?.text() ?: continue
                if (MediaIdentity.normalize(titleYear.replace(cardTitle, "")) !in titleKeys) continue

                val url = card.absUrl("href").toHttpUrlOrNull()
                    ?.takeIf { it.host == resultHost && it.encodedPath != "/" }
                    ?.toString() ?: continue
                if (!visited.add(url)) continue
                val detail = getPage(url) ?: continue
                val candidate = catalogEntry(url, detail) ?: continue
                if (!MediaIdentity.matches(request, candidate)) continue

                matches[detail.location().ifBlank { url }] = if (request.type == 1) {
                    detail.select("div.download-item").flatMap { mirrors(it) }
                } else {
                    episodeMirrors(detail, request)
                }
            }
            if (matches.isNotEmpty()) break
        }

        val found = matches.values.singleOrNull().orEmpty().distinctBy(Mirror::url)
        if (found.isEmpty()) return@coroutineScope
        val slots = Semaphore(4)
        found.map { mirror ->
            async {
                slots.withPermit {
                    sourceCatching {
                        val url = if (mirror.url.toHttpUrlOrNull()?.queryParameter("id") != null) {
                            fourKRedirect(mirror.url).ifBlank { mirror.url }
                        } else mirror.url
                        resolveFourKHost(url, null, domains?.hubCloud, sub) { extracted ->
                            if (extracted.quality == Qualities.Unknown.value && mirror.quality != Qualities.Unknown.value) {
                                extracted.quality = mirror.quality
                            }
                            link(extracted)
                        }
                    }.onFailure { Log.d("FourKHDHub", "Mirror failed: " + it.javaClass.simpleName) }
                }
            }
        }.awaitAll()
        Unit
    }

    private suspend fun getPage(url: String): Document? = sourceCatching {
        val response = app.get(url, timeout = 8)
        if (response.code == 200) Jsoup.parse(response.textLarge, response.url ?: url) else null
    }.getOrNull()

    private fun catalogEntry(url: String, doc: Document): MediaIdentity.Candidate? {
        val rawTitle = doc.selectFirst("h1.page-title")?.text()?.trim().orEmpty()
        if (rawTitle.isEmpty()) return null
        val title = titleYear.replace(rawTitle, "").trim()
        val tags = doc.select("div.mt-2 span.badge").map { it.text().trim() }
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        val type = when {
            tags.any { it.equals("Movies", true) } || "-movie-" in path -> 1
            tags.any { it.equals("Series", true) } || "-series-" in path ||
                doc.selectFirst("div.episodes-list") != null -> 2
            else -> return null
        }
        val year = doc.select("div.mt-2 span").firstNotNullOfOrNull {
            it.text().trim().takeIf(yearPattern::matches)?.toIntOrNull()
        } ?: titleYear.find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            ?: titleYear.find(doc.title().substringBefore(" - ").trim())?.groupValues?.get(1)?.toIntOrNull()
        val kind = if (type == 1) "movie" else "tv"
        val tmdbPattern = Regex("themoviedb\\.org/$kind/(\\d+)")
        val tmdb = doc.select("a[href*='themoviedb.org/']").firstNotNullOfOrNull {
            tmdbPattern.find(it.attr("href"))?.groupValues?.get(1)
        }
        val imdb = doc.select("a[href*='imdb.com/title/']").firstNotNullOfOrNull {
            Regex("""imdb\.com/title/(tt\d+)""").find(it.attr("href"))?.groupValues?.get(1)
        }
        return MediaIdentity.Candidate(url, listOf(title), year, type, tmdb, imdb)
    }

    private fun episodeMirrors(doc: Document, request: MediaIdentity.Request): List<Mirror> {
        val season = request.season ?: return emptyList()
        val episode = request.episode ?: return emptyList()
        val episodic = doc.select("div.episodes-list div.season-item").flatMap { row ->
            val heading = row.select("div.episode-number").text()
            val rowSeason = seasonNumber.find(heading)?.groupValues?.get(1)?.toIntOrNull()
            row.select("div.episode-download-item").flatMap { item ->
                val code = episodeCode.find(item.select(".file-title").text())
                val itemSeason = code?.groupValues?.get(1)?.toIntOrNull() ?: rowSeason
                val itemNumber = code?.groupValues?.get(2)?.toIntOrNull()
                    ?: episodeNumber.find(item.select("div.episode-file-info span.badge-psa").text())
                        ?.groupValues?.get(1)?.toIntOrNull()
                if (itemSeason == season && itemNumber == episode) mirrors(item, heading) else emptyList()
            }
        }
        val explicitlyNumbered = doc.select("div.download-item").flatMap { item ->
            val title = item.select(".file-title").text().ifBlank {
                item.select("div.flex-1.text-left.font-semibold").text()
            }
            val code = episodeCode.findAll(title).singleOrNull()
            val isPack = Regex("""(?i)(?:complete|season pack|\d+\s+episodes\b|S\d+E\d+\s*[-–~]\s*(?:E)?\d+)""")
                .containsMatchIn(title)
            if (!isPack && code != null && code.groupValues[1].toIntOrNull() == season &&
                code.groupValues[2].toIntOrNull() == episode
            ) mirrors(item) else emptyList()
        }
        return episodic + explicitlyNumbered
    }

    private fun mirrors(item: Element, heading: String = ""): List<Mirror> {
        val qualityText = item.select(".file-title, div.flex-1.text-left.font-semibold").text().ifBlank { heading }
        val quality = resolution.find(qualityText)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
        return item.select("a[href]").mapNotNull { a ->
            a.absUrl("href").toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
                ?.let { Mirror(it.toString(), quality) }
        }
    }
}

private object FourKHDHubDomains {
    private const val URL = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    data class Domains(val catalog: String, val hubCloud: String?)
    @Volatile private var cached: Domains? = null

    suspend fun get(): Domains? {
        cached?.let { return it }
        return sourceCatching {
            val response = app.get(URL, timeout = 5)
            if (response.code != 200) return@sourceCatching null
            val json = JSONObject(response.text)
            fun base(key: String) = json.optString(key).toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.encodedPath == "/" && it.query == null }
                ?.toString()?.trimEnd('/')
            val catalog = base("4khdhub") ?: return@sourceCatching null
            Domains(catalog, base("hubcloud")).also { cached = it }
        }.getOrNull()
    }
}

private suspend fun resolveFourKHost(
    url: String,
    referer: String?,
    hubCloudBase: String?,
    sub: (SubtitleFile) -> Unit,
    link: (ExtractorLink) -> Unit,
) {
    val address = url.toHttpUrlOrNull() ?: return
    if (address.username.isNotEmpty() || address.password.isNotEmpty()) return
    val host = address.host
    val extractor = when {
        "hubcloud" in host || host == hubCloudBase?.toHttpUrlOrNull()?.host -> FourKHubCloud(hubCloudBase)
        "hblinks" in host -> FourKHblinks(hubCloudBase)
        "hubdrive" in host -> FourKHubDrive(hubCloudBase)
        "hdstream4u" in host -> FourKHdStream4u()
        "hubstream" in host -> FourKHubstream()
        host == "pixeldrain.dev" -> FourKPixelDrain()
        else -> null
    }
    if (extractor != null) {
        extractor.getUrl(url, referer, sub, link)
    } else if ("hubcdn" in host) {
        var emitted = false
        sourceCatching {
            FourKHubCdn().getUrl(url, referer, sub) { result ->
                emitted = true
                link(result)
            }
        }
        if (!emitted) FourKHubCdnHls().getUrl(url, referer, sub, link)
    } else if (address.encodedPath.substringAfterLast('.').lowercase(Locale.ROOT) in
        setOf("mkv", "mp4", "webm", "m3u8", "mpd")
    ) {
        link(newExtractorLink("Direct", "Direct", url) {
            this.referer = referer.orEmpty()
        })
    } else {
        loadExtractor(url, referer, sub, link)
    }
}

private val FOUR_K_REDIRECT_PATTERN =
    Regex("s\\('o','([A-Za-z0-9+/=]+)'|ck\\('_wp_http_\\d+','([^']+)'")

private suspend fun fourKRedirect(url: String): String {
    val html = sourceCatching {
        app.get(url).text
    }.getOrElse {
        Log.e("Error", "Failed to load redirect page: ${it.javaClass.simpleName}")
        return ""
    }

    // Faster than buildString + forEach
    val combined = StringBuilder(128)
    for (m in FOUR_K_REDIRECT_PATTERN.findAll(html)) {
        m.groups[1]?.value?.let(combined::append)
            ?: m.groups[2]?.value?.let(combined::append)
    }

    if (combined.isEmpty()) return ""

    return sourceCatching {
        val decoded = base64Decode(
            fourKRot13(
                base64Decode(
                    base64Decode(combined.toString())
                )
            )
        )

        val json = JSONObject(decoded)

        val encodedUrl = base64Decode(json.optString("o"))
        if (encodedUrl.isNotBlank()) return@sourceCatching encodedUrl.trim()

        val data = fourKDecode(json.optString("data"))
        val wp = json.optString("blog_url")
        if (wp.toHttpUrlOrNull() == null || data.isBlank()) return@sourceCatching ""

        app.get("$wp?re=$data")
            .documentLarge
            .body()
            .text()
            .trim()
    }.getOrElse {
        Log.e("Error", "Error processing redirect: ${it.javaClass.simpleName}")
        ""
    }
}

private fun fourKDecode(value: String): String =
    if (value.isEmpty()) "" else String(Base64.decode(value, Base64.DEFAULT))

private fun fourKRot13(value: String): String {
    val out = StringBuilder(value.length)
    for (c in value) {
        out.append(
            when (c) {
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                else -> c
            }
        )
    }
    return out.toString()
}

private class FourKHdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.*"
}

private class FourKHubstream : VidStack() {
    override var mainUrl = "https://hubstream.*"
}

private class FourKHblinks(private val hubCloudBase: String?) : ExtractorApi() {

    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to (referer ?: url)
        )
        val elements = sourceCatching {
            app.get(url, headers = headers).documentLarge
                .select("h3 a, h5 a, div.entry-content p a, div.entry-content a")
        }.getOrElse {
            Log.e(name, "Failed to load page: " + it.javaClass.simpleName)
            return
        }

        for (el in elements) {
            val href = el.absUrl("href")
                .ifBlank { el.attr("href") }
                .trim()

            if (href.isEmpty() || href.startsWith("#") ||
                href.contains("telegram", ignoreCase = true) || href.contains("t.me", ignoreCase = true)
            ) continue

            val address = href.toHttpUrlOrNull() ?: continue
            if ("hblinks" in address.host) continue
            sourceCatching { resolveFourKHost(href, url, hubCloudBase, subtitleCallback, callback) }
        }
    }
}


private class FourKHubCdnHls : ExtractorApi() {

    override val name = "Hubcdn"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = sourceCatching {
            app.get(url).documentLarge.toString()
        }.getOrElse {
            Log.e(name, "Failed to load page: " + it.javaClass.simpleName)
            return
        }

        val encoded = Regex("r=([A-Za-z0-9+/=]+)")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        if (encoded.isEmpty()) {
            Log.e(name, "Encoded URL not found")
            return
        }

        val m3u8 = sourceCatching {
            base64Decode(encoded).substringAfterLast("link=")
        }.getOrElse {
            Log.e(name, "Failed to decode base64")
            return
        }

        callback(
            newExtractorLink(
                name,
                name,
                m3u8,
                ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}


private class FourKPixelDrain : PixelDrain(){
    override var mainUrl = "https://pixeldrain.dev"
}

private class FourKHubDrive(private val hubCloudBase: String?) : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, timeout = 8).documentLarge
        val href = document.selectFirst(".btn.btn-primary.btn-md.btn-success1.m-1, .btn.btn-primary.btn-user.btn-success1.m-1")
            ?.let { button ->
                button.absUrl("href").ifBlank {
                    url.toHttpUrlOrNull()?.resolve(button.attr("href"))?.toString().orEmpty()
                }
            }
            ?: return
        val address = href.toHttpUrlOrNull() ?: return
        if ("hubdrive" !in address.host && "hblinks" !in address.host) {
            resolveFourKHost(href, url, hubCloudBase, subtitleCallback, callback)
        }
    }
}


private class FourKHubCloud(private val hubCloudBase: String?) : ExtractorApi() {

    override val name = "Hub-Cloud"
    override val mainUrl: String = hubCloudBase ?: "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val prefix = when {
            referer.isNullOrBlank() || referer.contains("4khdhub", ignoreCase = true) ||
                referer.contains("4k hdhub", ignoreCase = true) -> "HubCloud"
            referer.contains("HubCloud", ignoreCase = true) || referer.contains("Hub-Cloud", ignoreCase = true) -> referer.trim()
            else -> "${referer.trim()} HubCloud"
        }

        val uri = sourceCatching { URI(url) }.getOrElse {
            Log.e(tag, "Invalid URL: " + it.javaClass.simpleName)
            return
        }

        var realUrl = uri.toString()
        var baseUrl = "${uri.scheme}://${uri.host}"

        hubCloudBase?.let { latest ->
            if (latest.isNotBlank() && !baseUrl.equals(latest, ignoreCase = true)) {
                realUrl = realUrl.replace(baseUrl, latest)
                baseUrl = latest
            }
        }

        val href = sourceCatching {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val raw = app.get(realUrl).document
                    .selectFirst("a[href*='hubcloud.php']")
                    ?.attr("href")
                    .orEmpty()

                if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        }.getOrElse {
            Log.e(tag, "Failed to extract href: " + it.javaClass.simpleName)
            ""
        }

        if (href.isBlank()) return

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text()?.substringBefore("|")?.trim().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append(headerDetails)
            if (size.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("[$size]")
            }
        }

        for (element in document.select("a.btn")) {
            val rawLink = resolveButtonHref(element, document)
            val link = href.toHttpUrlOrNull()?.resolve(rawLink)?.toString() ?: continue
            if (rawLink.isBlank() || rawLink.startsWith("#") || link == href) continue
            val text = element.text().ifBlank { element.ownText() }
            val label = text.lowercase()

            when {
                "fsl server" in label -> {
                    callback(
                        newExtractorLink(
                            "$prefix [FSL Server]".trim(),
                            "$prefix [FSL Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "download file" in label || "instant download" in label || "instant" in label -> {
                    callback(
                        newExtractorLink(
                            "$prefix [Instant Download]".trim(),
                            "$prefix [Instant Download] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "buzzserver" in label || "buzz server" in label ||
                    "buzz" in label || "fuckingfast" in label -> {
                    val browserHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Sec-Ch-Ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
                        "Sec-Ch-Ua-Platform" to "\"Windows\"",
                        "Sec-Ch-Ua-Mobile" to "?0"
                    )
                    val firstResponse = sourceCatching { app.get(link, headers = browserHeaders) }.getOrNull()
                    val hxGet = firstResponse?.document?.selectFirst("a[hx-get*='download']")?.attr("hx-get")
                    val copiedLink = Regex("copyDownloadLink\\(['\"]([^'\"]+)['\"]\\)")
                        .find(firstResponse?.text.orEmpty())?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                    val downloadPath = hxGet ?: copiedLink
                    val downloadUrl = when {
                        downloadPath.isNullOrBlank() && link.endsWith("/download") -> link
                        downloadPath.isNullOrBlank() -> link.trimEnd('/') + "/download"
                        downloadPath.startsWith("http", ignoreCase = true) -> downloadPath
                        else -> getBaseUrl(link).trimEnd('/') + "/" + downloadPath.trimStart('/')
                    }
                    val resp = sourceCatching {
                        app.get(downloadUrl, headers = browserHeaders + mapOf(
                            "Referer" to link, "HX-Request" to "true"
                        ), allowRedirects = false)
                    }.getOrNull()
                    val dlink = resp?.headers?.get("hx-redirect")
                        ?: resp?.headers?.get("HX-Redirect")
                        ?: resp?.headers?.get("Location")
                        ?: resp?.headers?.get("location")
                        ?: ""

                    if (dlink.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                "$prefix [BuzzServer]".trim(),
                                "$prefix [BuzzServer] $labelExtras",
                                dlink
                            ) { this.quality = quality }
                        )
                    } else {
                        Log.w(tag, "BuzzServer: No redirect")
                    }
                }

                "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label -> {
                    val base = getBaseUrl(link)
                    val finalUrl =
                        if ("download" in link) link
                        else "$base/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink(
                            "[Pixeldrain]",
                            "$prefix [Pixeldrain] $labelExtras",
                            finalUrl
                        ) { this.quality = quality }
                    )
                }

                "s3 server" in label -> {
                    callback(
                        newExtractorLink(
                            "[FSL Server 3]",
                            "$prefix [S3 Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "fslv2" in label -> {
                    callback(
                        newExtractorLink(
                            "[FSL Server 2]",
                            "$prefix [FSLv2] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "mega server" in label -> {
                    callback(
                        newExtractorLink(
                            "[Mega Server]",
                            "$prefix [Mega Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                else -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getBaseUrl(url: String): String {
        return sourceCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault("")
    }

    private fun resolveButtonHref(
        element: org.jsoup.nodes.Element,
        document: org.jsoup.nodes.Document
    ): String {
        var href = element.attr("href")
        val id = element.id()
        if (id.isBlank()) return href

        val html = document.html()
        val assignment = Regex(
            "document\\.getElementById\\([\\\"']${Regex.escape(id)}[\\\"']\\)\\.href\\s*=\\s*([^;]+)"
        ).find(html)?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')

        if (!assignment.isNullOrBlank()) {
            href = if (assignment.startsWith("http", ignoreCase = true)) {
                assignment
            } else {
                Regex("(?:var|let|const)?\\s*${Regex.escape(assignment)}\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
                    .find(html)?.groupValues?.getOrNull(1) ?: href
            }
        }
        return href
    }

    private fun cleanTitle(title: String): String {

        val name = title.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")

        val normalized = name
            .replace(Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE), "WEB-DL")
            .replace(Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE), "WEBRIP")
            .replace(Regex("H[ .]?265", RegexOption.IGNORE_CASE), "H265")
            .replace(Regex("H[ .]?264", RegexOption.IGNORE_CASE), "H264")
            .replace(Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE), "DDP$1")

        val parts = normalized.split(" ", "_", ".")

        val sourceTags = setOf(
            "WEB-DL", "WEBRIP", "BLURAY", "HDRIP",
            "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP"
        )

        val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")

        val audioTags = setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3")

        val audioExtras = setOf("ATMOS")

        val hdrTags = setOf("SDR","HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")

        val filtered = parts.mapNotNull { part ->
            val p = part.uppercase()

            when {
                sourceTags.contains(p) -> p
                codecTags.contains(p) -> p
                audioTags.any { p.startsWith(it) } -> p
                audioExtras.contains(p) -> p
                hdrTags.contains(p) -> {
                    when (p) {
                        "DV", "DOLBYVISION" -> "DOLBYVISION"
                        else -> p
                    }
                }
                p == "NF" || p == "CR" -> p
                else -> null
            }
        }

        return filtered.distinct().joinToString(" ")
    }
}

private class FourKHubCdn : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).documentLarge
        val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

        val encodedUrl = Regex("reurl\\s*=\\s*\"([^\"]+)\"")
            .find(scriptText ?: "")
            ?.groupValues?.get(1)
            ?.substringAfter("?r=")

        val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")


        if (decodedUrl != null) {
            callback(
                newExtractorLink(
                    this.name,
                    this.name,
                    decodedUrl,
                    INFER_TYPE,
                )
                {
                    this.quality=Qualities.Unknown.value
                }
            )
        }
    }
}

internal class OppaSource(private val base: String = "https://oppadrama.io",
    private val get: suspend (String)->String? = { url -> app.get(url,
        headers=mapOf("Cookie" to "user_is_human=true", "User-Agent" to USER_AGENT),timeout=10)
        .takeIf { it.code==200 }?.text },
    private val playback: suspend (String,(SubtitleFile)->Unit,(ExtractorLink)->Unit)->Unit = { url,s,l ->
        OppaPlayback().loadLinks(url,false,s,l); Unit }) {
    private fun catalogUrl(raw: String): String? = sourceCatching {
        val u=URI(base).resolve(raw)
        u.toString().takeIf { u.scheme=="https" && u.host==URI(base).host && u.userInfo==null && (u.port==-1 || u.port==443) }
    }.getOrNull()
    suspend fun resolve(r: MediaIdentity.Request, sub: (SubtitleFile)->Unit, link: (ExtractorLink)->Unit) {
        val visited=hashSetOf<String>()
        val candidates=linkedMapOf<String,Pair<MediaIdentity.Candidate,org.jsoup.nodes.Document>>()
        for(title in r.titles.filter { it.isNotBlank() }.distinctBy(MediaIdentity::normalize).take(6)) {
            val html=get("$base/?s=${URLEncoder.encode(title,"UTF-8")}") ?: continue
            val doc=Jsoup.parse(html,base)
            for(a in doc.select(".listupd .bsx a[href], .bs .bsx a[href]").take(12)) {
                val url=catalogUrl(a.attr("href")) ?: continue
                if(!visited.add(url)) continue
                val name=a.selectFirst(".tt h2")?.text() ?: a.attr("title")
                val plain=MediaIdentity.catalogTitle(name).replace(Regex("(?i)\\s+(?:season|s)\\s*\\d+\\s*$"), "")
                if(name.isNotBlank() && r.titles.none { MediaIdentity.normalize(it)==MediaIdentity.normalize(plain) }) continue
                val detailHtml=get(url) ?: continue
                val detail=Jsoup.parse(detailHtml,url)
                val candidate=MediaIdentity.oppa(url,detail) ?: continue
                candidates[url]=candidate to detail
            }
        }
        val selected=MediaIdentity.select(r,candidates.values.map { it.first }) ?: return
        val detail=candidates.getValue(selected.key).second
        if(r.type==2) {
            val episode=MediaIdentity.episode(r,MediaIdentity.oppaEpisodes(detail,selected)) ?: return
            val url=catalogUrl(episode.url) ?: return
            playback(url,sub,link)
        } else {
            val versions=detail.select(".eplister ul li a[href], .bxcl ul li a[href], #chapterlist ul li a[href], .episodelist ul li a[href]")
                .mapNotNull { catalogUrl(it.attr("href")) }.distinct()
            // Every version belongs to this verified movie's own list, never recommendations.
            if(versions.isEmpty()) playback(selected.key,sub,link)
            else versions.take(4).forEach { playback(it,sub,link) }
        }
    }
}

internal data class MovieVersionData(val url: String, val versionName: String)

internal class OppaPlayback {
    private val mainUrl = "https://oppadrama.io"
    private val DEBUG = false
    private val USE_PROXY = true
    private val FL_DIAG = false
    private val concurrencySemaphore = Semaphore(3)
    private val headersMap = mapOf("Cookie" to "user_is_human=true", "User-Agent" to USER_AGENT)
    private fun fixUrl(url: String): String = java.net.URL(java.net.URL(mainUrl), url).toString()
    private fun logDebug(message: String) = Unit
    private fun getQualityFromString(quality: String?): Int {
        return when {
            quality.isNullOrBlank() -> Qualities.Unknown.value
            quality.contains("2160") || quality.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()

        val versionList = tryParseJson<List<MovieVersionData>>(data)
            ?: listOf(MovieVersionData(data, ""))

        // Deduplikasi Thread-Safe
        val visitedEmbedUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        val safeSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (visitedSubtitleUrls.add(sub.url)) {
                logDebug("Subtitle emitted: [${sub.lang}] -> ${sub.url}")
                subtitleCallback(sub)
            }
        }

        // Post-Extractor Deduplication
        val safeLinkCallback: (ExtractorLink) -> Unit = { link ->
            if (visitedStreamUrls.add(link.url)) {
                logDebug("Source emitted: [${link.source}] ${link.name} -> ${link.url}")
                callback(link)
            } else {
                logDebug("Stream URL skipped (Duplicate Stream Link): ${link.url}")
            }
        }

        coroutineScope {
            versionList.map { version ->
                async {
                    concurrencySemaphore.withPermit {
                        val pageUrl = version.url
                        val labelSuffix = if (version.versionName.isNotBlank()) " (${version.versionName})" else ""
                        logDebug("Processing Mode/Version: $pageUrl | Label: $labelSuffix")

                        sourceCatching {
                            val res = app.get(pageUrl, headers = headersMap)
                            val doc = res.document

                            val mirrorOptions = doc.select("select.mirror option, select[name=mirror] option")
                            logDebug("Found ${mirrorOptions.size} mirror option(s) on $pageUrl")

                            for (option in mirrorOptions) {
                                val base64Value = option.attr("value").trim()
                                var serverName = option.text().trim()

                                if (base64Value.isBlank() || serverName.contains("Pilih Server", ignoreCase = true)) {
                                    continue
                                }

                                if (labelSuffix.isNotBlank()) {
                                    serverName += labelSuffix
                                }

                                sourceCatching {
                                    val decodedHtml = base64Decode(base64Value)
                                    val iframeDoc = Jsoup.parse(decodedHtml)
                                    val iframeElements = iframeDoc.select("iframe[src], IFRAME[SRC]")

                                    for (iframe in iframeElements) {
                                        val rawSrc = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                        if (rawSrc.isNotBlank()) {
                                            val fixedUrl = fixUrl(rawSrc)

                                            if (!visitedEmbedUrls.add(fixedUrl)) {
                                                logDebug("Embed URL skipped (Pre-Extractor Duplicate): $fixedUrl")
                                                continue
                                            }

                                            val countBefore = visitedStreamUrls.size

                                            // 1. Penanganan TurboVIP
                                            if (fixedUrl.contains("emturbovid.com") || fixedUrl.contains("turboviplay.com")) {
                                                logDebug("Processing TurboVIP: $fixedUrl")
                                                extractTurboVipWithFallback(fixedUrl, pageUrl, serverName, safeSubtitleCallback, safeLinkCallback)
                                            }
                                            // 2. Penanganan Hydrax
                                            else if (fixedUrl.contains("abyss") || fixedUrl.contains("hydrax")) {
                                                val mediaId = extractMediaId(fixedUrl)
                                                LocalProxy.obs("HYDRAX", "in=$fixedUrl id=$mediaId")

                                                val hxOk = try {
                                                    extractHydrax(fixedUrl, pageUrl, serverName, safeLinkCallback)
                                                } catch (e: Exception) {
                                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                                    LocalProxy.obs("HX-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                                                    false
                                                }

                                                if (!hxOk && visitedStreamUrls.size == countBefore) {
                                                    LocalProxy.obs("HYDRAX-GAGAL", "Ekstraksi HydraX tidak menghasilkan stream")
                                                }
                                            }
                                            // 3. Penanganan FileLions / Minochinos
                                            else if (fixedUrl.contains("minochinos.com") || fixedUrl.contains("vidhide") || fixedUrl.contains("filelions")) {
                                                val flOk = try {
                                                    extractFileLions(fixedUrl, pageUrl, serverName, safeLinkCallback)
                                                } catch (e: Exception) {
                                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                                    LocalProxy.obs("FL-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                                                    false
                                                }

                                                if (!flOk && visitedStreamUrls.size == countBefore) {
                                                    val mediaId = extractMediaId(fixedUrl)
                                                    LocalProxy.obs("FL-FALLBACK", "in=$fixedUrl id=$mediaId")
                                                    if (!mediaId.isNullOrBlank()) {
                                                        loadExtractor("https://vidhidepro.com/v/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                        if (visitedStreamUrls.size == countBefore) {
                                                            loadExtractor("https://vidhidepro.com/d/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                        }
                                                    }
                                                    if (visitedStreamUrls.size == countBefore) {
                                                        loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    }
                                                }
                                            } else {
                                                // General Fallback
                                                loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                            }
                                        }
                                    }
                                }.onFailure { err ->
                                    logDebug("Failed to decode Base64 mirror: ${err.message}")
                                }
                            }

                            // Secondary Fallback: Iframe Default di DOM (#pembed)
                            if (visitedStreamUrls.isEmpty()) {
                                val defaultIframes = doc.select(".player-embed iframe, #pembed iframe, .mvelement iframe")
                                for (iframe in defaultIframes) {
                                    val src = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                    if (src.isNotBlank()) {
                                        val fixedUrl = fixUrl(src)
                                        if (visitedEmbedUrls.add(fixedUrl)) {
                                            logDebug("Attempting loadExtractor for DOM Iframe: $fixedUrl")
                                            if (fixedUrl.contains("emturbovid.com") || fixedUrl.contains("turboviplay.com")) {
                                                extractTurboVipWithFallback(fixedUrl, pageUrl, "TurboVIP", safeSubtitleCallback, safeLinkCallback)
                                            } else if (fixedUrl.contains("abyss") || fixedUrl.contains("hydrax")) {
                                                extractHydrax(fixedUrl, pageUrl, "Hydrax", safeLinkCallback)
                                            } else if (fixedUrl.contains("minochinos.com") || fixedUrl.contains("vidhide") || fixedUrl.contains("filelions")) {
                                                extractFileLions(fixedUrl, pageUrl, "FileLions", safeLinkCallback)
                                            } else {
                                                loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                            }
                                        }
                                    }
                                }
                            }
                        }.onFailure { err ->
                            logDebug("Failed to process page $pageUrl: ${err.message}")
                        }
                    }
                }
            }.awaitAll()
        }

        logDebug("loadLinks -> Extraction completed. Total unique stream URLs emitted: ${visitedStreamUrls.size}")
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return visitedStreamUrls.isNotEmpty()
    }

    // Ekstraksi TurboVIP
    private suspend fun extractTurboVipWithFallback(
        url: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return sourceCatching {
            val doc = app.get(url, referer = referer, headers = headersMap).document

            var m3u8Url = doc.selectFirst("#video_player[data-hash]")?.attr("data-hash")?.trim()

            if (!m3u8Url.isNullOrBlank()) {
                logDebug("[TurboVIP Level 1 Success] Found M3U8 via data-hash: $m3u8Url")
            } else {
                logDebug("[TurboVIP Level 1 Failed] Trying Level 2 JS Unpacker")
                val html = doc.html()
                val unpacked = getAndUnpack(html)
                m3u8Url = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""file:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
            }

            if (!m3u8Url.isNullOrBlank()) {
                val fixedM3u8 = fixUrl(m3u8Url)

                val servedUrl = if (USE_PROXY) {
                    LocalProxy.proxyUrl(fixedM3u8) ?: return@sourceCatching false
                } else {
                    fixedM3u8
                }
                val viaProxy = servedUrl != fixedM3u8
                logDebug("[TurboVIP] serve=$servedUrl viaProxy=$viaProxy")

                callback(
                    newExtractorLink(
                        source = serverName,
                        name = if (viaProxy) "$serverName [proxy]" else serverName,
                        url = servedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        if (viaProxy) {
                            this.referer = ""
                        } else {
                            this.referer = "https://turboviplay.com/"
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    }
                )
                true
            } else {
                logDebug("[TurboVIP Level 2 Failed] Trying Level 4 loadExtractor built-in")
                val altUrl = if (url.contains("/t/")) url.replace("/t/", "/v/") else url
                loadExtractor(altUrl, referer = url, subtitleCallback, callback)
            }
        }.getOrElse { false }
    }

    // ======================================================================
    // Dukungan AV1 perangkat. Dipakai agar track av1 hanya ditawarkan bila
    // perangkat benar-benar punya decodernya (bundle player juga menyaring
    // av1 berdasarkan kemampuan browser).
    // ======================================================================
    private val deviceSupportsAv1: Boolean by lazy {
        runCatching {
            (0 until MediaCodecList.getCodecCount()).map { MediaCodecList.getCodecInfoAt(it) }.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/av01", true) }
            }
        }.getOrDefault(false)
    }

    /** Satu entri `sources` hasil dekripsi config Hydrax. */
    private data class HydraxSource(
        val label: String,
        val codec: String,
        val resId: String?,
        val size: Long?,
        val sub: String?,
        val url: String?,
        val path: String?,
        val partSize: Long
    )

    /**
     * MD5 dengan seed "satu byte per digit".
     *
     * Ini meniru perilaku pustaka md5 di bundle player: expandKey(size) dipanggil
     * dengan Number, dan modul md5 hanya melakukan toString() tanpa stringToBytes,
     * sehingga bytesToWords membaca tiap karakter lalu meng-koersinya jadi angka
     * ('7' menjadi 7, bukan 0x37).
     */
    private fun md5HexOfDigits(size: Long): String {
        val digits = size.toString()
        val seed = ByteArray(digits.length) { i -> (digits[i] - '0').toByte() }
        return MessageDigest.getInstance("MD5").digest(seed)
            .joinToString("") { "%02x".format(it) }
    }

    /** AES/CTR/NoPadding. CTR bersifat simetris; dipakai untuk enkripsi token. */
    private fun aesCtr(keyHex: String, data: ByteArray): ByteArray {
        val keyBytes = keyHex.toByteArray(Charsets.UTF_8)      // 32 byte -> AES-256
        val ivBytes = keyBytes.copyOfRange(0, 16)              // counter = 16 byte pertama
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes)
        )
        return cipher.doFinal(data)
    }

    /** base64 alfabet standar, tanpa padding dan tanpa newline. */
    private fun b64NoPad(data: ByteArray): String =
        android.util.Base64.encodeToString(
            data,
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )

    /**
     * Bangun URL jalur mp4_native Hydrax.
     *
     *   plain   = "/mp4/{md5_id}/{res_id}/{size}?v={slug}"
     *   keyHex  = md5(seed digit dari size) dalam hex, 32 karakter
     *   ct      = AES/CTR encrypt(plain), kunci = keyHex sebagai ASCII
     *   token   = base64(base64(ct) tanpa '=') tanpa '='
     *   URL     = https://{host}/sora/{size}/{token}
     */
    private fun buildSoraUrl(
        md5Id: String,
        resId: String,
        size: Long,
        slug: String,
        host: String,
        type: String = "mp4"
    ): String {
        val plain = "/$type/$md5Id/$resId/$size?v=$slug"
        val ct = aesCtr(md5HexOfDigits(size), plain.toByteArray(Charsets.UTF_8))
        val token = b64NoPad(b64NoPad(ct).toByteArray(Charsets.US_ASCII))
        return "https://$host/sora/$size/$token"
    }

    /**
     * Urutan preferensi kandidat dalam satu resolusi yang sama.
     *
     *  1. Buang av1 bila perangkat tidak punya decodernya.
     *  2. Dahulukan h264. av1 hanya dipakai bila tidak ada h264 untuk resolusi itu.
     *  3. Dalam codec yang sama, berkas terbesar dipilih (bitrate lebih tinggi).
     *
     * Mengembalikan daftar terurut, bukan satu nilai, supaya pemanggil bisa
     * mencoba kandidat berikutnya kalau pembangunan URL gagal.
     */
    private fun hydraxPreference(group: List<HydraxSource>): List<HydraxSource> {
        val playable = group.filter { !it.codec.equals("av1", true) || deviceSupportsAv1 }
        return playable.sortedWith(
            compareBy<HydraxSource> { if (it.codec.equals("av1", true)) 1 else 0 }
                .thenByDescending { it.size ?: 0L }
        )
    }

    /**
     * Extractor Hydrax.
     *
     * HX-1..HX-4 (ambil HTML, blob base64, dekripsi config AES-CTR) tidak berubah.
     * HX-5 mengurai sources, HX-6 memilih satu kandidat per resolusi lalu membangun
     * URL dengan dua jalur:
     *   1. /sora/  bila source punya size + sub + domain yang cocok  -> file utuh
     *   2. url + path (perilaku lama) sebagai cadangan
     */
    private suspend fun extractHydrax(
        embedUrl: String,
        pageUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ---------------- HX-1 ----------------
        val html = try {
            app.get(
                embedUrl,
                referer = pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LocalProxy.obs("HX-1-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        LocalProxy.obs("HX-1-OK", "html=${html.length} char")
        if (html.isBlank()) return false

        // ---------------- HX-2 ----------------
        val b64 = Regex("""["']([A-Za-z0-9+/=]{200,})["']""")
            .find(html)?.groupValues?.get(1)
        if (b64.isNullOrBlank()) {
            LocalProxy.obs("HX-2-GAGAL", "blob base64 config tidak ditemukan")
            return false
        }
        LocalProxy.obs("HX-2-OK", "b64=${b64.length} char")

        // ---------------- HX-3 ----------------
        val rawBytes = try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LocalProxy.obs("HX-3-GAGAL", "base64 decode: ${e.message}")
            return false
        }

        val jsonObj = try {
            JSONObject(String(rawBytes, Charsets.ISO_8859_1))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LocalProxy.obs("HX-3-JSON-ERROR", "Parsing JSONObject gagal: ${e.message}")
            return false
        }

        val slug = jsonObj.optString("slug").takeIf { it.isNotBlank() }
            ?: extractMediaId(embedUrl) ?: ""
        val md5id = jsonObj.optString("md5_id").takeIf { it.isNotBlank() }
        val userId = jsonObj.optString("user_id").takeIf { it.isNotBlank() }
        val mediaStr = jsonObj.optString("media").takeIf { it.isNotBlank() }

        LocalProxy.obs(
            "HX-3-OK",
            "json=${rawBytes.size}B slug=$slug md5_id=$md5id user_id=$userId media_len=${mediaStr?.length ?: 0}"
        )

        // ---------------- HX-4 (Proses Dekripsi Field 'media') ----------------
        if (userId.isNullOrBlank() || md5id.isNullOrBlank() || mediaStr.isNullOrBlank()) {
            LocalProxy.obs("HX-4-GAGAL", "Parameter terenkripsi tidak lengkap (userId=$userId, md5id=$md5id, media=${mediaStr != null})")
            return false
        }

        val decryptedJson = try {
            val hashInput = "$userId:$slug:$md5id".toByteArray(Charsets.UTF_8)
            val md5Hash = MessageDigest.getInstance("MD5").digest(hashInput)
            val keyHex = md5Hash.joinToString("") { "%02x".format(it) }

            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )

            String(cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LocalProxy.obs("HX-4-DECRYPT-ERROR", "${e.javaClass.simpleName}: ${e.message}")
            return false
        }

        LocalProxy.obs("HX-4-DECRYPT-OK", "decrypted_len=${decryptedJson.length}")

        // ---------------- HX-5 (Parsing hasil dekripsi) ----------------
        val decryptedObj = try {
            JSONObject(decryptedJson)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LocalProxy.obs("HX-5-JSON-ERROR", "Parsing decrypted JSON gagal: ${e.message}")
            return false
        }

        val mp4Obj = decryptedObj.optJSONObject("mp4")
        val sourcesArr = mp4Obj?.optJSONArray("sources")
            ?: decryptedObj.optJSONArray("sources")

        if (sourcesArr == null || sourcesArr.length() == 0) {
            LocalProxy.obs(
                "HX-5-GAGAL",
                "sources kosong (len=${decryptedJson.length}): ${decryptedJson.take(300)}"
            )
            return false
        }

        val parsed = ArrayList<HydraxSource>()
        for (i in 0 until sourcesArr.length()) {
            val o = sourcesArr.optJSONObject(i) ?: continue
            LocalProxy.obs("HX-5-SOURCE[$i]", o.toString())
            parsed.add(
                HydraxSource(
                    label = o.optString("label").takeIf { it.isNotBlank() } ?: "HD",
                    codec = o.optString("codec").takeIf { it.isNotBlank() } ?: "",
                    resId = o.optString("res_id").takeIf { it.isNotBlank() && it != "null" },
                    size = o.optString("size").toLongOrNull(),
                    sub = o.optString("sub").takeIf { it.isNotBlank() && it != "null" },
                    url = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
                    path = o.optString("path").takeIf { it.isNotBlank() && it != "null" }
                        ?: o.optString("file").takeIf { it.isNotBlank() && it != "null" }
                        ?: o.optString("src").takeIf { it.isNotBlank() && it != "null" },
                    partSize = o.optLong("partSize", 0L)
                )
            )
        }

        // Daftar domain CDN, dipakai untuk mencocokkan field `sub` tiap source
        val domainsArr = mp4Obj?.optJSONArray("domains")
            ?: decryptedObj.optJSONArray("domains")
        val domains = ArrayList<String>()
        for (i in 0 until (domainsArr?.length() ?: 0)) {
            val d = domainsArr?.optString(i)
            if (!d.isNullOrBlank()) domains.add(d)
        }

        LocalProxy.obs(
            "HX-5-OK",
            "sources=${parsed.size} domains=${domains.size} av1_support=$deviceSupportsAv1"
        )

        // ---------------- HX-6 (Pilih satu per resolusi, lalu emit) ----------------
        // Satu resolusi = satu baris di daftar Sumber CloudStream, karena satu
        // ExtractorLink selalu dirender sebagai satu entri.
        var linksEmitted = 0
        val emitted = HashSet<String>()

        val byLabel = parsed.groupBy { it.label }
            .toList()
            .sortedByDescending { (label, _) -> getQualityFromString(label) }

        for ((label, group) in byLabel) {
            val order = hydraxPreference(group)

            if (order.isEmpty()) {
                LocalProxy.obs(
                    "HX-6-SKIP-AV1",
                    "label=$label semua kandidat av1, perangkat tidak punya decodernya"
                )
                continue
            }
            if (group.size > 1) {
                LocalProxy.obs(
                    "HX-6-DEDUPE",
                    "label=$label kandidat=${group.size} codec=${group.map { it.codec }} " +
                        "-> dipilih codec=${order.first().codec} size=${order.first().size}"
                )
            }

            var chosen: HydraxSource? = null
            var fullUrl: String? = null

            for (src in order) {
                // The site's R2 fallback is encrypted at the start and split into
                // storage parts. It must be served as one decrypted, seekable MP4.
                if (USE_PROXY && src.size != null && src.url != null && src.path != null) {
                    val storageUrl = if (src.path.startsWith("https://")) src.path
                        else "${unescapeJs(src.url).trimEnd('/')}/${unescapeJs(src.path).trimStart('/')}"
                    fullUrl = LocalProxy.hydraxUrl(storageUrl, src.size, src.partSize)
                    if (fullUrl != null) {
                        LocalProxy.obs("HX-6-STORAGE", "label=$label codec=${src.codec} size=${src.size} part=${src.partSize}")
                        chosen = src
                        break
                    }
                }

                // --- Jalur 1: /sora/ (mp4_native). Mengembalikan file utuh. ---
                if (src.resId != null && src.size != null && src.sub != null) {
                    val host = domains.firstOrNull { it.contains(src.sub) }
                    if (host != null) {
                        fullUrl = try {
                            buildSoraUrl(md5id, src.resId, src.size, slug, host)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            LocalProxy.obs("HX-6-SORA-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                            null
                        }
                        if (fullUrl != null) {
                            LocalProxy.obs(
                                "HX-6-SORA",
                                "label=$label codec=${src.codec} res_id=${src.resId} size=${src.size} host=$host"
                            )
                        }
                    } else {
                        LocalProxy.obs("HX-6-SORA-SKIP", "domain untuk sub='${src.sub}' tidak ditemukan")
                    }
                }

                // --- Jalur 2: url + path (perilaku lama, dipertahankan sebagai cadangan) ---
                if (fullUrl == null) {
                    val bUrl = src.url
                        ?: mp4Obj?.optString("url")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: decryptedObj.optString("url").takeIf { it.isNotBlank() && it != "null" }
                    val pPath = src.path

                    if (pPath != null && (pPath.startsWith("http://") || pPath.startsWith("https://"))) {
                        fullUrl = unescapeJs(pPath)
                    } else if (!bUrl.isNullOrBlank() && !pPath.isNullOrBlank()) {
                        fullUrl = "${unescapeJs(bUrl).trimEnd('/')}/${unescapeJs(pPath).trimStart('/')}"
                    }
                    if (fullUrl != null) {
                        LocalProxy.obs("HX-6-LEGACY", "label=$label (cadangan url+path)")
                    }
                }

                if (fullUrl != null) {
                    chosen = src
                    break
                }
            }

            val finalUrl = fullUrl
            if (chosen == null || finalUrl.isNullOrBlank() || !emitted.add(finalUrl)) continue

            val qualityVal = getQualityFromString(label)

            LocalProxy.obs(
                "HX-6-EMIT-DETAILS",
                "source=$serverName | name=$serverName | quality=$label ($qualityVal) | " +
                    "codec=${chosen.codec} | type=VIDEO | referer=https://abyssplayer.com/ | " +
                    "UA=$USER_AGENT | url=$finalUrl"
            )

            callback(
                newExtractorLink(
                    source = serverName,
                    name = serverName,
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityVal
                    this.referer = "https://abyssplayer.com/"
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            linksEmitted++
        }

        if (linksEmitted == 0) {
            LocalProxy.obs(
                "HX-6-GAGAL",
                "Tidak ada URL yang bisa dibangun (len=${decryptedJson.length}): ${decryptedJson.take(300)}"
            )
            return false
        }

        LocalProxy.obs(
            "HX-6-EMIT-DIRECT",
            "Sukses emit $linksEmitted quality track under single server $serverName"
        )
        return true
    }

    private fun unpackDeanEdwards(packed: String): String? {
        val m = Regex(
            """\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(packed) ?: return null

        val payload = unescapeJs(m.groupValues[1])
        val radix = m.groupValues[2].toIntOrNull() ?: return null
        val count = m.groupValues[3].toIntOrNull() ?: return null
        val words = m.groupValues[4].split("|")
        if (radix < 2 || radix > 62 || count <= 0) return null

        val map = HashMap<String, String>(count * 2)
        for (i in 0 until count) {
            val tok = baseN(i, radix)
            val w = words.getOrNull(i)
            map[tok] = if (w.isNullOrEmpty()) tok else w
        }
        return Regex("""\b[0-9A-Za-z]+\b""").replace(payload) { mr ->
            map[mr.value] ?: mr.value
        }
    }

    private fun baseN(num: Int, radix: Int): String {
        if (num == 0) return "0"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % radix])
            n /= radix
        }
        return sb.toString()
    }

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    // Extractor FileLions
    private suspend fun extractFileLions(
        embedUrl: String,
        pageUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(
                embedUrl,
                referer = pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LocalProxy.obs("FL-1-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        LocalProxy.obs("FL-1-OK", "html=${html.length} char")
        if (html.isBlank()) return false

        val kandidat = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\s*\)?\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).map { it.value }.toList()

        if (kandidat.isEmpty()) {
            LocalProxy.obs("FL-2-GAGAL", "blok packed tidak ditemukan")
            return false
        }
        LocalProxy.obs(
            "FL-2-OK",
            "blok=${kandidat.size} ukuran=${kandidat.map { it.length }}"
        )

        var code: String? = null
        var links = LinkedHashMap<String, String>()
        for ((idx, blok) in kandidat.withIndex()) {
            val hasil = unpackDeanEdwards(blok)
            if (hasil.isNullOrBlank()) {
                LocalProxy.obs("FL-3-SKIP", "blok[$idx] unpack gagal")
                continue
            }
            val l = LinkedHashMap<String, String>()
            Regex(""""(\w+)"\s*:\s*"((?:https?://|/)[^"]+)"""").findAll(hasil).forEach {
                l[it.groupValues[1]] = it.groupValues[2]
            }
            if (l.isEmpty()) {
                Regex("""(\w+)\s*:\s*"((?:https?://|/)[^"]+)"""").findAll(hasil).forEach {
                    l[it.groupValues[1]] = it.groupValues[2]
                }
            }
            LocalProxy.obs(
                "FL-3-OK",
                "blok[$idx] unpacked=${hasil.length} char links=${l.keys}"
            )
            if (l.values.any { it.contains(".m3u8") || it.contains(".mp4") }) {
                code = hasil
                links = l
                break
            }
        }
        if (code == null) {
            LocalProxy.obs("FL-3-GAGAL", "tidak ada blok yang menghasilkan URL media")
            return false
        }

        val kandidatUrl = listOf("hls4", "hls2").firstNotNullOfOrNull { k ->
            links[k]?.takeIf { it.contains(".m3u8") || it.contains(".txt") }
                ?.let { k to it }
        }
        if (kandidatUrl == null) {
            LocalProxy.obs("FL-4-BERHENTI", "hls4/hls2 tidak ada (links: ${links.keys})")
            return false
        }
        val (labelDipakai, urlMentah) = kandidatUrl
        val hls2 = sourceCatching {
            java.net.URL(java.net.URL(embedUrl), urlMentah).toString()
        }.getOrElse { urlMentah }

        val host = sourceCatching { java.net.URL(hls2).host }.getOrNull() ?: "?"
        val servedUrl = LocalProxy.proxyUrl(hls2, clean = true) ?: hls2
        val viaProxy = servedUrl != hls2
        LocalProxy.obs(
            "FL-4-EMIT",
            "label=$labelDipakai host=$host clean=$viaProxy tersedia=${links.keys} url=$hls2"
        )
        callback(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = servedUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = ""
            }
        )
        LocalProxy.obs("FL-4-OK", "emit=1 ($labelDipakai)")

        if (FL_DIAG) {
            try {
                val pl = app.get(hls2, headers = mapOf("User-Agent" to USER_AGENT)).text
                val resDari = { tag: String ->
                    Regex("""$tag:[^\n]*""").findAll(pl).map { mr ->
                        Regex("""RESOLUTION=(\d+x\d+)""")
                            .find(mr.value)?.groupValues?.get(1) ?: "?"
                    }.toList()
                }
                val normal = resDari("#EXT-X-STREAM-INF")
                val iframe = resDari("#EXT-X-I-FRAME-STREAM-INF")
                LocalProxy.obs(
                    "FL-5-MASTER",
                    "bytes=${pl.length} normal=${normal.size}$normal " +
                        "iframe=${iframe.size}$iframe total_entri=${normal.size + iframe.size}"
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LocalProxy.obs("FL-5-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return true
    }

    private fun extractMediaId(url: String): String? {
        val base = url.substringBefore('#')
        Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(base)?.let { return it.groupValues[1] }
        val seg = base.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return if (seg.matches(Regex("""[A-Za-z0-9_-]{4,}"""))) seg else null
    }

}

/**
 * M3 + M4 — proxy lokal dengan pemindaian sync dinamis dan pemotongan prefix.
 * MENGGANTIKAN LocalServer.kt dan versi M2 LocalProxy. Hapus keduanya.
 *
 * TIGA HAL YANG DIKERJAKAN:
 *
 *  1. PENULISAN ULANG PLAYLIST. Ini yang tidak ada di rencana milestone dan
 *     tanpanya seluruh desain tidak mungkin bekerja. URI segmen di playlist
 *     adalah URL absolut ke lh3.googleusercontent.com; kalau tidak ditulis
 *     ulang, ExoPlayer mengambilnya LANGSUNG dan proxy dilewati sepenuhnya.
 *     Setiap URI diganti menjadi http://127.0.0.1:PORT/p?u=<encoded>.
 *     Master -> variant -> segmen bekerja rekursif dengan sendirinya.
 *
 *  2. SCANNER SYNC DINAMIS. Mencari 0x47 ber-alignment 188 byte, sama seperti
 *     yang divalidasi di Python pada 74 segmen lintas 2 judul / 4 varian.
 *     Angka 941 TIDAK di-hardcode di mana pun: ia properti satu aset gambar
 *     (806 PNG + 135 padding), bukan bagian spesifikasi MPEG-TS/HLS.
 *     Kalau TurboVIP mengganti wrapper, scanner tetap benar.
 *
 *  3. PEMOTONGAN STREAMING. Memori konstan. Hanya jendela pemindaian yang
 *     ditampung; sisanya dipompa langsung.
 *
 * FAIL-SAFE: kalau sync tidak ditemukan, byte diteruskan APA ADANYA tanpa
 * dipotong. Lebih baik gagal seperti sebelumnya daripada merusak stream yang
 * sebetulnya sehat.
 */
internal object LocalProxy {

    private const val BUF = 64 * 1024
    private const val SCAN_WINDOW = 64 * 1024
    private const val PROBE_BYTES = 8192
    private const val MAX_REDIRECTS = 5
    private const val PKT = 188
    private const val SYNC = 0x47.toByte()
    private const val NEED_PACKETS = 8

    private val ALLOWED = listOf(
        // TurboVIP
        ".turbosplayer.com",
        ".googleusercontent.com",
        ".turboviplay.com",
        "cdn.turboviplay.com",
        "turbovidhls.com",
        // FileLions. Host CDN-nya BERGANTI-GANTI antar judul - sudah terlihat
        // acek-cdn.com, personalcoachexpert.cyou, dan marsexplorationteam.space
        // pada tiga film berbeda. Kalau muncul host baru, proxy akan menolak
        // dengan 403 dan mencatat "DENY host=..." di logcat. Nama host di baris
        // itu tinggal ditambahkan ke daftar ini.
        "minochinos.com",
        ".acek-cdn.com",
        ".personalcoachexpert.cyou",
        ".marsexplorationteam.space"
    )

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var startedAtMs: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    val served = AtomicInteger(0)
    val swallowed = AtomicInteger(0)
    val proxied = AtomicInteger(0)
    val denied = AtomicInteger(0)
    val playlists = AtomicInteger(0)
    val stripped = AtomicInteger(0)
    val noSync = AtomicInteger(0)
    val bytesOut = AtomicLong(0)

    /** offset hasil PENEMUAN per URL, bukan asumsi. Dipakai untuk Range. */
    private val offsetCache = ConcurrentHashMap<String, Int>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = java.util.concurrent.ThreadPoolExecutor(0, 16, 30L,
        java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "multisource-proxy-media").apply { isDaemon = true } })

    @Volatile
    private var socket: ServerSocket? = null

    val isRunning: Boolean
        get() = socket?.let { !it.isClosed } == true

    val uptimeSec: Long
        get() = if (startedAtMs == 0L) 0L else (System.currentTimeMillis() - startedAtMs) / 1000

    val lastOffsets: String
        get() = offsetCache.values.distinct().sorted().joinToString(",").ifEmpty { "-" }

    // ------------------------------------------------------------- lifecycle

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val lo = InetAddress.getByName("127.0.0.1")
        var ss: ServerSocket? = null
        var lastEx: Throwable? = null
        ss = try { ServerSocket(0, 64, lo) } catch (e: java.io.IOException) { lastEx = e; null }
        if (ss == null) {
            lastError = "bind: ${lastEx?.javaClass?.simpleName}: ${lastEx?.message}"
            obs("START-FAIL", lastError!!)
            port = -1
            return false
        }
        socket = ss
        port = ss.localPort
        startedAtMs = System.currentTimeMillis()
        served.set(0); swallowed.set(0); proxied.set(0); denied.set(0)
        playlists.set(0); stripped.set(0); noSync.set(0); bytesOut.set(0)
        offsetCache.clear(); lastError = null

        Thread({ acceptLoop(ss) }, "OppaDrama-proxy").apply {
            isDaemon = true
            start()
        }
        obs("START", "port=$port")
        return true
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        activeSockets.forEach { sourceCatching { it.close() } }
        activeSockets.clear()
        offsetCache.clear()
        HydraxMedia.clear()
        obs("STOP", "served=${served.get()} stripped=${stripped.get()}")
    }

    // ------------------------------------------------------------------- URL

    /**
     * Bangun URL proxy. WAJIB dipanggil saat emit, jangan pernah di-cache.
     *
     * @param clean kalau true, master playlist disaring: baris
     *   #EXT-X-I-FRAME-STREAM-INF dibuang dan URI varian ditulis absolut
     *   menunjuk langsung ke CDN (TIDAK di-proxy). Dipakai FileLions.
     *   Default false, sehingga pemanggil lama - termasuk TurboVIP -
     *   berperilaku persis seperti sebelumnya.
     */
    fun proxyUrl(upstream: String, clean: Boolean = false): String? {
        if (!isAllowed(upstream) || !start() || port <= 0) return null
        val suffix = if (clean) "&clean=1" else ""
        return "http://127.0.0.1:$port/p?u=" + URLEncoder.encode(upstream, "UTF-8") + suffix
    }

    fun hydraxUrl(upstream: String, size: Long, partSize: Long): String? {
        if (!start() || port <= 0) return null
        val id = HydraxMedia.register(upstream, size, partSize) ?: return null
        return "http://127.0.0.1:$port/hx?id=$id"
    }

    fun isAllowed(raw: String): Boolean {
        val host = try {
            URL(raw).let { if(it.protocol != "https" || it.userInfo != null || (it.port != -1 && it.port != 443)) return false; it.host.lowercase() }
        } catch (_: Throwable) {
            return false
        }
        if (host.isEmpty()) return false
        return ALLOWED.any { e ->
            if (e.startsWith(".")) host == e.substring(1) || host.endsWith(e) else host == e
        }
    }

    // --------------------------------------------------------------- scanner

    /**
     * Cari offset paket TS pertama: 0x47 yang berulang tiap 188 byte
     * sebanyak NEED_PACKETS kali. Identik dengan algoritma yang divalidasi
     * di tv5/tv6. Return -1 kalau tidak ditemukan.
     */
    fun findSync(b: ByteArray, len: Int, need: Int = NEED_PACKETS): Int {
        val limit = len - PKT * need
        if (limit <= 0) return -1
        var i = 0
        while (i < limit) {
            if (b[i] == SYNC) {
                var ok = true
                var k = 1
                while (k < need) {
                    if (b[i + k * PKT] != SYNC) {
                        ok = false; break
                    }
                    k++
                }
                if (ok) return i
            }
            i++
        }
        return -1
    }

    // ---------------------------------------------------------------- server

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val c = try {
                ss.accept()
            } catch (_: Throwable) {
                if (ss.isClosed) return
                swallowed.incrementAndGet()
                continue
            }
            activeSockets.add(c)
            try { workers.execute { handle(c) } }
            catch (_: java.util.concurrent.RejectedExecutionException) { activeSockets.remove(c); c.close() }
        }
    }

    private class Req(val path: String, val headers: Map<String, String>, val method: String)

    private fun readRequest(input: InputStream): Req? {
        val sb = StringBuilder()
        var reqLine: String? = null
        val h = LinkedHashMap<String, String>()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val line = sb.toString().trim()
                sb.setLength(0)
                if (reqLine == null) reqLine = line
                else if (line.isEmpty()) break
                else {
                    val i = line.indexOf(':')
                    if (i > 0) h[line.substring(0, i).trim().lowercase()] =
                        line.substring(i + 1).trim()
                }
            } else if (b != '\r'.code) sb.append(b.toChar())
        }
        val rl = reqLine ?: return null
        val p = rl.split(" ").getOrNull(1) ?: return null
        return Req(p, h, rl.substringBefore(' '))
    }

    private class Up(
        val conn: HttpURLConnection?, val finalUrl: String, val hops: Int
    )

    /** Buka upstream, ikuti redirect MANUAL dengan cek allowlist tiap hop. */
    private fun openUpstream(start: String, range: String?): Up {
        var url = start
        var hops = 0
        while (true) {
            val c = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "*/*")
                    range?.let { setRequestProperty("Range", it) }
                }
            } catch (_: Throwable) {
                return Up(null, url, hops)
            }
            val code = try {
                c.responseCode
            } catch (_: Throwable) {
                c.disconnect(); return Up(null, url, hops)
            }
            if (code in 301..308 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank() || ++hops > MAX_REDIRECTS) return Up(null, url, hops)
                val next = try {
                    URL(URL(url), loc).toString()
                } catch (_: Throwable) {
                    return Up(null, url, hops)
                }
                if (!isAllowed(next)) {
                    denied.incrementAndGet()
                    obs("DENY-REDIRECT", "host=${hostOf(next)}")
                    return Up(null, next, hops)
                }
                url = next
                continue
            }
            return Up(c, url, hops)
        }
    }

    /** Probe kecil untuk menemukan offset tanpa mengunduh seluruh segmen. */
    private fun probeOffset(url: String): Int {
        offsetCache[url]?.let { return it }
        val up = openUpstream(url, "bytes=0-${PROBE_BYTES - 1}")
        val c = up.conn ?: return -1
        return try {
            val buf = ByteArray(PROBE_BYTES)
            var n = 0
            val ins = c.inputStream
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            val off = findSync(buf, n)
            if (off >= 0) { if (offsetCache.size >= 256) offsetCache.clear(); offsetCache[url] = off }
            off
        } catch (_: Throwable) {
            -1
        } finally {
            try {
                c.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun handle(client: Socket) {
        var upstreamConnection: HttpURLConnection? = null
        var upUrl = "-"
        var status = -1
        var lenAsli = -1L
        var offset = 0
        var sent = 0L
        var kind = "-"
        val t0 = System.currentTimeMillis()
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val req = readRequest(client.getInputStream()) ?: run {
                swallowed.incrementAndGet(); return
            }
            served.incrementAndGet()
            val out = client.getOutputStream()

            val q = req.path.indexOf('?')
            val route = if (q >= 0) req.path.substring(0, q) else req.path
            val query = if (q >= 0) req.path.substring(q + 1) else ""

            if (route == "/ping" || route == "/") {
                val body = buildString {
                    append("ok\nport=$port\nserved=${served.get()}\n")
                    append("uptime_sec=$uptimeSec\nstarted_at_ms=$startedAtMs\n")
                    append("playlists=${playlists.get()}\nstripped=${stripped.get()}\n")
                    append("no_sync=${noSync.get()}\ndenied=${denied.get()}\n")
                    append("swallowed=${swallowed.get()}\nbytes_out=${bytesOut.get()}\n")
                    append("offsets_ditemukan=$lastOffsets\n")
                }.toByteArray()
                writeHead(out, 200, "text/plain; charset=utf-8", body.size.toLong(), null)
                out.write(body); out.flush()
                return
            }
            if (route == "/hx") {
                sent = HydraxMedia.serve(param(query, "id") ?: "", req.headers["range"], req.method == "HEAD", out)
                proxied.incrementAndGet()
                bytesOut.addAndGet(sent)
                return
            }
            if (route != "/p") {
                val b = "not found\n".toByteArray()
                writeHead(out, 404, "text/plain", b.size.toLong(), null)
                out.write(b); out.flush(); return
            }

            upUrl = param(query, "u") ?: run {
                val b = "missing u\n".toByteArray()
                writeHead(out, 400, "text/plain", b.size.toLong(), null)
                out.write(b); out.flush(); return
            }
            if (!isAllowed(upUrl)) {
                denied.incrementAndGet()
                val b = "host not allowed\n".toByteArray()
                writeHead(out, 403, "text/plain", b.size.toLong(), null)
                out.write(b); out.flush()
                obs("DENY", "host=${hostOf(upUrl)}")
                return
            }

            val clientRange = req.headers["range"]
            val cleanMode = param(query, "clean") == "1"

            // ---------- cabang 1: Range diminta klien ----------
            if (clientRange != null) {
                kind = "range"
                val off = probeOffset(upUrl)
                offset = if (off > 0) off else 0
                val translated = translateRange(clientRange, offset)
                val up = openUpstream(upUrl, translated)
                val c = up.conn ?: run {
                    val b = "upstream refused\n".toByteArray()
                    writeHead(out, 502, "text/plain", b.size.toLong(), null)
                    out.write(b); out.flush(); return
                }
                upstreamConnection = c
                status = c.responseCode
                lenAsli = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val cr = c.getHeaderField("Content-Range")
                writeHead(
                    out, status, c.contentType ?: "video/mp2t", lenAsli,
                    rewriteContentRange(cr, offset)
                )
                sent = pump(safeStream(c), out)
                out.flush()
                proxied.incrementAndGet(); bytesOut.addAndGet(sent)
                c.disconnect()
                return
            }

            // ---------- ambil upstream ----------
            val up = openUpstream(upUrl, null)
            val c = up.conn ?: run {
                val b = "upstream refused\n".toByteArray()
                writeHead(out, 502, "text/plain", b.size.toLong(), null)
                out.write(b); out.flush()
                obs("PROXY-FAIL", "url=${shortUrl(upUrl)} hops=${up.hops}")
                return
            }
            upstreamConnection = c
            status = c.responseCode
            lenAsli = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val ctype = c.contentType ?: "application/octet-stream"
            val ins = safeStream(c)

            // baca jendela awal: cukup untuk membedakan playlist vs media,
            // dan cukup untuk menemukan sync
            val win = ByteArray(SCAN_WINDOW)
            var wn = 0
            while (wn < win.size) {
                val r = ins.read(win, wn, win.size - wn)
                if (r <= 0) break
                wn += r
            }

            // ---------- cabang 2: playlist -> tulis ulang ----------
            if (looksLikePlaylist(ctype, up.finalUrl, win, wn)) {
                kind = if (cleanMode) "playlist-clean" else "playlist"
                // playlist kecil: aman dibaca penuh
                val rest = ins.readBytes()
                val whole = ByteArray(wn + rest.size)
                System.arraycopy(win, 0, whole, 0, wn)
                System.arraycopy(rest, 0, whole, wn, rest.size)
                val teks = String(whole, Charsets.UTF_8)
                val body = if (cleanMode) {
                    cleanMasterPlaylist(teks, up.finalUrl).toByteArray(Charsets.UTF_8)
                } else {
                    rewritePlaylist(teks, up.finalUrl).toByteArray(Charsets.UTF_8)
                }
                // Content-Length WAJIB dihitung ulang: body berubah.
                writeHead(out, status, "application/vnd.apple.mpegurl", body.size.toLong(), null)
                out.write(body); out.flush()
                sent = body.size.toLong()
                playlists.incrementAndGet(); proxied.incrementAndGet()
                bytesOut.addAndGet(sent)
                c.disconnect()
                return
            }

            // ---------- cabang 3: media -> cari sync, potong ----------
            kind = "media"
            offset = findSync(win, wn)
            if (offset < 0) {
                // FAIL-SAFE: tidak ditemukan sync, teruskan tanpa dipotong
                noSync.incrementAndGet()
                offset = 0
                kind = "media-nosync"
            } else {
                if (offsetCache.size >= 256) offsetCache.clear()
                offsetCache[upUrl] = offset
                if (offset > 0) stripped.incrementAndGet()
            }
            val newLen = if (lenAsli >= 0) (lenAsli - offset).coerceAtLeast(0) else -1L
            writeHead(out, status, ctype, newLen, null)
            if (wn > offset) out.write(win, offset, wn - offset)
            sent = (wn - offset).toLong().coerceAtLeast(0) + pump(ins, out)
            out.flush()
            proxied.incrementAndGet(); bytesOut.addAndGet(sent)
            c.disconnect()
        } catch (_: SocketException) {
            swallowed.incrementAndGet()
        } catch (_: IOException) {
            swallowed.incrementAndGet()
        } catch (e: Throwable) {
            swallowed.incrementAndGet()
            lastError = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            upstreamConnection?.disconnect()
            activeSockets.remove(client)
            try {
                client.close()
            } catch (_: Throwable) {
            }
            if (upUrl != "-") obs(
                "PROXY",
                "kind=$kind url=${shortUrl(upUrl)} status=$status offset=$offset " +
                    "len_upstream=$lenAsli len_kirim=$sent ms=${System.currentTimeMillis() - t0}"
            )
        }
    }

    // -------------------------------------------------------------- playlist

    private fun looksLikePlaylist(ctype: String, url: String, b: ByteArray, n: Int): Boolean {
        if (ctype.contains("mpegurl", true) || ctype.contains("m3u", true)) return true
        if (url.substringBefore('?').endsWith(".m3u8", true)) return true
        if (n >= 7) {
            val head = String(b, 0, minOf(n, 16), Charsets.ISO_8859_1)
            if (head.trimStart().startsWith("#EXTM3U")) return true
        }
        return false
    }

    private val uriTagRe = Regex("""URI="([^"]+)"""")

    /**
     * Saring master playlist untuk mode clean=1.
     *
     * SEBAB YANG DIPERBAIKI (terukur di FL-5-MASTER):
     *   normal=3[852x480, 1280x720, 1920x1080]
     *   iframe=3[852x480, 1280x720, 1920x1080]
     *   total_entri=6
     * Varian #EXT-X-I-FRAME-STREAM-INF membawa atribut RESOLUTION yang sama
     * dengan varian normal, sehingga ExoPlayer mengeksposnya sebagai track
     * video terpilih. Padahal isinya hanya keyframe untuk preview scrub -
     * tidak bisa diputar sebagai video normal. Itulah entri kedua yang gagal.
     *
     * Dua hal yang dilakukan:
     *   1. buang seluruh baris #EXT-X-I-FRAME-STREAM-INF
     *   2. tulis URI varian menjadi ABSOLUT ke CDN, TIDAK di-proxy
     *
     * Poin 2 disengaja: hanya master yang melewati proxy. Playlist varian dan
     * segmen tetap mengalir langsung ke CDN, jadi tidak ada tambahan latensi
     * maupun titik gagal baru pada lalu lintas media.
     */
    private fun cleanMasterPlaylist(body: String, base: String): String {
        val sb = StringBuilder(body.length)
        var dibuang = 0
        var varian = 0
        for (raw in body.split("\n")) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> dibuang++
                line.startsWith("#") -> sb.append(line).append("\n")
                else -> {
                    varian++
                    sb.append(resolve(base, line)).append("\n")
                }
            }
        }
        obs("PROXY-CLEAN", "iframe_dibuang=$dibuang varian_disisakan=$varian")
        return sb.toString()
    }

    private fun rewritePlaylist(body: String, base: String): String {
        val sb = StringBuilder(body.length + 4096)
        for (raw in body.split("\n")) {
            val line = raw.trim()
            when {
                line.isEmpty() -> sb.append("\n")
                line.startsWith("#") -> {
                    val m = uriTagRe.find(line)
                    if (m != null) {
                        val abs = resolve(base, m.groupValues[1])
                        val prox = if (isAllowed(abs)) proxyUrl(abs) ?: abs else abs
                        sb.append(line.replace(m.value, "URI=\"$prox\"")).append("\n")
                    } else sb.append(line).append("\n")
                }
                else -> {
                    val abs = resolve(base, line)
                    val prox = if (isAllowed(abs)) proxyUrl(abs) ?: abs else abs
                    sb.append(prox).append("\n")
                }
            }
        }
        return sb.toString()
    }

    private fun resolve(base: String, rel: String): String = try {
        URL(URL(base), rel).toString()
    } catch (_: Throwable) {
        rel
    }

    // ----------------------------------------------------------------- range

    /** bytes=A-B klien  ->  bytes=(A+off)-(B+off) upstream */
    private fun translateRange(clientRange: String, off: Int): String {
        if (off <= 0) return clientRange
        val m = Regex("""bytes=(\d*)-(\d*)""").find(clientRange) ?: return clientRange
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        return when {
            a != null && b != null -> "bytes=${a + off}-${b + off}"
            a != null -> "bytes=${a + off}-"
            else -> clientRange           // suffix range: biarkan apa adanya
        }
    }

    /** bytes X-Y/Z upstream  ->  bytes (X-off)-(Y-off)/(Z-off) untuk klien */
    private fun rewriteContentRange(cr: String?, off: Int): String? {
        if (cr == null || off <= 0) return cr
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(cr) ?: return cr
        val x = m.groupValues[1].toLongOrNull() ?: return cr
        val y = m.groupValues[2].toLongOrNull() ?: return cr
        val z = m.groupValues[3]
        val zz = if (z == "*") "*" else ((z.toLongOrNull() ?: 0L) - off).coerceAtLeast(0).toString()
        return "bytes ${(x - off).coerceAtLeast(0)}-${(y - off).coerceAtLeast(0)}/$zz"
    }

    // ----------------------------------------------------------------- utils

    private fun safeStream(c: HttpURLConnection): InputStream = try {
        c.inputStream
    } catch (_: Throwable) {
        c.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
    }

    private fun pump(ins: InputStream, out: OutputStream): Long {
        val buf = ByteArray(BUF)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return total
    }

    private fun writeHead(
        out: OutputStream, code: Int, ctype: String, len: Long, contentRange: String?
    ) {
        val reason = when (code) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"
            403 -> "Forbidden"; 404 -> "Not Found"; 502 -> "Bad Gateway"
            else -> "Status"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $ctype\r\n")
        if (len >= 0) sb.append("Content-Length: $len\r\n")
        contentRange?.let { sb.append("Content-Range: $it\r\n") }
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun param(query: String, key: String): String? {
        for (pair in query.split('&')) {
            val i = pair.indexOf('=')
            if (i > 0 && pair.substring(0, i) == key) return try {
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun hostOf(u: String) = try {
        URL(u).host ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun shortUrl(u: String) = "${hostOf(u)}..${u.takeLast(24)}"

    fun obs(event: String, message: String) = Unit
}

/** Hydrax R2 transport, matching player-v2/sw.bundle.v3.js.
 * Part zero has no suffix; subsequent parts append their index to the filename.
 * Only the first 64 KiB is AES-CTR encrypted, keyed by MD5 of that filename.
 * Keep this separate from the existing TurboVIP/FileLions HLS transport.
 */
internal object HydraxMedia {
    private const val PREFIX_SIZE = 65536
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    private class Media(val url: String, val size: Long, val part: Long) {
        @Volatile var prefix: ByteArray? = null
    }

    // Bound retained metadata and decrypted prefixes across repeated searches.
    private val media = object : LinkedHashMap<String, Media>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Media>?) = size > 128
    }

    @Synchronized fun clear() { media.clear() }

    private fun allowed(raw: String): Boolean = runCatching {
        val u = URL(raw)
        u.protocol == "https" && (u.port == -1 || u.port == 443) && u.userInfo == null &&
            u.host.lowercase().endsWith(".sssrr.org")
    }.getOrDefault(false)

    @Synchronized
    fun register(url: String, size: Long, partSize: Long): String? {
        if (!allowed(url) || size < PREFIX_SIZE || partSize < 0 || partSize > size) return null
        val part = partSize.takeIf { it > 0 } ?: size
        if (part < PREFIX_SIZE) return null
        val id = hex(MessageDigest.getInstance("SHA-256").digest("$url|$size|$part".toByteArray()))
        if (!media.containsKey(id)) media[id] = Media(url, size, part)
        return id
    }

    private fun open(url: String, start: Long, end: Long): HttpURLConnection {
        var current = url
        repeat(6) {
            if (!allowed(current)) throw IOException("Hydrax storage host rejected")
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://abyssplayer.com/")
                setRequestProperty("Origin", "https://abyssplayer.com")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=$start-$end")
            }
            try {
                val code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = c.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    current = URL(URL(current), location).toString()
                    c.disconnect()
                } else {
                    val cr = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
                        .matchEntire(c.getHeaderField("Content-Range") ?: "")
                    if (code != 206 || cr == null || cr.groupValues[1].toLong() != start ||
                        cr.groupValues[2].toLong() != end) throw IOException("Invalid Hydrax range: HTTP $code")
                    return c
                }
            } catch (e: Exception) {
                c.disconnect()
                throw e
            }
        }
        throw IOException("Too many Hydrax redirects")
    }

    private fun copy(c: HttpURLConnection, out: OutputStream, length: Long) {
        try {
            c.inputStream.use { input ->
                val buffer = ByteArray(PREFIX_SIZE)
                var remaining = length
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw EOFException("Truncated Hydrax storage")
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun prefix(m: Media): ByteArray = synchronized(m) {
        m.prefix?.let { return@synchronized it }
        val out = java.io.ByteArrayOutputStream(PREFIX_SIZE)
        copy(open(m.url, 0, PREFIX_SIZE - 1L), out, PREFIX_SIZE.toLong())
        val original = out.toByteArray()
        val filename = URL(m.url).path.substringAfterLast('/')
        val key = hex(MessageDigest.getInstance("MD5").digest(filename.toByteArray(Charsets.UTF_8)))
            .toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
        val decoded = cipher.doFinal(original)
        val result = when {
            isMp4(original) -> original
            isMp4(decoded) -> decoded
            else -> throw IOException("Hydrax MP4 header invalid")
        }
        m.prefix = result
        result
    }

    private fun isMp4(bytes: ByteArray) = String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun head(out: OutputStream, code: Int, length: Long, range: String? = null) {
        val reason = when (code) {
            200 -> "OK"; 206 -> "Partial Content"; 404 -> "Not Found"
            416 -> "Range Not Satisfiable"; else -> "Bad Gateway"
        }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: video/mp4\r\n" +
            "Content-Length: $length\r\nAccept-Ranges: bytes\r\n" +
            (range?.let { "Content-Range: $it\r\n" } ?: "") +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
    }

    fun serve(id: String, range: String?, headOnly: Boolean, out: OutputStream): Long {
        val m = synchronized(this) { media[id] } ?: run { head(out, 404, 0); return 0 }
        var start = 0L
        var end = m.size - 1
        if (range != null) {
            val match = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(range.trim())
            val a = match?.groupValues?.get(1)?.toLongOrNull()
            val b = match?.groupValues?.get(2)?.toLongOrNull()
            if (match == null ||
                (match.groupValues[1].isNotEmpty() && a == null) ||
                (match.groupValues[2].isNotEmpty() && b == null) ||
                (a == null && b == null) || (a == null && b == 0L)) {
                head(out, 416, 0, "bytes */${m.size}"); return 0
            }
            if (a == null) start = (m.size - b!!).coerceAtLeast(0)
            else { start = a; if (b != null) end = minOf(end, b) }
            if (start >= m.size || start > end) { head(out, 416, 0, "bytes */${m.size}"); return 0 }
        }
        val length = end - start + 1
        // Validate/decrypt before sending a success response, including seeks.
        val first = if (headOnly) null else try { prefix(m) } catch (e: Exception) {
            LocalProxy.obs("HX-MEDIA-FAIL", e.javaClass.simpleName + ": " + e.message)
            head(out, 502, 0); return 0
        }
        head(out, if (range == null) 200 else 206, length,
            if (range == null) null else "bytes $start-$end/${m.size}")
        if (headOnly) return 0
        var pos = start
        if (pos < PREFIX_SIZE) {
            val count = (minOf(end + 1, PREFIX_SIZE.toLong()) - pos).toInt()
            out.write(first!!, pos.toInt(), count)
            pos += count
        }
        while (pos <= end) {
            val index = pos / m.part
            val local = pos % m.part
            val last = minOf(end, (index + 1) * m.part - 1)
            val url = m.url + if (index == 0L) "" else index.toString()
            copy(open(url, local, local + last - pos), out, last - pos + 1)
            pos = last + 1
        }
        out.flush()
        return length
    }
}
