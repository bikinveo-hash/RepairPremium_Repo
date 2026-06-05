package com.LayarKacaProvider

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

val mapper: ObjectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// =========================================================================
// DATA CLASSES
// =========================================================================
data class HydraxData(
    @JsonProperty("slug")    val slug:    String? = null,
    @JsonProperty("md5_id")  val md5_id:  String? = null,
    @JsonProperty("user_id") val user_id: String? = null,
    @JsonProperty("media")   val media:   String? = null
)

data class HydraxMedia(
    @JsonProperty("mp4") val mp4: HydraxMp4? = null
)

data class HydraxMp4(
    @JsonProperty("sources") val sources: List<HydraxSource>? = null
)

data class HydraxSource(
    @JsonProperty("label")    val label:    String? = null,
    @JsonProperty("codec")    val codec:    String? = null,
    @JsonProperty("path")     val path:     String? = null,
    @JsonProperty("url")      val url:      String? = null,
    @JsonProperty("size")     val size:     Long?   = null,
    @JsonProperty("partSize") val partSize: Long?   = null
)

data class HowNetworkResponse(
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("file")   val file:   String?,
    @JsonProperty("type")   val type:   String?,
    @JsonProperty("title")  val title:  String?
)

data class CastChalResp(
    @JsonProperty("nonce")        val nonce:        String?,
    @JsonProperty("challenge_id") val challenge_id: String?
)
data class CastAttestResp(
    @JsonProperty("token") val token: String?
)
data class CastPbResp(
    @JsonProperty("playback") val playback: CastPlaybackInfo?
)
data class CastPlaybackInfo(
    @JsonProperty("iv")        val iv:        String?,
    @JsonProperty("payload")   val payload:   String?,
    @JsonProperty("key_parts") val key_parts: List<String>?
)
data class CastDecrypted(
    @JsonProperty("sources") val sources: List<CastSource>?
)
data class CastSource(
    @JsonProperty("url")   val url:   String?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("type")  val type:  String?
)

