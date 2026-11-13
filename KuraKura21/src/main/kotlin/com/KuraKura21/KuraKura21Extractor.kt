package com.kurakura21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import kotlin.coroutines.coroutineContext
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.Buffer
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Selected detail -> Muvipro AJAX -> bounded iframe chain -> verified public HTTP extractor. */
internal class KuraKura21Resolver(private val mainUrl: String, private val headers: Map<String, String>,
    private val sourceName: String = "KURAKURA21", private val limits: KuraKura21HttpLimits = KuraKura21HttpLimits()) {
    suspend fun load(detailUrl: String, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val trace = KuraKura21HttpTrace()
        val session = KuraKura21HttpSession(headers, limits)
        var delivered = false
        withTimeoutOrNull(limits.totalMs) {
            val response = try { session.request(detailUrl, "$mainUrl/") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { trace.event("DETAIL_FETCH", "HTTP_ERROR"); return@withTimeoutOrNull }
            trace.event("DETAIL_FETCH", "HTTP_STATUS", response.status)
            if (response.status !in 200..299 || response.truncated || !response.mime.contains("html") ||
                KuraKura21MediaPolicy.origin(response.url) != KuraKura21MediaPolicy.origin(mainUrl)) return@withTimeoutOrNull
            val player = KuraKura21PlayerData.parse(response.text()) ?: run {
                trace.event("PLAYER_PARSE", "PLAYER_MISSING"); return@withTimeoutOrNull
            }
            val title = KuraKura21Metadata.detailTitle(Jsoup.parse(response.text(), response.url))
            trace.event("PLAYER_PARSE", "TABS_READY", player.tabs.size)
            // Media emission is load-scoped; recursive traversal is tab-scoped.
            // A shared iframe can legitimately resolve differently for another tab/session step.
            val emittedMedia = mutableSetOf<String>()
            for ((tab, label) in player.tabs) {
                val visited = mutableSetOf<String>()
                coroutineContext.ensureActive()
                trace.selectServer(tab)
                val result = withTimeoutOrNull(limits.serverMs) server@ {
                    try {
                        val ajax = session.request("$mainUrl/wp-admin/admin-ajax.php", response.url,
                            post = KuraKura21PlayerData.ajaxData(tab, player.postId), origin = mainUrl)
                        trace.event("AJAX_FETCH", "HTTP_STATUS", ajax.status)
                        if (ajax.status !in 200..299 || ajax.truncated ||
                            !(ajax.mime.contains("html") || ajax.mime.contains("json"))) return@server null
                        val frames = KuraKura21PlayerData.frames(ajax.text(), response.url)
                        trace.event("IFRAME_PARSE", "FRAMES_READY", frames.size)
                        for (frame in frames.take(5)) {
                            try {
                                val found = resolve(frame, response.url, 0, session, visited, title, label, trace)
                                if (found != null) return@server found
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { trace.event("EXTRACTOR_HTTP", "FRAME_FAILED") }
                        }
                        null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { trace.event("EXTRACTOR_HTTP", "SERVER_FAILED"); null }
                }
                if (result != null) {
                    // Preserve the full query: different signed resources are not assumed identical.
                    // A URL fragment is not sent in the HTTP request and must not duplicate a link.
                    if (emittedMedia.add(result.first.url.substringBefore('#'))) {
                        callback(result.first)
                        delivered = true
                        trace.event("EXTRACTOR_EMIT", "DELIVERED")
                        for (subtitle in result.second) try { subtitleCallback(subtitle) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { trace.event("SUBTITLE", "CALLBACK_FAILED") }
                    } else {
                        trace.event("EXTRACTOR_EMIT", "DUPLICATE_MEDIA_SKIPPED")
                    }
                } else {
                    trace.event("EXTRACTOR_HTTP", "SERVER_NO_HTTP_MEDIA")
                }
            }
        }
        trace.event("CLEANUP", if (delivered) "HTTP_COMPLETE" else "NO_HTTP_MEDIA", session.requestCount)
        delivered
    }

    private suspend fun resolve(url: String, parent: String, depth: Int, session: KuraKura21HttpSession,
        visited: MutableSet<String>, title: String?, label: String, trace: KuraKura21HttpTrace): Pair<ExtractorLink, List<SubtitleFile>>? {
        coroutineContext.ensureActive()
        if (depth > 2 || !KuraKura21MediaPolicy.isPlayerHost(url) || KuraKura21MediaPolicy.isAd(url) || !visited.add(url)) return null
        if (KuraKura21MediaPolicy.host(url) == "turtle4up.top") {
            var link: ExtractorLink? = null
            val subtitles = mutableListOf<SubtitleFile>()
            KuraKura21TurtleExtractor(session, headers, sourceName, label, title, trace)
                .getUrl(url, parent, { subtitles.add(it) }, { if (link == null) link = it })
            return link?.let { it to subtitles.toList() }
        }
        if (KuraKura21PublicPlayerData.supports(url)) {
            var link: ExtractorLink? = null
            KuraKura21PublicPlayerExtractor(session, headers, sourceName, label, title, trace)
                .getUrl(url, parent, {}, { if (link == null) link = it })
            return link?.let { it to emptyList() }
        }
        if (depth == 2) return null
        val page = session.request(url, parent)
        if (page.status !in 200..299 || page.truncated || !page.mime.contains("html")) return null
        val children = KuraKura21PlayerData.frames(page.text(), page.url).toMutableList()
        if (children.isEmpty() && KuraKura21MediaPolicy.host(page.url) == "kurakura21.space") {
            // Observed public SPA contract: selected /e/{code}/... -> same-origin
            // embed/details -> explicit nested iframe. Never emulate its playback/auth checks.
            val code = KuraKura21PlayerData.publicEmbedCode(page.url)
            if (code != null) {
                val endpoint = "https://kurakura21.space/api/videos/$code/embed/details"
                val metadata = session.request(endpoint, page.url)
                trace.event("EXTRACTOR_HTTP", "EMBED_METADATA_STATUS", metadata.status)
                if (metadata.status in 200..299 && metadata.url == endpoint && !metadata.truncated && metadata.mime.contains("json")) {
                    KuraKura21PlayerData.publicNestedFrame(metadata.text(), page.url, title)?.let(children::add)
                }
            }
        }
        for (child in children.take(3)) {
            try {
                resolve(child, page.url, depth + 1, session, visited, title, label, trace)?.let { return it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { trace.event("EXTRACTOR_HTTP", "NESTED_FRAME_FAILED") }
        }
        // SPA/session-gated players have no verified direct contract. Never emulate their protection.
        trace.event("EXTRACTOR_HTTP", "NO_VERIFIED_HTTP_CONTRACT")
        return null
    }
}

/** Pure parsing/policy code is exercised by the module's offline regression harness. */
internal object KuraKura21PlayerData {
    data class Player(val postId: String, val tabs: List<Pair<String, String>>)

    fun parse(html: String): Player? {
        val player = Jsoup.parse(html).selectFirst("#muvipro_player_content_id") ?: return null
        val id = player.attr("data-id").takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val tabs = player.select("ul.muvipro-player-tabs a[href^='#p']").mapNotNull { link ->
            val tab = link.attr("href").removePrefix("#")
            if (!tab.matches(Regex("p[0-9]+"))) null else tab to link.text().trim().ifBlank { "Server $tab" }
        }.distinctBy { it.first }.take(12)
        return if (tabs.isEmpty()) null else Player(id, tabs)
    }

    fun ajaxData(tab: String, postId: String) = mapOf(
        "action" to "muvipro_player_content", "tab" to tab, "post_id" to postId
    )

    fun publicEmbedCode(url: String): String? {
        val uri = KuraKura21MediaPolicy.safeUri(url) ?: return null
        if (uri.host.lowercase() != "kurakura21.space") return null
        val parts = uri.path.trim('/').split('/')
        return parts.getOrNull(1)?.takeIf {
            parts.size in 2..3 && parts.first() == "e" && it.matches(Regex("[A-Za-z0-9_-]{4,64}"))
        }
    }

    fun publicNestedFrame(raw: String, selectedPlayer: String, expectedTitle: String?): String? {
        val code = publicEmbedCode(selectedPlayer) ?: return null
        val metadata = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        // Observed wrapper metadata splits the publisher label "Kelas Bintang",
        // while the selected WordPress title uses "Kelasbintang". Ignore that
        // publisher label only here, after exact selected-code binding; retain
        // movie words, years and explicit part-number conflict checks.
        fun movieTitle(value: String) = value.replace(Regex("\\bkelas\\s*bintang\\b", RegexOption.IGNORE_CASE), " ")
        if (metadata.optString("code") != code || metadata.opt("owner_private") != false ||
            !KuraKura21TurtleExtractor.matchesTitle(movieTitle(expectedTitle.orEmpty()), movieTitle(metadata.optString("title")))) return null
        val nested = KuraKura21MediaPolicy.absolute(metadata.optString("embed_frame_url"), selectedPlayer) ?: return null
        // Only this delegated player family was actually declared by the observed API.
        return nested.takeIf { KuraKura21MediaPolicy.host(it) == "n1mwq.org" && !KuraKura21MediaPolicy.isAd(it) }
    }

    fun frames(raw: String, base: String): List<String> {
        // Muvipro normally returns HTML. Also accept a JSON-encoded HTML string;
        // never scan unrelated script text or every URL in an AJAX response.
        val text = if (raw.trimStart().startsWith('"') || raw.trimStart().startsWith('{')) {
            when (val value = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull()) {
                is String -> value
                is JSONObject -> if (value.optBoolean("success")) value.opt("data") as? String else null
                else -> null
            } ?: return emptyList()
        } else raw
        return Jsoup.parseBodyFragment(text, base).select("iframe[src]").mapNotNull {
            KuraKura21MediaPolicy.absolute(it.attr("src"), base)
        }.filterNot(KuraKura21MediaPolicy::isAd).distinct()
    }
}

/** Public client data encoding observed in Turtle4Up's served player bundle.
 * Encodes the selected alias only. Never generates fingerprint, ad/headless status or view beacons. */
internal object KuraKura21TurtleCodec {
    private val keyChars = listOf("𝕊", "𝔼", "𝕮", "𝚁", "𝔼", "𝚃", "𝓚", "𝓔", "𝓨", "𝟙", "𝟚", "𝟛")
    private val saltChars = listOf("🔐", "🗝️", "🔑", "🚪", "🛡️", "⚡")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun bytes(text: String): ByteArray {
        require(text.length in 32..200_000 && text.length % 32 == 0 && text.matches(Regex("[0-9a-fA-F]+")))
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun cipher(mode: Int, host: String): Cipher {
        val key = (keyChars.joinToString("") { it.codePointAt(0).toString(16) } +
            (System.currentTimeMillis() / 86_400_000L).toString(16)).take(32).padEnd(32, '0')
        val iv = (saltChars.joinToString("") { it.codePointAt(0).toString(16) } +
            hex(host.lowercase().toByteArray(StandardCharsets.UTF_8))).take(32).padEnd(32, '0')
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(bytes(key), "AES"), IvParameterSpec(bytes(iv)))
        }
    }
    fun encode(alias: String, host: String): String = hex(cipher(Cipher.ENCRYPT_MODE, host)
        .doFinal(alias.toByteArray(StandardCharsets.UTF_8)))
    fun decode(value: String, host: String): String = String(cipher(Cipher.DECRYPT_MODE, host)
        .doFinal(bytes(value)), StandardCharsets.UTF_8)
}

internal data class KuraKura21HttpLimits(val totalMs: Long = 55_000L,
    val serverMs: Long = 20_000L, val requests: Int = 24) {
    init { require(totalMs > 0 && serverMs > 0 && requests > 0) }
}

/** Locally generated diagnostics only; no URL, identity, token, cookie or exception message. */
internal class KuraKura21HttpTrace {
    private var events = 0
    private var server = "NONE"
    fun selectServer(tab: String) { server = tab.takeIf { it.matches(Regex("p[0-9]{1,3}")) } ?: "UNKNOWN" }
    @Synchronized fun event(stage: String, reason: String, number: Int? = null) {
        if (events++ >= 64) return
        val s = stage.takeIf { it.matches(Regex("[A-Z_]{1,30}")) } ?: "UNKNOWN"
        val r = reason.takeIf { it.matches(Regex("[A-Z_]{1,40}")) } ?: "UNKNOWN"
        Log.i("KuraKura21", "build=FINAL3_HTTP server=$server stage=$s reason=$r" + (number?.let { " count_or_status=$it" } ?: ""))
    }
}

internal data class KuraKura21HttpData(val url: String, val status: Int, val mime: String,
    val body: ByteArray, val headers: Headers, val truncated: Boolean) {
    fun text(): String = String(body, StandardCharsets.UTF_8)
}

internal class KuraKura21HttpCookies : CookieJar {
    private val values = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis() && values.size < 32) values.add(cookie)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return values.filter { it.matches(url) }
    }
}

/** Fresh normal HTTP cookies are scoped to this load and their actual domain/path.
 * No browser cookies or global identities are imported, exported, or forwarded as manual headers. */
internal class KuraKura21HttpSession(private val defaults: Map<String, String>,
    private val limits: KuraKura21HttpLimits) {
    private val cookies = KuraKura21HttpCookies()
    private val client = app.baseClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(cookies).connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()
    private var count = 0
    private val publicSuffixes = mutableMapOf<String, String>()
    fun publicDoodSuffix(identity: String, alphabet: String, suffix: String): String = publicSuffixes.getOrPut(identity + suffix) {
        // Same-load reuse prevents synthetic URL padding from defeating media deduplication.
        val random = java.security.SecureRandom()
        (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") + suffix + System.currentTimeMillis()
    }
    val requestCount: Int get() = count

    suspend fun request(url: String, referer: String, post: Map<String, String>? = null,
        origin: String? = null, media: KuraKura21HttpScope? = null, probe: Boolean = false): KuraKura21HttpData {
        var current = url
        var form = post
        repeat(3) {
            if (count >= limits.requests) throw IOException("Request budget")
            count++
            require(KuraKura21MediaPolicy.safeUri(current) != null && !KuraKura21MediaPolicy.isAd(current))
            if (media != null) require(media.allows(current))
            else require(KuraKura21MediaPolicy.host(current) in playerHosts)
            val builder = Request.Builder().url(current)
            for ((name, value) in defaults) if (name.equals("User-Agent", true) || name.equals("Accept-Language", true))
                builder.header(name, value)
            builder.header("Referer", referer).header("Accept", "*/*")
            origin?.let { value -> builder.header("Origin", value) }
            if (form != null) {
                val body = FormBody.Builder().apply { form!!.forEach { (key, value) -> add(key, value) } }.build()
                builder.post(body).header("X-Requested-With", "XMLHttpRequest")
            }
            if (probe && (media?.directProbe == true || URI(current).path.endsWith(".mp4", true))) builder.header("Range", "bytes=0-31")
            val result = exchange(builder.build(), probe)
            if (result.status !in setOf(301, 302, 303, 307, 308)) return result
            if (URI(url).path.startsWith("/api/")) throw IOException("API redirect unsupported")
            val next = KuraKura21MediaPolicy.absolute(result.headers["Location"].orEmpty(), current)
                ?: throw IOException("Redirect target")
            // Public player redirects stay within known hosts. Media redirects never leave this exact scope.
            if (media != null) require(media.authorizeRedirect(current, next))
            else require(KuraKura21MediaPolicy.host(next) in playerHosts)
            if (form != null) require(KuraKura21MediaPolicy.origin(next) == KuraKura21MediaPolicy.origin(url))
            current = next
            if (result.status == 303 || (result.status in setOf(301, 302) && form != null)) form = null
        }
        throw IOException("Redirect budget")
    }

    private suspend fun exchange(request: Request, probe: Boolean): KuraKura21HttpData = suspendCancellableCoroutine { continuation ->
        val timeout = when {
            request.method == "POST" -> 12L
            request.url.encodedPath.startsWith("/api/") -> 8L
            probe || request.url.encodedPath.endsWith(".m3u8") -> 8L
            else -> 12L
        }
        val call = client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val data = response.use {
                        val source = it.body.source()
                        val buffer = Buffer()
                        var maximum = 1_000_000L
                        if (probe) {
                            while (buffer.size < 16 && source.read(buffer, 16 - buffer.size) != -1L) { }
                            if (KuraKura21MediaPolicy.mp4Header(buffer.clone().readByteArray())) maximum = 16
                        }
                        if (maximum != 16L) while (buffer.size <= maximum) {
                            if (source.read(buffer, minOf(8192L, maximum + 1 - buffer.size)) == -1L) break
                        }
                        val truncated = buffer.size > maximum
                        KuraKura21HttpData(it.request.url.toString(), it.code,
                            it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(),
                            buffer.readByteArray(), it.headers, truncated)
                    }
                    if (continuation.isActive) continuation.resume(data)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
    companion object {
        val playerHosts = setOf("kurakura21.com", "www.kurakura21.com", "kr21.click", "n1mwq.org", "turtle4up.top", "k21cdn.click", "kurakura21.space", "dooood.com", "playmogo.com", "streamtape.xyz", "sbrapid.com")
    }
}

/** The exact main-media URL is delegated by the selected alias's HTTPS resource response.
 * Restrict that delegation to observed CDN families and its video-specific directory namespace. */
internal class KuraKura21HttpScope private constructor(val root: String, private val delegatedOrigin: String?,
    private val directFamily: String? = null) {
    constructor(root: String) : this(root, null)
    val directProbe: Boolean get() = directFamily != null
    private val directUrls = mutableSetOf(root.substringBefore('#'))
    private val uri = KuraKura21MediaPolicy.safeUri(root)
    private val prefix = uri?.path?.substringBeforeLast('/', "")?.plus("/").orEmpty()
    private val host = uri?.host?.lowercase()
    val valid = uri != null && !KuraKura21MediaPolicy.isAd(root) && if (directFamily != null) {
        when (directFamily) {
            "dood" -> host?.endsWith(".cloudatacdn.com") == true
            "streamtape" -> host == "streamtape.xyz" && uri.path == "/get_video"
            else -> false
        }
    } else (host == "turtle4up.top" || host?.endsWith(".goldencrestdigital.shop") == true ||
            delegatedOrigin != null && KuraKura21MediaPolicy.origin(root) == delegatedOrigin) &&
        prefix.trim('/').split('/').filter(String::isNotEmpty).size >= 2
    fun allows(url: String): Boolean {
        val other = KuraKura21MediaPolicy.safeUri(url) ?: return false
        if (directFamily != null) return valid && url.substringBefore('#') in directUrls && !KuraKura21MediaPolicy.isAd(url)
        return valid && other.host.equals(host, true) && other.path.startsWith(prefix) && !KuraKura21MediaPolicy.isAd(url)
    }
    fun authorizeRedirect(from: String, next: String): Boolean {
        if (directFamily == null) return allows(next) // Existing Turtle scope remains unchanged.
        if (!allows(from) || KuraKura21MediaPolicy.isAd(next)) return false
        val target = KuraKura21MediaPolicy.safeUri(next) ?: return false
        val familyMatches = when (directFamily) {
            "dood" -> target.host.endsWith(".cloudatacdn.com", true)
            "streamtape" -> target.host.endsWith(".tapecontent.net", true)
            else -> false
        }
        if (!familyMatches) return false
        // Grant this exact server-issued Location only, not the CDN's other videos.
        directUrls.add(next.substringBefore('#'))
        return true
    }
    fun dash(text: String, url: String): Boolean {
        if (!allows(url) || text.length > 1_000_000 || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
                setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                    override fun error(error: org.xml.sax.SAXParseException) { throw error }
                    override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
                })
            }
            val doc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(text)))
            val root = doc.documentElement
            if (root.localName != "MPD" || root.namespaceURI != "urn:mpeg:dash:schema:mpd:2011") return false
            if (doc.getElementsByTagNameNS("*", "ContentProtection").length > 0 ||
                doc.getElementsByTagNameNS("*", "pssh").length > 0 ||
                doc.getElementsByTagNameNS("*", "Representation").length == 0) return false
            val all = doc.getElementsByTagName("*")
            if (all.length > 10_000) return false
            for (index in 0 until all.length) {
                val item = all.item(index) as org.w3c.dom.Element
                if (item.hasAttributeNS("http://www.w3.org/XML/1998/namespace", "base")) return false
                for (attributeIndex in 0 until item.attributes.length) {
                    if (item.attributes.item(attributeIndex).namespaceURI == "http://www.w3.org/1999/xlink") return false
                }
                if (item.localName in setOf("BaseURL", "Location", "PatchLocation")) {
                    if (!allows(KuraKura21MediaPolicy.absolute(item.textContent.trim(), url) ?: return false)) return false
                }
                for (name in listOf("media", "initialization", "sourceURL", "href")) if (item.hasAttribute(name)) {
                    if (!allows(KuraKura21MediaPolicy.absolute(item.getAttribute(name), url) ?: return false)) return false
                }
            }
            true
        } catch (_: Exception) { false }
    }
    companion object {
        fun fromPublicPrimary(root: String, selectedPlayer: String): KuraKura21HttpScope? {
            val family = when (KuraKura21MediaPolicy.host(selectedPlayer)) {
                "dooood.com", "playmogo.com" -> "dood"
                "streamtape.xyz" -> "streamtape"
                else -> return null
            }
            if (family == "streamtape") {
                val id = KuraKura21PublicPlayerData.embedId(selectedPlayer) ?: return null
                val query = HttpUrl.Builder().scheme("https").host("streamtape.xyz").build().resolve(root) ?: return null
                if (query.queryParameterValues("id") != listOf(id)) return null
            }
            return KuraKura21HttpScope(root, null, family).takeIf { it.valid }
        }
        /** Grant only URLs in the selected-alias API's primary stream fields, never ads or scanned HTML.
         * The API response cannot redirect, must have the exact expected URL, and must share the player origin.
         * A delegated CDN remains pinned to its own origin + video directory for every subsequent request. */
        fun fromResource(response: KuraKura21HttpData, player: String, expectedApi: String): List<KuraKura21HttpScope> {
            require(response.status in 200..299 && !response.truncated && response.url == expectedApi)
            require(KuraKura21MediaPolicy.origin(response.url) == KuraKura21MediaPolicy.origin(player))
            require(URI(response.url).path == "/api/stream/tracking" && KuraKura21MediaPolicy.host(player) == "turtle4up.top")
            val encoded = response.headers["x-tracking"] ?: return emptyList()
            val stream = JSONObject(KuraKura21TurtleCodec.decode(encoded, URI(player).host)).optJSONObject("stream") ?: return emptyList()
            return listOf("hls", "tiktok", "direct").mapNotNull { field ->
                val root = (stream.opt(field) as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val known = KuraKura21HttpScope(root)
                if (known.valid) known else KuraKura21HttpScope(root, KuraKura21MediaPolicy.origin(root))
            }.distinctBy { it.root }
        }
    }
    fun playlist(text: String, url: String): Boolean {
        if (!allows(url) || text.length > 1_000_000) return false
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U") return false
        var pending = false; var count = 0; var master = false; var media = false
        for (line in lines.drop(1)) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (pending || media) return false
                pending = true; master = true
            } else if (line.startsWith("#EXTINF:")) {
                val duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return false
                if (pending || master || !duration.isFinite() || duration <= 0) return false
                pending = true; media = true
            } else if (!line.startsWith('#')) {
                if (!pending || !allows(KuraKura21MediaPolicy.absolute(line, url) ?: return false)) return false
                pending = false; count++
            }
            if (line.startsWith("#EXT")) {
                if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                    val methods = line.substringAfter(':').split(',').map(String::trim).filter { it.startsWith("METHOD=") }
                    if (methods != listOf("METHOD=NONE")) return false // No unsupported encryption/DRM flow.
                }
                val attributes = line.substringAfter(':', "")
                val uriPattern = Regex("(?:^|,)\\s*URI=\"([^\"]*)\"")
                val uris = uriPattern.findAll(attributes).toList()
                if (Regex("(?:^|,)\\s*URI=").findAll(attributes).count() != uris.size || uris.size > 1) return false
                val uri = uris.singleOrNull()
                if (attributes.contains(Regex("(?:^|,)\\s*URI=")) && uri == null) return false
                if ((line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) && uri == null) return false
                if (uri != null && !allows(KuraKura21MediaPolicy.absolute(uri.groupValues[1], url) ?: return false)) return false
            }
        }
        return !pending && count > 0 && (master || media)
    }
}

