package com.OppaDrama

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
object LocalProxy {

    private const val PREFERRED_PORT = 47821
    private const val PORT_FALLBACK_TRIES = 12
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
        for (i in 0 until PORT_FALLBACK_TRIES) {
            ss = try {
                ServerSocket(PREFERRED_PORT + i, 64, lo)
            } catch (e: Throwable) {
                lastEx = e; null
            }
            if (ss != null) break
        }
        if (ss == null) ss = try {
            ServerSocket(0, 64, lo)
        } catch (e: Throwable) {
            lastEx = e; null
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
        served.set(0); swallowed.set(0); proxied.set(0); denied.set(0)
        playlists.set(0); stripped.set(0); noSync.set(0); bytesOut.set(0)
        offsetCache.clear(); lastError = null

        Thread({ acceptLoop(ss) }, "OppaDrama-proxy").apply {
            isDaemon = true
            start()
        }
        obs("START", "port=$port preferred=$PREFERRED_PORT")
        return true
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
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
        if (!isRunning || port <= 0) return null
        val suffix = if (clean) "&clean=1" else ""
        return "http://127.0.0.1:$port/p?u=" + URLEncoder.encode(upstream, "UTF-8") + suffix
    }

    fun isAllowed(raw: String): Boolean {
        val host = try {
            URL(raw).host?.lowercase() ?: return false
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
            Thread({ handle(c) }, "OppaDrama-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private class Req(val path: String, val headers: Map<String, String>)

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
        return Req(p, h)
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
            if (off >= 0) offsetCache[url] = off
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

    fun obs(event: String, detail: String) {
        println("[OppaDrama/OBS] $event $detail")
    }
}