// ==============================================================================
// HYDRAX PROXY - FIXED v3.0
// Bug Fix: ExoPlayer Error 2004 saat seek ke menit terakhir video
//
// ROOT CAUSE yang diperbaiki:
//   1. HTTP 416 dari CDN di-forward mentah ke ExoPlayer -> Error 2004
//   2. Range request tidak di-clamp ke batas file_size yang diketahui
//   3. Content-Length tidak di-cache antar request, ExoPlayer tidak tahu EOF
//   4. HTTP 200 dari CDN tidak dikonversi ke 206 saat range diminta
// ==============================================================================
object HydraxProxy {
    var port: Int = 0
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    // Cache ukuran file per URL agar bisa clamp range request
    // Key = URL CDN (original), Value = ukuran file dalam bytes
    private val fileSizeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            isRunning = true
            thread(isDaemon = true) {
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        thread(isDaemon = true) { handleClient(client) }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleClient(client: Socket) {
        var response: Response? = null
        try {
            client.soTimeout = 30_000

            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()

            // ── Parse request line ────────────────────────────────────────────
            val requestLine = reader.readLine() ?: return
            val reqParts    = requestLine.split(" ")
            if (reqParts.size < 2) return

            val method  = reqParts[0]
            val reqPath = reqParts[1]
            if (!reqPath.contains("?url=")) return

            val params = reqPath.substringAfter("?")
                .split("&")
                .associate {
                    val kv = it.split("=", limit = 2)
                    kv[0] to (if (kv.size > 1) kv[1] else "")
                }

            val encodedUrl = params["url"] ?: return
            val keyHex     = params["key"] ?: return
            val realUrl    = String(Base64.decode(encodedUrl, Base64.URL_SAFE))

            // ── Baca semua header dari ExoPlayer ─────────────────────────────
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            // ── Parse range yang diminta ExoPlayer ───────────────────────────
            val (reqStart, reqEnd) = parseRange(rangeHeader)

            // ── [FIX A] Clamp range terhadap file_size yang sudah diketahui ──
            // Jika kita sudah tahu ukuran file dari request sebelumnya,
            // kita bisa validasi range sebelum diteruskan ke CDN.
            val cachedFileSize = fileSizeCache[realUrl]
            if (cachedFileSize != null && reqStart >= cachedFileSize) {
                // ExoPlayer minta range di luar file — kirim 416 RFC-7233 compliant
                // ExoPlayer akan baca Content-Range: bytes */SIZE dan berhenti retry
                sendProper416(output, cachedFileSize)
                return
            }

            // ── [FIX B] Clamp reqEnd agar tidak melampaui batas file ─────────
            val clampedRangeHeader = buildClampedRange(rangeHeader, reqStart, reqEnd, cachedFileSize)

            // ── Kirim request ke CDN ──────────────────────────────────────────
            val cdnRequest = Request.Builder()
                .url(realUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer",    "https://abyssplayer.com/")
                .header("Origin",     "https://abyssplayer.com")
                .header("Accept-Encoding", "identity")
                .apply { if (clampedRangeHeader != null) header("Range", clampedRangeHeader) }
                .build()

            response = try {
                app.baseClient.newCall(cdnRequest).execute()
            } catch (e: Exception) {
                output.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val code         = response.code
            val contentRange = response.header("Content-Range")
            val contentLen   = response.header("Content-Length")?.toLongOrNull()

            // ── [FIX C] Cache ukuran file dari response CDN ──────────────────
            val totalFileSize: Long? = when {
                // Content-Range: bytes X-Y/Z -> ambil Z
                contentRange != null -> {
                    contentRange.substringAfter("/", "").toLongOrNull()
                }
                // Content-Length tersedia di response 200
                code == 200 && contentLen != null -> contentLen
                else -> cachedFileSize
            }
            if (totalFileSize != null && totalFileSize > 0) {
                fileSizeCache[realUrl] = totalFileSize
            }

            // ── [FIX D] Tangani HTTP 416 dari CDN ────────────────────────────
            // Daripada forward 416 mentah (yang crash ExoPlayer),
            // kirim 416 RFC-7233 yang benar dengan Content-Range: bytes */<size>
            if (code == 416) {
                val knownSize = totalFileSize ?: cachedFileSize
                sendProper416(output, knownSize)
                return
            }

            // ── [FIX E] Tangani kode error lain (403, 404, 5xx) ──────────────
            // Konversi ke 503 agar ExoPlayer retry bukan crash permanen
            if (code == 403 || code == 404) {
                output.write(
                    "HTTP/1.1 503 Service Unavailable\r\n" +
                    "Retry-After: 3\r\n" +
                    "Connection: close\r\n\r\n"
                    .toByteArray()
                )
                output.flush()
                return
            }

            // ── [FIX F] Konversi HTTP 200 -> 206 jika range diminta ──────────
            // CDN kadang merespons 200 padahal kita minta Range.
            // ExoPlayer mengharapkan 206 + Content-Range agar tahu posisi data.
            val (statusCode, statusMsg, extraHeaders) = when {
                code == 200 && rangeHeader != null && totalFileSize != null -> {
                    // CDN kirim full file padahal range diminta
                    // Kita "pura-pura" jadi 206 dengan Content-Range yang benar
                    val actualStart = 0L
                    val actualEnd   = totalFileSize - 1
                    Triple(
                        206, "Partial Content",
                        listOf("Content-Range" to "bytes $actualStart-$actualEnd/$totalFileSize")
                    )
                }
                else -> Triple(code, if (response.message.isEmpty()) "OK" else response.message, emptyList())
            }

            // ── Hitung actualOffset untuk sinkronisasi AES-CTR ───────────────
            val actualOffset: Long = when {
                statusCode == 206 && contentRange != null -> {
                    contentRange.substringAfter("bytes ", "").substringBefore("-").toLongOrNull() ?: reqStart
                }
                statusCode == 206 && rangeHeader != null && code == 200 -> {
                    // Kita konversi 200 -> 206, data CDN mulai dari byte 0
                    0L
                }
                code == 200 -> 0L
                else -> reqStart
            }

            // ── Tulis header response ke ExoPlayer ───────────────────────────
            output.write("HTTP/1.1 $statusCode $statusMsg\r\n".toByteArray())

            // Forward header dari CDN (dengan filter)
            for ((hKey, hVal) in response.headers) {
                if (hKey.equals("transfer-encoding", ignoreCase = true)) continue
                if (hKey.equals("content-encoding",  ignoreCase = true)) continue
                if (hKey.equals("connection",         ignoreCase = true)) continue
                // Jika kita konversi 200->206, skip Content-Range asli CDN (akan kita ganti)
                if (statusCode == 206 && code == 200 && hKey.equals("content-range", ignoreCase = true)) continue
                output.write("$hKey: $hVal\r\n".toByteArray())
            }

            // Tambah header ekstra (misal Content-Range untuk konversi 200->206)
            for ((hKey, hVal) in extraHeaders) {
                output.write("$hKey: $hVal\r\n".toByteArray())
            }

            output.write("Connection: close\r\n\r\n".toByteArray())
            output.flush()

            // ── Jika HEAD request atau response error, berhenti di sini ──────
            if (!response.isSuccessful || method.equals("HEAD", ignoreCase = true)) {
                return
            }

            // ── Setup AES-CTR cipher ──────────────────────────────────────────
            val keyBytes  = keyHex.toByteArray(Charsets.UTF_8)
            val ivBytes   = keyBytes.copyOfRange(0, 16)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher    = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ivBytes))

            // Advance cipher state ke posisi actualOffset
            // (hanya perlu jika kita mulai di tengah zona terenkripsi 0..64KB)
            if (actualOffset in 1L until 65536L) {
                var remaining = actualOffset
                val skipBuf   = ByteArray(8192)
                while (remaining > 0) {
                    val n = minOf(remaining, skipBuf.size.toLong()).toInt()
                    cipher.update(skipBuf, 0, n)
                    remaining -= n
                }
            }

            // ── Stream data: dekripsi 64KB pertama, passthrough sisanya ──────
            val inputStream = response.body?.byteStream() ?: return
            val buffer      = ByteArray(32768)
            var bytesRead:  Int
            var streamPos   = actualOffset

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (streamPos < 65536L) {
                    val encryptedBytes = minOf(bytesRead.toLong(), 65536L - streamPos).toInt()
                    val decrypted      = cipher.update(buffer, 0, encryptedBytes)
                    if (decrypted != null) output.write(decrypted)
                    // Sisa buffer yang sudah di luar zona enkripsi
                    if (encryptedBytes < bytesRead) {
                        output.write(buffer, encryptedBytes, bytesRead - encryptedBytes)
                    }
                } else {
                    // Di luar 64KB pertama: raw passthrough tanpa dekripsi
                    output.write(buffer, 0, bytesRead)
                }
                streamPos += bytesRead
                output.flush()
            }

        } catch (_: SocketException) {
            // Normal: user seek/close player, koneksi terputus
        } catch (_: Exception) {
            // Abaikan agar thread tidak crash
        } finally {
            try { response?.close() } catch (_: Exception) {}
            try { client.close()    } catch (_: Exception) {}
        }
    }

