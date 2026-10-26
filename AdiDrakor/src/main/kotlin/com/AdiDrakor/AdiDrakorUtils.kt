package com.AdiDrakor

import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/** Dipakai di AdiDrakor.load() untuk menandai episode/film yang belum rilis. */
fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        unixTimeMS < dateTime
    } catch (t: Throwable) {
        logError(t)
        false
    }
}

/** Catalog IDs are never treated as TMDB IDs. Missing identity evidence fails closed. */
internal object MediaIdentity {
    data class Request(
        val titles: List<String>,
        val year: Int?,
        val type: Int,
        val season: Int?,
        val episode: Int?,
        val tmdbId: String?,
        val imdbId: String? = null,
        val seasonYear: Int? = null,
    )

    data class Candidate(
        val key: String,
        val titles: List<String>,
        val year: Int?,
        val type: Int,
        val tmdbId: String? = null,
        val imdbId: String? = null,
        val season: Int? = null,
    )

    data class Episode(val url: String, val season: Int?, val number: Int?)

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun valid(r: Request): Boolean =
        r.type in 1..2 &&
            r.titles.any { normalize(it).isNotEmpty() } &&
            (r.type == 1 ||
                (r.season != null && r.season >= 0 && r.episode != null && r.episode > 0))

    fun matches(r: Request, c: Candidate): Boolean {
        if (!valid(r) || c.type != r.type) return false
        if (r.tmdbId != null && c.tmdbId != null && r.tmdbId != c.tmdbId) return false
        if (r.imdbId != null && c.imdbId != null && r.imdbId != c.imdbId) return false
        if (r.type == 2 && c.season != null && c.season != r.season) return false
        val id =
            (r.tmdbId != null && r.tmdbId == c.tmdbId) || (r.imdbId != null && r.imdbId == c.imdbId)
        val sameYear =
            r.year != null && r.year == c.year ||
                (r.type == 2 &&
                    c.season == r.season &&
                    r.seasonYear != null &&
                    r.seasonYear == c.year)
        if (r.year != null && c.year != null && !sameYear) return false
        if (id) return true
        // No fuzzy/token-overlap fallback: aliases must come from selected TMDB metadata.
        return sameYear &&
            c.titles.any { title ->
                val n = normalize(title)
                n.isNotEmpty() && r.titles.any { normalize(it) == n }
            }
    }

    fun select(r: Request, rows: List<Candidate>): Candidate? =
        rows.distinctBy { it.key }.filter { matches(r, it) }.singleOrNull()

    fun episode(r: Request, rows: List<Episode>): Episode? =
        rows
            .distinctBy { it.url }
            .filter { it.season == r.season && it.number == r.episode }
            .singleOrNull()