/** Custom ExtractorApi for the verified public Turtle4Up/Turbovio contract, not StreamSB. */
internal class KuraKura21TurtleExtractor(private val session: KuraKura21HttpSession,
    private val requestHeaders: Map<String, String>, private val sourceName: String,
    private val label: String, private val titleHint: String?, private val trace: KuraKura21HttpTrace) : ExtractorApi() {
    override val name = "Turtle4Up HTTP"
    override val mainUrl = "https://turtle4up.top"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit) {
        val uri = KuraKura21MediaPolicy.safeUri(url) ?: return
        if (uri.host.lowercase() != "turtle4up.top") return
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 1 || !parts[0].matches(Regex("[A-Za-z0-9_-]{4,64}")) || uri.fragment != null) return
        val alias = parts[0]
        val parsed = HttpUrl.Builder().scheme("https").host(uri.host).addPathSegment("api").addPathSegment("stream")
        parsed.addQueryParameter("s", alias)
        val query = requestQuery(url)
        if (query.keys.any { it != "pv" }) return
        val pv = query["pv"]
        if (pv != null && !pv.matches(Regex("[A-Za-z0-9._-]{1,64}"))) return
        pv?.let { parsed.addQueryParameter("pv", it) }
        val metadata = session.request(parsed.build().toString(), url)
        trace.event("EXTRACTOR_HTTP", "METADATA_STATUS", metadata.status)
        if (metadata.status !in 200..299 || metadata.truncated || !metadata.mime.contains("json")) return
        val data = JSONObject(metadata.text()).optJSONObject("data") ?: return
        val title = (data.opt("title") as? String)?.trim() ?: return
        if (title.isEmpty() || !matchesTitle(titleHint, title)) {
            trace.event("IDENTITY_CHECK", "TITLE_MISMATCH"); return
        }
        val api = HttpUrl.Builder().scheme("https").host(uri.host).addPathSegment("api").addPathSegment("stream")
            .addPathSegment("tracking").addQueryParameter("c", KuraKura21TurtleCodec.encode(alias, uri.host))
        pv?.let { api.addQueryParameter("pv", it) }
        val resource = session.request(api.build().toString(), url)
        trace.event("EXTRACTOR_HTTP", "RESOURCE_STATUS", resource.status)
        if (resource.status !in 200..299 || resource.truncated) return
        val scopes = KuraKura21HttpScope.fromResource(resource, url, api.build().toString())
        if (scopes.isEmpty()) trace.event("MEDIA_REJECT", "PRIMARY_SOURCE_MISSING")
        for (scope in scopes) {
            val candidate = scope.root
            if (!scope.valid) { trace.event("MEDIA_REJECT", "UNTRUSTED_SCOPE"); continue }
            val origin = KuraKura21MediaPolicy.origin(url) ?: return
            val type = try { validate(candidate, url, origin, scope) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { trace.event("MEDIA_REJECT", "CANDIDATE_HTTP_ERROR"); null } ?: continue
            val playbackHeaders = requestHeaders.filterKeys { it.equals("User-Agent", true) || it.equals("Accept-Language", true) } +
                mapOf("Referer" to url, "Origin" to origin)
            callback(newExtractorLink(source = sourceName, name = "$sourceName - $label (HTTP)", url = candidate, type = type) {
                this.referer = url
                headers = playbackHeaders
                quality = Qualities.Unknown.value
            })
            trace.event("MEDIA_CANDIDATE", "VALIDATED")
            val subtitles = data.optJSONArray("subtitles")
            if (subtitles != null) for (i in 0 until minOf(subtitles.length(), 20)) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val target = sub.optString("url")
                if (scope.allows(target)) subtitleCallback(newSubtitleFile(sub.optString("lang").ifBlank { "Unknown" }, target))
            }
            return
        }
    }

    private suspend fun validate(url: String, referer: String, origin: String, scope: KuraKura21HttpScope): ExtractorLinkType? {
        val response = session.request(url, referer, origin = origin, media = scope, probe = true)
        if (response.status !in 200..299 || response.truncated) { trace.event("MEDIA_REJECT", "MEDIA_HTTP", response.status); return null }
        if (KuraKura21MediaPolicy.mp4Header(response.body)) {
            if (response.mime !in setOf("video/mp4", "application/mp4")) return null
            if (response.status == 206 && !response.headers["Content-Range"].orEmpty().startsWith("bytes 0-")) return null
            return ExtractorLinkType.VIDEO
        }
        if (response.mime in setOf("application/dash+xml", "application/xml", "text/xml") ||
            response.mime in setOf("text/plain", "application/octet-stream") &&
                response.text().trimStart().let { it.startsWith("<MPD") || it.startsWith("<?xml") }) {
            if (scope.dash(response.text(), response.url)) return ExtractorLinkType.DASH
            trace.event("MEDIA_REJECT", "DASH_SCOPE_OR_DRM"); return null
        }
        if (!hlsMime(response.mime) || !scope.playlist(response.text(), response.url)) {
            trace.event("MEDIA_REJECT", "MANIFEST_SCOPE"); return null
        }
        val lines = response.text().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        if (index >= 0) {
            val target = lines.drop(index + 1).firstOrNull { !it.startsWith('#') }
                ?.let { KuraKura21MediaPolicy.absolute(it, response.url) } ?: return null
            if (!scope.allows(target)) return null
            val child = session.request(target, referer, origin = origin, media = scope)
            if (child.status !in 200..299 || child.truncated || !hlsMime(child.mime) || !scope.playlist(child.text(), child.url) ||
                !child.text().lineSequence().any { it.startsWith("#EXTINF:") }) return null
        }
        return ExtractorLinkType.M3U8
    }

    companion object {
        private fun hlsMime(mime: String) = mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl",
            "audio/mpegurl", "audio/x-mpegurl", "text/plain", "application/octet-stream")
        fun matchesTitle(expected: String?, actual: String): Boolean {
            val requested = KuraKura21Metadata.cleanTitle(expected)
            val returned = KuraKura21Metadata.cleanTitle(actual)
            if (requested.isBlank() || returned.isBlank()) return false
            val id = Regex("\\b([A-Z]{2,8})-([0-9]{2,6})\\b", RegexOption.IGNORE_CASE).find(requested)
            if (id != null) return Regex("(?i)(?<![A-Z0-9])${Regex.escape(id.groupValues[1])}[ _-]*${Regex.escape(id.groupValues[2])}(?![A-Z0-9])").containsMatchIn(returned)
            // Non-catalogue titles must agree too; an unrelated upload is never accepted merely for lacking a code.
            fun core(value: String): String = value.lowercase()
                .replace(Regex("^\\s*\\((?:sub[ _-]*(?:indo|indonesia|english)|subtitle[^)]*)\\)\\s*"), "")
                .replace(Regex("\\.(?:mp4|mkv|webm)$"), "")
                .replace(Regex("[^\\p{L}\\p{N}]"), "")
            if (core(requested).isNotEmpty() && core(requested) == core(returned)) return true
            // The verified selected-alias API may use a shorter upload label than the WordPress heading.
            // Require every meaningful label word to belong to the selected title; never match unrelated labels.
            val boilerplate = setOf("kurakura21", "turbovio", "turtle4up", "video", "stream", "movie", "film", "sub", "indo", "indonesia", "english", "subtitle", "mp4", "mkv", "webm")
            fun words(value: String) = Regex("[\\p{L}\\p{N}]+").findAll(value.lowercase())
                .map { it.value }.filter { it.length >= 4 && it !in boilerplate && !it.all(Char::isDigit) }.toSet()
            val years = Regex("\\b(?:19|20)[0-9]{2}\\b")
            val requestedYears = years.findAll(requested).map { it.value }.toSet()
            val returnedYears = years.findAll(returned).map { it.value }.toSet()
            if (requestedYears.isNotEmpty() && returnedYears.isNotEmpty() && requestedYears.intersect(returnedYears).isEmpty()) return false
            val actualWords = words(returned)
            if (actualWords.isEmpty() || !words(requested).containsAll(actualWords)) return false
            // A shared long word must not erase part numbers, Roman numerals or
            // short distinguishing words (e.g. Part 1/2 or Sun/War). Keep the
            // captured shortened-upload-label case, but reject explicit conflicts.
            val neutral = setOf("a", "an", "the", "of", "at", "to", "in", "on", "and", "or", "dan", "di", "ke", "si", "hd", "full", "1080p", "720p")
            fun discriminators(value: String) = Regex("[\\p{L}\\p{N}]+").findAll(value.lowercase())
                .map { it.value }.filter { (it.length >= 2 || it.all(Char::isDigit)) && it !in boilerplate && it !in neutral && !years.matches(it) }
                .map { it.toLongOrNull()?.toString() ?: it }.toSet()
            return discriminators(requested).containsAll(discriminators(returned))
        }
        private fun requestQuery(url: String): Map<String, String> {
            val uri = HttpUrl.Builder().scheme("https").host("turtle4up.top").build().resolve(url) ?: return emptyMap()
            return uri.queryParameterNames.associateWith { uri.queryParameter(it).orEmpty() }
        }
    }
}