    // ── Helper: Parse Range header ─────────────────────────────────────────
    // Mengembalikan Pair(start, end) dimana end=-1 berarti open-ended
    private fun parseRange(rangeHeader: String?): Pair<Long, Long> {
        if (rangeHeader == null) return Pair(0L, -1L)
        val rangeVal = rangeHeader.removePrefix("bytes=")
        val parts    = rangeVal.split("-")
        val start    = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end      = parts.getOrNull(1)?.toLongOrNull() ?: -1L
        return Pair(start, end)
    }

    // ── Helper: Clamp range agar tidak melampaui file_size ────────────────
    private fun buildClampedRange(
        original:  String?,
        reqStart:  Long,
        reqEnd:    Long,
        fileSize:  Long?
    ): String? {
        if (original == null) return null
        if (fileSize == null || fileSize <= 0) return original

        val clampedStart = minOf(reqStart, fileSize - 1)
        val clampedEnd   = when {
            reqEnd < 0           -> ""            // open-ended, biarkan CDN tentukan
            reqEnd >= fileSize   -> (fileSize - 1).toString()
            else                 -> reqEnd.toString()
        }
        return if (clampedEnd.isEmpty()) "bytes=$clampedStart-"
               else "bytes=$clampedStart-$clampedEnd"
    }