    fun text(o: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" } }

    fun objects(a: JSONArray?): List<JSONObject> =
        (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }

    private fun year(s: String?): Int? =
        s?.let { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

    fun idlix(key: String, o: JSONObject, type: Int): Candidate =
        Candidate(
            key,
            listOfNotNull(
                text(o, "title", "name"),
                text(o, "originalTitle", "original_title", "originalName"),
            ),
            year(text(o, "releaseDate", "firstAirDate", "release_date", "first_air_date", "year")),
            type,
            text(o, "tmdbId", "tmdb_id"),
            text(o, "imdbId", "imdb_id"),
        )

    fun catalogTitle(raw: String): String =
        raw.replace(Regex("(?i)^(nonton|streaming|download)\\s+"), "")
            .replace(Regex("(?i)\\s*(subtitle\\s+indonesia|sub\\s+indo)\\s*$"), "")
            .replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "")
            .trim()

    fun oppa(key: String, doc: Document): Candidate? {
        val rawTitle =
            doc.selectFirst(".infolimit h2, h1.entry-title, h1[itemprop=name], h1.title")?.text()
                ?: return null
        val title = catalogTitle(rawTitle)
        val facts = doc.select(".spe span, .info-content .info, .infox .info").map { it.text() }
        val type =
            facts.firstOrNull { Regex("(?i)^(tipe|type)\\s*:").containsMatchIn(it) } ?: return null
        val kind =
            when {
                type.contains("Movie", true) -> 1
                Regex("(?i)drama|series|tv show|anime").containsMatchIn(type) -> 2
                else -> return null
            }
        val release =
            facts.firstOrNull {
                Regex("(?i)^(rilis|released|release|tahun|aired|tayang)\\s*:").containsMatchIn(it)
            }
        val seasonMatch = Regex("(?i)\\s+(?:season|s)\\s*(\\d+)\\s*$").find(title)
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
        val plain = seasonMatch?.let { title.substring(0, it.range.first).trim() } ?: title
        val aliases =
            facts
                .filter {
                    Regex("(?i)^(judul lain|other name|native title|alternative titles?)\\s*:")
                        .containsMatchIn(it)
                }
                .flatMap { it.substringAfter(':').split(',', ';') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val links =
            doc.select(".spe a[href], .info-content a[href], .single-info .infox a[href]").map {
                it.attr("href")
            }
        val tmdb =
            links.firstNotNullOfOrNull {
                Regex("themoviedb\\.org/${if(kind==1) "movie" else "tv"}/(\\d+)")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
            }
        val imdb =
            links.firstNotNullOfOrNull {
                Regex("imdb\\.com/title/(tt\\d+)").find(it)?.groupValues?.get(1)
            }
        return Candidate(
            key,
            listOf(plain) + aliases.map(::catalogTitle),
            year(release) ?: Regex("\\((?:19|20)\\d{2}\\)").find(rawTitle)?.value?.let(::year),
            kind,
            tmdb,
            imdb,
            season,
        )
    }

    fun oppaEpisodes(doc: Document, c: Candidate): List<Episode> =
        doc.select(".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li")
            .mapNotNull { li ->
                val a = li.selectFirst("a[href]") ?: return@mapNotNull null
                val title =
                    li.selectFirst(".epl-title, .playinfo h4, .lchx")?.text()
                        ?: a.attr("title").ifBlank { a.text() }
                val number =
                    Regex("(?i)(?:episode|eps|ep)\\s*(\\d+)\\b")
                        .find(title)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                val season =
                    Regex("(?i)(?:season|s)\\s*(\\d+)\\b")
                        .find(title)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?: c.season
                        ?: 1 // unnumbered catalog is season one only; never assume requested
                // season.
                a.absUrl("href").takeIf { it.isNotBlank() }?.let { Episode(it, season, number) }
            }
}

/**
 * Hydrax R2 transport, matching player-v2/sw.bundle.v3.js. Part zero has no suffix; subsequent
 * parts append their index to the filename. Only the first 64 KiB is AES-CTR encrypted, keyed by
 * MD5 of that filename. Keep this separate from the existing TurboVIP/FileLions HLS transport.
 */
internal object HydraxMedia {
    private const val PREFIX_SIZE = 65536
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    private class Media(val url: String, val size: Long, val part: Long) {
        @Volatile var prefix: ByteArray? = null
    }

    // Bound retained metadata and decrypted prefixes across repeated searches.
    private val media =
        object : LinkedHashMap<String, Media>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Media>?) =
                size > 128
        }

    @Synchronized
    fun clear() {
        media.clear()
    }

    private fun allowed(raw: String): Boolean =
        runCatching {
                val u = URL(raw)
                u.protocol == "https" &&
                    (u.port == -1 || u.port == 443) &&
                    u.userInfo == null &&
                    u.host.lowercase().endsWith(".sssrr.org")
            }
            .getOrDefault(false)

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
            val c =
                (URL(current).openConnection() as HttpURLConnection).apply {
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
                    val location =
                        c.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    current = URL(URL(current), location).toString()
                    c.disconnect()
                } else {
                    val cr =
                        Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
                            .matchEntire(c.getHeaderField("Content-Range") ?: "")
                    if (
                        code != 206 ||
                            cr == null ||
                            cr.groupValues[1].toLong() != start ||
                            cr.groupValues[2].toLong() != end
                    )
                        throw IOException("Invalid Hydrax range: HTTP $code")
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

    private fun prefix(m: Media): ByteArray =
        synchronized(m) {
            m.prefix?.let {
                return@synchronized it
            }
            val out = java.io.ByteArrayOutputStream(PREFIX_SIZE)
            copy(open(m.url, 0, PREFIX_SIZE - 1L), out, PREFIX_SIZE.toLong())
            val original = out.toByteArray()
            val filename = URL(m.url).path.substringAfterLast('/')
            val key =
                hex(MessageDigest.getInstance("MD5").digest(filename.toByteArray(Charsets.UTF_8)))
                    .toByteArray(Charsets.US_ASCII)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(key.copyOfRange(0, 16)),
            )
            val decoded = cipher.doFinal(original)
            val result =
                when {
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
        val reason =
            when (code) {
                200 -> "OK"
                206 -> "Partial Content"
                404 -> "Not Found"
                416 -> "Range Not Satisfiable"
                else -> "Bad Gateway"
            }
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: video/mp4\r\n" +
                    "Content-Length: $length\r\nAccept-Ranges: bytes\r\n" +
                    (range?.let { "Content-Range: $it\r\n" } ?: "") +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n")
                .toByteArray()
        )
    }