/** Only the two observed public player grammars; no downloaded JavaScript execution. */
internal object KuraKura21PublicPlayerData {
    data class Dood(val endpoint: String, val suffix: String, val alphabet: String)
    fun supports(url: String) = KuraKura21MediaPolicy.host(url) in setOf("dooood.com", "playmogo.com", "streamtape.xyz")
    fun embedId(url: String): String? {
        val uri = KuraKura21MediaPolicy.safeUri(url) ?: return null
        val parts = uri.path.trim('/').split('/')
        return parts.getOrNull(1)?.takeIf { parts.size in 2..3 && parts[0] in setOf("e", "v") && it.matches(Regex("[A-Za-z0-9_-]{4,64}")) }
    }
    fun titleMatches(expected: String?, actual: String): Boolean {
        fun clean(value: String) = value.replace(Regex("(?i)(?<![a-z0-9])kelas[ _-]*bintang(?![a-z0-9])"), " ")
            .replace(Regex("(?i)\\bDoodStream\\b"), " ")
        return KuraKura21TurtleExtractor.matchesTitle(clean(expected.orEmpty()), clean(actual))
    }
    private fun script(html: String) = Jsoup.parse(html).select("script:not([src])").joinToString("\n") { it.data() }
    fun dood(html: String, player: String): Dood? {
        if (KuraKura21MediaPolicy.host(player) !in setOf("dooood.com", "playmogo.com")) return null
        val code = script(html)
        val pass = Regex("""[$]\.get\('(/pass_md5/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+)',\s*function\(data\)""").find(code) ?: return null
        if (!Regex("""dsplayer\.src\(\{\s*type:\s*"video/mp4",\s*src:\s*data\s*\+\s*makePlay\(\)""").containsMatchIn(code)) return null
        val function = Regex("""function makePlay\(\)\s*\{(.{1,8192}?)\};""", RegexOption.DOT_MATCHES_ALL).find(code)?.groupValues?.get(1) ?: return null
        if (!function.contains("10 > o") || !function.contains("Math.floor(Math.random() * n)")) return null
        val suffix = Regex("""return a \+ "(\?token=[A-Za-z0-9_-]+&expiry=)" \+ Date\.now\(\)""").find(function)?.groupValues?.get(1) ?: return null
        val alphabet = Regex("""t = "([A-Za-z0-9]{32,100})"""").find(function)?.groupValues?.get(1) ?: return null
        if (suffix.substringAfter("?token=").substringBefore('&') != pass.groupValues[1].substringAfterLast('/')) return null
        val endpoint = KuraKura21MediaPolicy.absolute(pass.groupValues[1], player) ?: return null
        if (KuraKura21MediaPolicy.origin(endpoint) != KuraKura21MediaPolicy.origin(player)) return null
        return Dood(endpoint, suffix, alphabet)
    }
    fun streamTape(html: String, player: String): String? {
        if (KuraKura21MediaPolicy.host(player) != "streamtape.xyz") return null
        val id = embedId(player) ?: return null
        val code = script(html)
        // Match the declared mainvideo consumer, not an arbitrary hidden link or ad URL.
        if (!Regex("""var srclink\s*=\s*[$]\('#botlink'\)\.text\(\)\s*\+\s*'&stream=1'""").containsMatchIn(code) ||
            !Regex("""getElementById\("mainvideo"\)\.src\s*=\s*srclink""").containsMatchIn(code)) return null
        val assignment = Regex("""document\.getElementById\('botlink'\)\.innerHTML\s*=\s*'([^'\\\r\n]{1,128})'\s*\+\s*\('([^'\\\r\n]{1,8192})'\)((?:\.substring\([0-9]{1,3}\)){1,4});""").findAll(code).lastOrNull() ?: return null
        var tail = assignment.groupValues[2]
        for (step in Regex("""substring\(([0-9]+)\)""").findAll(assignment.groupValues[3])) {
            val count = step.groupValues[1].toInt()
            if (count > tail.length) return null
            tail = tail.substring(count)
        }
        val target = KuraKura21MediaPolicy.absolute(assignment.groupValues[1] + tail, player) ?: return null
        val uri = HttpUrl.Builder().scheme("https").host("streamtape.xyz").build().resolve(target) ?: return null
        if (uri.host != "streamtape.xyz" || uri.encodedPath != "/get_video" || uri.queryParameterNames != setOf("id", "expires", "ip", "token") ||
            uri.queryParameterValues("id") != listOf(id) || uri.queryParameterNames.any { uri.queryParameterValues(it).size != 1 || uri.queryParameter(it).isNullOrBlank() }) return null
        return "$target&stream=1"
    }
}

/** Selected public source -> pinned MP4 probe, using the same cancellable request budget. */
internal class KuraKura21PublicPlayerExtractor(private val session: KuraKura21HttpSession,
    private val requestHeaders: Map<String, String>, private val sourceName: String,
    private val label: String, private val titleHint: String?, private val trace: KuraKura21HttpTrace) : ExtractorApi() {
    override val name = "Selected public HTTP"
    override val mainUrl = "https://streamtape.xyz"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (!KuraKura21PublicPlayerData.supports(url) || KuraKura21PublicPlayerData.embedId(url) == null) return
        val page = session.request(url, referer ?: return)
        trace.event("EXTRACTOR_HTTP", "PUBLIC_PLAYER_STATUS", page.status)
        if (page.status !in 200..299 || page.truncated || !page.mime.contains("html") || !KuraKura21PublicPlayerData.supports(page.url)) return
        val document = Jsoup.parse(page.text())
        if (page.headers["cf-mitigated"].equals("challenge", true) || document.title().trim().lowercase() in setOf("just a moment...", "attention required! | cloudflare")) {
            trace.event("EXTRACTOR_HTTP", "CHALLENGE_NOT_COMPLETED"); return
        }
        val family: String
        val candidate: String
        if (KuraKura21MediaPolicy.host(page.url) == "streamtape.xyz") {
            family = "StreamTape"
            // The selected /e/{id}/{slug} and get_video id must agree. The observed
            // upload slug omits a part label; exact selected-id binding is retained.
            val slug = URI(page.url).path.substringAfterLast('/')
            if (!KuraKura21PublicPlayerData.titleMatches(titleHint, slug)) { trace.event("IDENTITY_CHECK", "TITLE_MISMATCH"); return }
            candidate = KuraKura21PublicPlayerData.streamTape(page.text(), page.url) ?: run {
                trace.event("EXTRACTOR_HTTP", "NO_VERIFIED_HTTP_CONTRACT"); return
            }
        } else {
            family = "DoodStream"
            if (!KuraKura21PublicPlayerData.titleMatches(titleHint, document.title())) { trace.event("IDENTITY_CHECK", "TITLE_MISMATCH"); return }
            val contract = KuraKura21PublicPlayerData.dood(page.text(), page.url) ?: run {
                trace.event("EXTRACTOR_HTTP", "NO_VERIFIED_HTTP_CONTRACT"); return
            }
            val resource = session.request(contract.endpoint, page.url)
            trace.event("EXTRACTOR_HTTP", "PUBLIC_RESOURCE_STATUS", resource.status)
            if (resource.status !in 200..299 || resource.truncated || resource.url != contract.endpoint) return
            val base = resource.text().trim()
            if (KuraKura21MediaPolicy.host(base)?.endsWith(".cloudatacdn.com") != true || KuraKura21MediaPolicy.isAd(base)) return
            candidate = base + session.publicDoodSuffix(contract.endpoint, contract.alphabet, contract.suffix)
        }
        val scope = KuraKura21HttpScope.fromPublicPrimary(candidate, page.url) ?: return
        val origin = KuraKura21MediaPolicy.origin(page.url) ?: return
        val probe = session.request(candidate, page.url, origin = origin, media = scope, probe = true)
        trace.event("EXTRACTOR_HTTP", "PUBLIC_MEDIA_STATUS", probe.status)
        if (probe.status !in setOf(200, 206) || probe.truncated || probe.mime !in setOf("video/mp4", "application/mp4") ||
            !KuraKura21MediaPolicy.mp4Header(probe.body) || !scope.allows(probe.url) ||
            probe.status == 206 && !probe.headers["Content-Range"].orEmpty().startsWith("bytes 0-")) return
        val playbackHeaders = requestHeaders.filterKeys { it.equals("User-Agent", true) || it.equals("Accept-Language", true) } +
            mapOf("Referer" to page.url, "Origin" to origin)
        callback(newExtractorLink(source = sourceName, name = "$sourceName - $label (HTTP $family)", url = candidate, type = ExtractorLinkType.VIDEO) {
            this.referer = page.url; headers = playbackHeaders; quality = Qualities.Unknown.value
        })
        trace.event("MEDIA_CANDIDATE", "VALIDATED")
    }
}