    // ── Helper: Kirim 416 RFC-7233 compliant ──────────────────────────────
    // RFC 7233 §4.4: jika server tahu total size, sertakan Content-Range: bytes */<size>
    // ExoPlayer akan baca ini dan tahu batas file => berhenti minta range yg tidak valid
    private fun sendProper416(output: java.io.OutputStream, fileSize: Long?) {
        val crHeader = if (fileSize != null && fileSize > 0) {
            "Content-Range: bytes */$fileSize\r\n"
        } else {
            ""
        }
        output.write(
            ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
            crHeader +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n")
            .toByteArray()
        )
        output.flush()
    }
}

// =========================================================================
// EXTRACTOR 1: ABYSS / HYDRAX
// =========================================================================
open class AbyssExtractor : ExtractorApi() {
    override val name          = "Abyss"
    override val mainUrl       = "https://abyssplayer.com"
    override val requiresReferer = true

    private fun baseHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
        "Referer"    to referer,
        "Origin"     to mainUrl,
        "Accept"     to "*/*"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageRef = referer ?: "$mainUrl/"
        val slug    = extractSlugFromUrl(url) ?: return
        val hdrs    = baseHeaders(pageRef)

        try {
            val html  = app.get("$mainUrl/?v=$slug", headers = hdrs).text
            val datas = Regex("""datas\s*=\s*"([^"]+)"""").find(html)
                ?.groupValues?.get(1) ?: return

            val decodedDatas = String(
                Base64.decode(datas, Base64.DEFAULT),
                Charsets.ISO_8859_1
            )
            val dataJson = mapper.readValue(decodedDatas, HydraxData::class.java)

            val infoSlug = dataJson.slug  ?: slug
            val md5Id    = dataJson.md5_id  ?: return
            val userId   = dataJson.user_id ?: return
            val mediaStr = dataJson.media   ?: return

            // Derive media decrypt key: MD5("userId:slug:md5Id")
            val keyHex = MessageDigest.getInstance("MD5")
                .digest("$userId:$infoSlug:$md5Id".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
            val cipher   = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(keyBytes.copyOfRange(0, 16))
            )

            val decryptedBytes = cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1))
            val mediaJson = mapper.readValue(
                String(decryptedBytes, Charsets.UTF_8),
                HydraxMedia::class.java
            )

            mediaJson.mp4?.sources?.forEach { src ->
                val label   = src.label   ?: "Unknown"
                val path    = src.path    ?: return@forEach
                val baseUrl = src.url     ?: return@forEach
                if (path.isEmpty() || baseUrl.isEmpty()) return@forEach

                val srcUrl   = "$baseUrl/$path"
                val filename = path.substringAfterLast("/")

                // fn_key = MD5(filename), dipakai proxy untuk dekripsi 64KB pertama
                val fnKeyHex = MessageDigest.getInstance("MD5")
                    .digest(filename.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }

                HydraxProxy.start()

                val encodedUrl = Base64.encodeToString(
                    srcUrl.toByteArray(),
                    Base64.URL_SAFE or Base64.NO_WRAP
                )
                val localProxyUrl =
                    "http://127.0.0.1:${HydraxProxy.port}/?url=$encodedUrl&key=$fnKeyHex"

                println("LINK TESTER: $localProxyUrl")

                callback(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = localProxyUrl,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageRef
                        this.quality = labelToQuality(label)
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractSlugFromUrl(url: String): String? {
        val vParam = url.substringAfter("?v=", "").substringBefore("&")
        if (vParam.isNotEmpty() && url.contains("?v=")) return vParam
        Regex("""(?:e|embed|v|play)/([a-zA-Z0-9_\-]{6,20})""")
            .find(url)?.groupValues?.get(1)?.let { return it }
        return url.split("?").first().trimEnd('/').split('/').lastOrNull()
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9_\\-]{6,20}")) }
    }

    private fun labelToQuality(label: String): Int = when {
        label.contains("2160") || label.contains("4k", ignoreCase = true) -> Qualities.P2160.value
        label.contains("1440") -> Qualities.P1440.value
        label.contains("1080") -> Qualities.P1080.value
        label.contains("720")  -> Qualities.P720.value
        label.contains("480")  -> Qualities.P480.value
        else                   -> Qualities.Unknown.value
    }
}

// =========================================================================
// EXTRACTOR 2: TURBO VIP
// =========================================================================
open class Lk21TurboExtractor : ExtractorApi() {
    override var name          = "LK21 TurboVIP"
    override var mainUrl       = "https://turbovidhls.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty()) return null

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer"    to "https://playeriframe.sbs/"
            )