    fun serve(id: String, range: String?, headOnly: Boolean, out: OutputStream): Long {
        val m =
            synchronized(this) { media[id] }
                ?: run {
                    head(out, 404, 0)
                    return 0
                }
        var start = 0L
        var end = m.size - 1
        if (range != null) {
            val match =
                Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(range.trim())
            val a = match?.groupValues?.get(1)?.toLongOrNull()
            val b = match?.groupValues?.get(2)?.toLongOrNull()
            if (
                match == null ||
                    (match.groupValues[1].isNotEmpty() && a == null) ||
                    (match.groupValues[2].isNotEmpty() && b == null) ||
                    (a == null && b == null) ||
                    (a == null && b == 0L)
            ) {
                head(out, 416, 0, "bytes */${m.size}")
                return 0
            }
            if (a == null) start = (m.size - b!!).coerceAtLeast(0)
            else {
                start = a
                if (b != null) end = minOf(end, b)
            }
            if (start >= m.size || start > end) {
                head(out, 416, 0, "bytes */${m.size}")
                return 0
            }
        }
        val length = end - start + 1
        // Validate/decrypt before sending a success response, including seeks.
        val first =
            if (headOnly) null
            else
                try {
                    prefix(m)
                } catch (e: Exception) {
                    LocalProxy.obs("HX-MEDIA-FAIL", e.javaClass.simpleName + ": " + e.message)
                    head(out, 502, 0)
                    return 0
                }
        head(
            out,
            if (range == null) 200 else 206,
            length,
            if (range == null) null else "bytes $start-$end/${m.size}",
        )
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

/** Loopback proxy for recursive HLS rewriting, dynamic MPEG-TS sync detection,
 * streaming prefix removal and range requests. Unrecognized streams pass through unchanged.
 */
internal object LocalProxy {

    private const val BUF = 64 * 1024
    private const val SCAN_WINDOW = 64 * 1024
    private const val PROBE_BYTES = 8192
    private const val MAX_REDIRECTS = 5
    private const val PKT = 188
    private const val SYNC = 0x47.toByte()
    private const val NEED_PACKETS = 8

    private val ALLOWED =
        listOf(
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
            ".marsexplorationteam.space",
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
    private val workers =
        java.util.concurrent.ThreadPoolExecutor(
            0,
            16,
            30L,
            java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue<Runnable>(),
            java.util.concurrent.ThreadFactory { r ->
                Thread(r, "multisource-proxy-media").apply { isDaemon = true }
            },
        )

    @Volatile private var socket: ServerSocket? = null

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
        ss =
            try {
                ServerSocket(0, 64, lo)
            } catch (e: java.io.IOException) {
                lastEx = e
                null
            }
        if (ss == null) {
            lastError = "bind: ${lastEx?.javaClass?.simpleName}: ${lastEx?.message}"
            obs("START-FAIL", lastError!!)
            port = -1
            return false
        }
        socket = ss
        port = ss.localPort
        startedAtMs = System.currentTimeMillis()
        served.set(0)
        swallowed.set(0)
        proxied.set(0)
        denied.set(0)
        playlists.set(0)
        stripped.set(0)
        noSync.set(0)
        bytesOut.set(0)
        offsetCache.clear()
        lastError = null

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
        } catch (_: Throwable) {}
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
     *
     * #EXT-X-I-FRAME-STREAM-INF dibuang dan URI varian ditulis absolut menunjuk langsung ke CDN
     * (TIDAK di-proxy). Dipakai FileLions. Default false, sehingga pemanggil lama - termasuk
     * TurboVIP - berperilaku persis seperti sebelumnya.
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
        val host =
            try {
                URL(raw).let {
                    if (
                        it.protocol != "https" ||
                            it.userInfo != null ||
                            (it.port != -1 && it.port != 443)
                    )
                        return false
                    it.host.lowercase()
                }
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
     * Cari offset paket TS pertama: 0x47 yang berulang tiap 188 byte sebanyak NEED_PACKETS kali.
     * Identik dengan algoritma yang divalidasi di tv5/tv6. Return -1 kalau tidak ditemukan.
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
                        ok = false
                        break
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
            val c =
                try {
                    ss.accept()
                } catch (_: Throwable) {
                    if (ss.isClosed) return
                    swallowed.incrementAndGet()
                    continue
                }
            activeSockets.add(c)
            try {
                workers.execute { handle(c) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                activeSockets.remove(c)
                c.close()
            }
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
                    if (i > 0)
                        h[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
            } else if (b != '\r'.code) sb.append(b.toChar())
        }
        val rl = reqLine ?: return null
        val p = rl.split(" ").getOrNull(1) ?: return null
        return Req(p, h, rl.substringBefore(' '))
    }

    private class Up(val conn: HttpURLConnection?, val finalUrl: String, val hops: Int)

    /** Buka upstream, ikuti redirect MANUAL dengan cek allowlist tiap hop. */
    private fun openUpstream(start: String, range: String?): Up {
        var url = start
        var hops = 0
        while (true) {
            val c =
                try {
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
            val code =
                try {
                    c.responseCode
                } catch (_: Throwable) {
                    c.disconnect()
                    return Up(null, url, hops)
                }
            if (code in 301..308 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank() || ++hops > MAX_REDIRECTS) return Up(null, url, hops)
                val next =
                    try {
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
        offsetCache[url]?.let {
            return it
        }
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
            if (off >= 0) {
                if (offsetCache.size >= 256) offsetCache.clear()
                offsetCache[url] = off
            }
            off
        } catch (_: Throwable) {
            -1
        } finally {
            try {
                c.disconnect()
            } catch (_: Throwable) {}
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
            val req =
                readRequest(client.getInputStream())
                    ?: run {
                        swallowed.incrementAndGet()
                        return
                    }
            served.incrementAndGet()
            val out = client.getOutputStream()

            val q = req.path.indexOf('?')
            val route = if (q >= 0) req.path.substring(0, q) else req.path
            val query = if (q >= 0) req.path.substring(q + 1) else ""

            if (route == "/ping" || route == "/") {
                val body =
                    buildString {
                            append("ok\nport=$port\nserved=${served.get()}\n")
                            append("uptime_sec=$uptimeSec\nstarted_at_ms=$startedAtMs\n")
                            append("playlists=${playlists.get()}\nstripped=${stripped.get()}\n")
                            append("no_sync=${noSync.get()}\ndenied=${denied.get()}\n")
                            append("swallowed=${swallowed.get()}\nbytes_out=${bytesOut.get()}\n")
                            append("offsets_ditemukan=$lastOffsets\n")
                        }
                        .toByteArray()
                writeHead(out, 200, "text/plain; charset=utf-8", body.size.toLong(), null)
                out.write(body)
                out.flush()
                return
            }
            if (route == "/hx") {
                sent =
                    HydraxMedia.serve(
                        param(query, "id") ?: "",
                        req.headers["range"],
                        req.method == "HEAD",
                        out,
                    )
                proxied.incrementAndGet()
                bytesOut.addAndGet(sent)
                return
            }
            if (route != "/p") {
                val b = "not found\n".toByteArray()
                writeHead(out, 404, "text/plain", b.size.toLong(), null)
                out.write(b)
                out.flush()
                return
            }

            upUrl =
                param(query, "u")
                    ?: run {
                        val b = "missing u\n".toByteArray()
                        writeHead(out, 400, "text/plain", b.size.toLong(), null)
                        out.write(b)
                        out.flush()
                        return
                    }
            if (!isAllowed(upUrl)) {
                denied.incrementAndGet()
                val b = "host not allowed\n".toByteArray()
                writeHead(out, 403, "text/plain", b.size.toLong(), null)
                out.write(b)
                out.flush()
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
                val c =
                    up.conn
                        ?: run {
                            val b = "upstream refused\n".toByteArray()
                            writeHead(out, 502, "text/plain", b.size.toLong(), null)
                            out.write(b)
                            out.flush()
                            return
                        }
                upstreamConnection = c
                status = c.responseCode
                lenAsli = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val cr = c.getHeaderField("Content-Range")
                writeHead(
                    out,
                    status,
                    c.contentType ?: "video/mp2t",
                    lenAsli,
                    rewriteContentRange(cr, offset),
                )
                sent = pump(safeStream(c), out)
                out.flush()
                proxied.incrementAndGet()
                bytesOut.addAndGet(sent)
                c.disconnect()
                return
            }

            // ---------- ambil upstream ----------
            val up = openUpstream(upUrl, null)
            val c =
                up.conn
                    ?: run {
                        val b = "upstream refused\n".toByteArray()
                        writeHead(out, 502, "text/plain", b.size.toLong(), null)
                        out.write(b)
                        out.flush()
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
                val body =
                    if (cleanMode) {
                        cleanMasterPlaylist(teks, up.finalUrl).toByteArray(Charsets.UTF_8)
                    } else {
                        rewritePlaylist(teks, up.finalUrl).toByteArray(Charsets.UTF_8)
                    }
                // Content-Length WAJIB dihitung ulang: body berubah.
                writeHead(out, status, "application/vnd.apple.mpegurl", body.size.toLong(), null)
                out.write(body)
                out.flush()
                sent = body.size.toLong()
                playlists.incrementAndGet()
                proxied.incrementAndGet()
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
            proxied.incrementAndGet()
            bytesOut.addAndGet(sent)
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
            } catch (_: Throwable) {}
            if (upUrl != "-")
                obs(
                    "PROXY",
                    "kind=$kind url=${shortUrl(upUrl)} status=$status offset=$offset " +
                        "len_upstream=$lenAsli len_kirim=$sent ms=${System.currentTimeMillis() - t0}",
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
     * SEBAB YANG DIPERBAIKI (terukur di FL-5-MASTER): normal=3[852x480, 1280x720, 1920x1080]
     * iframe=3[852x480, 1280x720, 1920x1080] total_entri=6 Varian #EXT-X-I-FRAME-STREAM-INF membawa
     * atribut RESOLUTION yang sama dengan varian normal, sehingga ExoPlayer mengeksposnya sebagai
     * track video terpilih. Padahal isinya hanya keyframe untuk preview scrub - tidak bisa diputar
     * sebagai video normal. Itulah entri kedua yang gagal.
     *
     * Dua hal yang dilakukan:
     * 1. buang seluruh baris #EXT-X-I-FRAME-STREAM-INF
     * 2. tulis URI varian menjadi ABSOLUT ke CDN, TIDAK di-proxy
     *
     * Poin 2 disengaja: hanya master yang melewati proxy. Playlist varian dan segmen tetap mengalir
     * langsung ke CDN, jadi tidak ada tambahan latensi maupun titik gagal baru pada lalu lintas
     * media.
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

    private fun resolve(base: String, rel: String): String =
        try {
            URL(URL(base), rel).toString()
        } catch (_: Throwable) {
            rel
        }

    // ----------------------------------------------------------------- range

    /** bytes=A-B klien -> bytes=(A+off)-(B+off) upstream */
    private fun translateRange(clientRange: String, off: Int): String {
        if (off <= 0) return clientRange
        val m = Regex("""bytes=(\d*)-(\d*)""").find(clientRange) ?: return clientRange
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        return when {
            a != null && b != null -> "bytes=${a + off}-${b + off}"
            a != null -> "bytes=${a + off}-"
            else -> clientRange // suffix range: biarkan apa adanya
        }
    }

    /** bytes X-Y/Z upstream -> bytes (X-off)-(Y-off)/(Z-off) untuk klien */
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

    private fun safeStream(c: HttpURLConnection): InputStream =
        try {
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
        out: OutputStream,
        code: Int,
        ctype: String,
        len: Long,
        contentRange: String?,
    ) {
        val reason =
            when (code) {
                200 -> "OK"
                206 -> "Partial Content"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                502 -> "Bad Gateway"
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
            if (i > 0 && pair.substring(0, i) == key)
                return try {
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                } catch (_: Throwable) {
                    null
                }
        }
        return null
    }

    private fun hostOf(u: String) =
        try {
            URL(u).host ?: "?"
        } catch (_: Throwable) {
            "?"
        }

    private fun shortUrl(u: String) = "${hostOf(u)}..${u.takeLast(24)}"

    fun obs(event: String, message: String) = Unit
}