internal object KuraKura21MediaPolicy {

        private val playerHosts = setOf("kr21.click", "n1mwq.org", "turtle4up.top", "k21cdn.click", "kurakura21.space", "dooood.com", "playmogo.com", "streamtape.xyz", "sbrapid.com")
        private val adHosts = setOf(
            "sacdnssedge.com", "tsyndicate.com", "mmcdn.com", "jads.co", "stripchat.com",
            "juicyads.com", "exoclick.com", "exosrv.com", "trafficjunky.net",
            "doubleclick.net", "googlesyndication.com"
        )

        fun safeUri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
            it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null &&
                it.port in setOf(-1, 443) && it.normalize().path == it.path &&
                it.path.split('/').none { part -> part == "." || part == ".." } &&
                it.host.contains('.') && it.host.any(Char::isLetter) &&
                !it.host.endsWith(".local", true) && !it.host.endsWith(".localhost", true)
        }
        fun host(url: String): String? = safeUri(url)?.host?.lowercase()
        fun origin(url: String): String? = safeUri(url)?.let { "https://${it.host.lowercase()}" }
        fun isPlayerHost(url: String) = host(url) in playerHosts
        fun mp4Header(bytes: ByteArray): Boolean {
            if (bytes.size < 16) return false
            val size = bytes.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
            return size >= 16 && bytes.copyOfRange(4, 8).contentEquals(byteArrayOf(102, 116, 121, 112))
        }
        fun isAd(url: String): Boolean {
            val uri = safeUri(url) ?: return true
            val host = uri.host.lowercase()
            return adHosts.any { host == it || host.endsWith(".$it") } ||
                Regex("(?:^|/)(?:ads?|preroll|vast|vpaid|promo|sponsor)(?:/|[-_.])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(uri.path)
        }
        fun absolute(raw: String, base: String): String? = runCatching {
            URI(base).resolve(raw.trim().replace("&amp;", "&")).toString()
        }.getOrNull()?.takeIf { safeUri(it) != null }
}