            val html    = app.get("$mainUrl/t/$id", headers = headers).text
            var m3u8Url = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8Url.isNullOrBlank())
                m3u8Url = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            if (m3u8Url.isNullOrBlank()) return null

            val type = if (m3u8Url.endsWith(".mp4", ignoreCase = true))
                ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8

            sources.add(
                newExtractorLink(
                    source = "LK21 TurboVIP",
                    name   = "TurboVIP HD",
                    url    = m3u8Url,
                    type   = type
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                }
            )
        } catch (e: Exception) { e.printStackTrace() }
        return sources
    }
}

// =========================================================================
// EXTRACTOR 3: HOW NETWORK
// =========================================================================
open class HowNetworkExtractor : ExtractorApi() {
    override var name          = "LK21 HowNetwork"
    override var mainUrl       = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("id=").substringBefore("&")
            if (id.isEmpty()) return null

            val response = app.post(
                url = "$mainUrl/api2.php?id=$id",
                headers = mapOf(
                    "Origin"     to mainUrl,
                    "Referer"    to url,
                    "Accept"     to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                ),
                data = mapOf(
                    "r" to "https://playeriframe.sbs/",
                    "d" to "cloud.hownetwork.xyz"
                )
            ).text

            val parsedRes = try {
                mapper.readValue(response, HowNetworkResponse::class.java)
            } catch (_: Exception) { null }

            val m3u8Url = parsedRes?.file
            if (!m3u8Url.isNullOrBlank()) {
                sources.add(
                    newExtractorLink(
                        source = "LK21 HowNetwork",
                        name   = "HowNetwork HD",
                        url    = m3u8Url,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                    }
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return sources
    }
}

// =========================================================================
// EXTRACTOR 4: CAST HD
// =========================================================================
open class CastExtractor : ExtractorApi() {
    override var name          = "CAST HD"
    override var mainUrl       = "https://weneverbeenfree.com"
    override val requiresReferer = false

