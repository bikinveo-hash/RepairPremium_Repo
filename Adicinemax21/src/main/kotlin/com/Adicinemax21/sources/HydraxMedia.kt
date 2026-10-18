package com.Adicinemax21.sources

import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