    private fun b64url(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun b64urlDecode(s: String): ByteArray {
        var std = s.replace("-", "+").replace("_", "/")
        val pad = 4 - (std.length % 4)
        if (pad != 4) std += "=".repeat(pad)
        return Base64.decode(std, Base64.DEFAULT)
    }

    private fun getRandomBytes(size: Int): ByteArray =
        ByteArray(size).apply { java.security.SecureRandom().nextBytes(this) }

    private fun derToRaw(der: ByteArray): ByteArray {
        var offset  = 2
        val rLength = der[offset + 1].toInt()
        val rOffset = offset + 2
        offset += 2 + rLength
        val sLength = der[offset + 1].toInt()
        val sOffset = offset + 2

        val rStr = der.copyOfRange(rOffset, rOffset + rLength)
            .dropWhile { it == 0.toByte() }.toByteArray()
        val sStr = der.copyOfRange(sOffset, sOffset + sLength)
            .dropWhile { it == 0.toByte() }.toByteArray()

        val rPadded = ByteArray(32); val sPadded = ByteArray(32)
        System.arraycopy(rStr, 0, rPadded, 32 - rStr.size, rStr.size)
        System.arraycopy(sStr, 0, sPadded, 32 - sStr.size, sStr.size)
        return rPadded + sPadded
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources     = mutableListOf<ExtractorLink>()
        val videoId     = url.substringAfterLast("/")
        val commonHdrs  = mapOf(
            "User-Agent"      to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Origin"          to mainUrl,
            "Referer"         to url,
            "X-Embed-Origin"  to "playeriframe.sbs",
            "X-Embed-Parent"  to url,
            "X-Embed-Referer" to "https://playeriframe.sbs/"
        )

        try {
            val chalRes  = app.post("$mainUrl/api/videos/access/challenge", headers = commonHdrs)
            val chalJson = try { mapper.readValue(chalRes.text, CastChalResp::class.java) }
                          catch (_: Exception) { null } ?: return null
            val nonce    = chalJson.nonce          ?: return null
            val cid      = chalJson.challenge_id   ?: return null

            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp  = kpg.generateKeyPair()

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(kp.private)
            sig.update(nonce.toByteArray(Charsets.UTF_8))
            val rawSig = derToRaw(sig.sign())

            val pub = kp.public as ECPublicKey
            var xBytes = pub.w.affineX.toByteArray()
            var yBytes = pub.w.affineY.toByteArray()
            if (xBytes.size > 32) xBytes = xBytes.copyOfRange(xBytes.size - 32, xBytes.size)
            if (yBytes.size > 32) yBytes = yBytes.copyOfRange(yBytes.size - 32, yBytes.size)
            if (xBytes.size < 32) xBytes = ByteArray(32 - xBytes.size) { 0 } + xBytes
            if (yBytes.size < 32) yBytes = ByteArray(32 - yBytes.size) { 0 } + yBytes

            val attestPayload = mapOf(
                "viewer_id"  to b64url(getRandomBytes(16)),
                "device_id"  to b64url(getRandomBytes(16)),
                "challenge_id" to cid,
                "nonce"      to nonce,
                "signature"  to b64url(rawSig),
                "public_key" to mapOf(
                    "crv" to "P-256", "ext" to true,
                    "key_ops" to listOf("verify"), "kty" to "EC",
                    "x" to b64url(xBytes), "y" to b64url(yBytes)
                ),
                "client"     to mapOf("user_agent" to commonHdrs["User-Agent"]!!),
                "attributes" to mapOf("entropy" to "high")
            )

            val attestRes  = app.post("$mainUrl/api/videos/access/attest",
                headers = commonHdrs, json = attestPayload)
            val attestJson = try { mapper.readValue(attestRes.text, CastAttestResp::class.java) }
                            catch (_: Exception) { null } ?: return null
            val token      = attestJson.token ?: return null

            val pbRes  = app.post(
                "$mainUrl/api/videos/$videoId/embed/playback",
                headers = commonHdrs,
                json    = mapOf("fingerprint" to mapOf("token" to token))
            )
            val pbResp = try { mapper.readValue(pbRes.text, CastPbResp::class.java)?.playback }
                        catch (_: Exception) { null } ?: return null

            val iv      = b64urlDecode(pbResp.iv      ?: return null)
            val payload = b64urlDecode(pbResp.payload ?: return null)
            val kParts  = pbResp.key_parts            ?: return null

            val keysToTest = mutableListOf<ByteArray>()
            val chunks16   = mutableListOf<ByteArray>()
            for (p in kParts) {
                if (p.length == 32) keysToTest.add(p.toByteArray(Charsets.UTF_8))
                try {
                    val dec = b64urlDecode(p)
                    if (dec.size == 32) keysToTest.add(dec)
                    else if (dec.size == 16) chunks16.add(dec)
                } catch (_: Exception) {}
            }
            for (i in chunks16.indices)
                for (j in chunks16.indices)
                    if (i != j) keysToTest.add(chunks16[i] + chunks16[j])

            var realUrl:   String? = null
            var qualLabel         = "HD"
            for (kb in keysToTest) {
                try {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kb, "AES"), GCMParameterSpec(128, iv))
                    val dec  = c.doFinal(payload)
                    val data = mapper.readValue(String(dec, Charsets.UTF_8), CastDecrypted::class.java)
                    realUrl  = data?.sources?.firstOrNull()?.url
                    qualLabel = data?.sources?.firstOrNull()?.label ?: "HD"
                    if (realUrl != null) break
                } catch (_: Exception) {}
            }

            if (realUrl != null) {
                sources.add(
                    newExtractorLink(
                        source = "CAST HD",
                        name   = "CAST $qualLabel",
                        url    = realUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin"     to mainUrl,
                            "Referer"    to "$mainUrl/",
                            "User-Agent" to commonHdrs["User-Agent"]!!
                        )
                    }
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return sources
    }
}
