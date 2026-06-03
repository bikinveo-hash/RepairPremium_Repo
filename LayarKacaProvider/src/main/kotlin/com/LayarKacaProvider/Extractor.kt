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
import java.net.URLDecoder
import java.net.URLEncoder
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

// FIX #12: Rename agar tidak konflik dengan `mapper` di MainAPI.kt
val jsonMapper: ObjectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// =========================================================================
// DATA CLASSES UNTUK TYPE-SAFE JSON PARSING
// =========================================================================
data class HydraxData(
    @JsonProperty("slug")    val slug: String?    = null,
    @JsonProperty("md5_id")  val md5_id: String?  = null,
    @JsonProperty("user_id") val user_id: String? = null,
    @JsonProperty("media")   val media: String?   = null
)

data class HydraxMedia(
    @JsonProperty("mp4") val mp4: HydraxMp4? = null
)

data class HydraxMp4(
    @JsonProperty("sources") val sources: List<HydraxSource>? = null
)

data class HydraxSource(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("codec") val codec: String? = null,
    @JsonProperty("path")  val path: String?  = null,
    @JsonProperty("url")   val url: String?   = null
)

data class HowNetworkResponse(
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("file")   val file: String?,
    @JsonProperty("type")   val type: String?,
    @JsonProperty("title")  val title: String?
)

data class CastChalResp(
    @JsonProperty("nonce")        val nonce: String?,
    @JsonProperty("challenge_id") val challenge_id: String?
)
data class CastAttestResp(
    @JsonProperty("token") val token: String?
)
data class CastPbResp(
    @JsonProperty("playback") val playback: CastPlaybackInfo?
)
data class CastPlaybackInfo(
    @JsonProperty("iv")        val iv: String?,
    @JsonProperty("payload")   val payload: String?,
    @JsonProperty("key_parts") val key_parts: List<String>?
)
data class CastDecrypted(
    @JsonProperty("sources") val sources: List<CastSource>?
)
data class CastSource(
    @JsonProperty("url")   val url: String?,
    @JsonProperty("label") val label: String?,
    @JsonProperty("type")  val type: String?
)

// =========================================================================
// MESIN SERVER PROXY LOKAL (ANTI-CRONET & ANTI-EOF)
//
// FIX SEEK:
//   Masalah utama: AES/CTR adalah stream cipher — byte ke-N hanya bisa
//   didapat setelah memproses byte 0..(N-1). Seekjauh langsung ke menit
//   terakhir berarti `reqStart` bisa jutaan byte.
//
//   Kode lama punya dua bug:
//   1) Hanya skip cipher untuk reqStart < 65536 — seek di atas itu tidak
//      di-handle sama sekali, video corrupt.
//   2) cipher.update(ByteArray(reqStart.toInt())) untuk skip —
//      ini allocate array sebesar reqStart byte di memory. Seek ke menit
//      terakhir film 2 jam = ~500MB array → OOM crash.
//
//   FIX: Gunakan counter block number (CBN) dari AES/CTR.
//   AES/CTR bekerja per-block 16 byte. Untuk seek ke offset N:
//   - Block yang dibutuhkan = N / 16
//   - Byte offset dalam block itu = N % 16
//   Kita bisa langsung set counter ke block tersebut tanpa memproses
//   semua byte sebelumnya. Ini O(1) — tidak peduli seberapa jauh seek.
// =========================================================================
object HydraxProxy {
    var port: Int = 0
    @Volatile private var isRunning = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            isRunning = true
            thread(isDaemon = true, name = "HydraxProxy") {
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        thread(isDaemon = true) { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning) e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // FIX #1: Tambah stop() agar tidak leak thread saat plugin di-reload
    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        port = 0
    }

    private fun handleClient(client: Socket) {
        var response: Response? = null
        try {
            client.soTimeout = 15000

            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            if (!path.contains("?url=")) return

            val query = path.substringAfter("?")

            // FIX #15: split dengan limit=2 agar nilai Base64 yang mengandung '=' tidak terpotong
            val params = query.split("&").associate {
                val kv = it.split("=", limit = 2)
                kv[0] to (if (kv.size > 1) kv[1] else "")
            }

            val encodedUrl = params["url"] ?: return
            val keyHex     = params["key"]  ?: return

            // FIX #2: URL-decode dulu sebelum Base64-decode
            val decodedEncoded = URLDecoder.decode(encodedUrl, "UTF-8")
            val realUrl = String(Base64.decode(decodedEncoded, Base64.URL_SAFE))

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            // Byte offset yang diminta player (posisi seek)
            val reqStart = rangeHeader
                ?.replace("bytes=", "")
                ?.split("-")
                ?.get(0)
                ?.toLongOrNull() ?: 0L

            val request = Request.Builder()
                .url(realUrl)
                .header("User-Agent",      "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer",         "https://abyssplayer.com/")
                .header("Origin",          "https://abyssplayer.com")
                .header("Accept-Encoding", "identity")
                .apply { if (rangeHeader != null) header("Range", rangeHeader) }
                .build()

            response = app.baseClient.newCall(request).execute()
            if (!response.isSuccessful) return

            output.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())
            for ((key, value) in response.headers) {
                if (key.equals("transfer-encoding", true) ||
                    key.equals("content-encoding",  true) ||
                    key.equals("connection",         true)) continue
                output.write("$key: $value\r\n".toByteArray())
            }
            output.write("Connection: close\r\n\r\n".toByteArray())

            val body = response.body
            val inputStream = body.byteStream()

            val keyBytes  = keyHex.toByteArray(Charsets.UTF_8)

            // =================================================================
            // FIX SEEK: Inisialisasi AES/CTR langsung ke block yang benar
            //
            // AES/CTR encrypt/decrypt per-block 16 byte. Counter awal (nonce)
            // ada di ivSpec. Untuk seek ke byte N:
            //   - blockNumber = N / 16  → jumlah block yang sudah terlewati
            //   - byteOffsetInBlock = N % 16  → posisi dalam block aktif
            //
            // Cara set counter: increment nonce sebesar blockNumber.
            // Nonce = 16 byte little-endian integer (untuk CTR mode).
            //
            // Setelah counter di-set, cipher akan langsung output keystream
            // yang tepat untuk posisi tersebut — O(1), tidak perlu skip manual.
            //
            // Catatan: Zone encrypt hanya 65536 byte pertama (batas server).
            //          Di luar itu data plaintext — tidak perlu cipher sama sekali.
            // =================================================================
            val ENCRYPTED_ZONE = 65536L  // Hydrax hanya enkripsi 64KB pertama

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val baseIv    = keyBytes.copyOfRange(0, 16)

            // Cipher sudah siap di posisi reqStart (hanya jika masih di zona enkripsi)
            val cipher: Cipher?
            val byteOffsetInBlock: Int

            if (reqStart < ENCRYPTED_ZONE) {
                // Hitung block number dan byte offset dalam block aktif
                val blockNumber      = reqStart / 16L
                byteOffsetInBlock    = (reqStart % 16).toInt()

                // Increment nonce (counter) sebesar blockNumber
                val counterIv = incrementCounter(baseIv, blockNumber)
                val ivSpec    = IvParameterSpec(counterIv)

                cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                // Jika kita mulai di tengah block, buang byte awal yang tidak relevan
                // dengan menghasilkan dummy output sebesar byteOffsetInBlock
                if (byteOffsetInBlock > 0) {
                    cipher.update(ByteArray(byteOffsetInBlock))
                }
            } else {
                // Seek melewati zona enkripsi — tidak perlu cipher sama sekali
                cipher = null
                byteOffsetInBlock = 0
            }

            var offset = reqStart
            val buffer = ByteArray(32768)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (cipher != null && offset < ENCRYPTED_ZONE) {
                    // Hitung berapa byte dari buffer ini masih dalam zona enkripsi
                    val encryptedInThisChunk = minOf(bytesRead.toLong(), ENCRYPTED_ZONE - offset).toInt()

                    // Decrypt bagian yang terenkripsi
                    val decrypted = cipher.update(buffer, 0, encryptedInThisChunk)
                    if (decrypted != null) output.write(decrypted)

                    // Tulis sisa chunk (jika ada) yang sudah di luar zona enkripsi
                    if (encryptedInThisChunk < bytesRead) {
                        output.write(buffer, encryptedInThisChunk, bytesRead - encryptedInThisChunk)
                    }
                } else {
                    // Seluruh chunk di luar zona enkripsi — tulis langsung
                    output.write(buffer, 0, bytesRead)
                }

                offset += bytesRead
                output.flush()
            }

        } catch (e: SocketException) {
            // Normal saat user seek/skip agresif — player tutup koneksi lama
        } catch (e: Exception) {
            // Abaikan error streaming lainnya
        } finally {
            try { response?.close() } catch (e: Exception) {}
            try { client.close()   } catch (e: Exception) {}
        }
    }

    /**
     * Increment AES/CTR nonce (128-bit big-endian counter) sebesar [amount] block.
     * Ini adalah operasi O(1) — jauh lebih efisien daripada memproses jutaan byte.
     */
    private fun incrementCounter(nonce: ByteArray, amount: Long): ByteArray {
        val result = nonce.copyOf()
        var carry  = amount
        // Increment dari byte paling kanan (big-endian counter)
        for (i in result.indices.reversed()) {
            val sum = (result[i].toLong() and 0xFF) + (carry and 0xFF)
            result[i] = (sum and 0xFF).toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            if (carry == 0L) break
        }
        return result
    }
}

// =========================================================================
// EXTRACTOR 1: ABYSS / HYDRAX (TRANSPARENT LOCAL PROXY)
// =========================================================================
open class AbyssExtractor : ExtractorApi() {
    override val name        = "Abyss"
    override val mainUrl     = "https://abyssplayer.com"
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
            val datas = Regex("""datas\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return

            val decodedDatas = String(
                android.util.Base64.decode(datas, android.util.Base64.DEFAULT),
                Charsets.ISO_8859_1
            )
            // FIX #12: gunakan jsonMapper (bukan mapper) agar tidak shadow MainAPI mapper
            val dataJson = jsonMapper.readValue(decodedDatas, HydraxData::class.java)

            val infoSlug = dataJson.slug    ?: slug
            val md5Id    = dataJson.md5_id  ?: return
            val userId   = dataJson.user_id ?: return
            val mediaStr = dataJson.media   ?: return

            val hashInput = "$userId:$infoSlug:$md5Id".toByteArray(Charsets.UTF_8)
            val md5Hash   = MessageDigest.getInstance("MD5").digest(hashInput)
            val keyHex    = md5Hash.joinToString("") { "%02x".format(it) }

            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
            val ivBytes  = keyBytes.copyOfRange(0, 16)

            val cipher    = Cipher.getInstance("AES/CTR/NoPadding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec    = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1))
            val mediaJson = jsonMapper.readValue(
                String(decryptedBytes, Charsets.UTF_8),
                HydraxMedia::class.java
            )

            val mp4Sources = mediaJson.mp4?.sources

            mp4Sources?.forEach { src ->
                val label   = src.label ?: "Unknown"
                val path    = src.path  ?: ""
                val baseUrl = src.url   ?: ""

                if (path.isNotEmpty() && baseUrl.isNotEmpty()) {
                    val srcUrl   = "$baseUrl/$path"
                    val filename = path.substringAfterLast("/")
                    val fnHash   = MessageDigest.getInstance("MD5")
                        .digest(filename.toByteArray(Charsets.UTF_8))
                    val fnKeyHex = fnHash.joinToString("") { "%02x".format(it) }

                    HydraxProxy.start()

                    // FIX #15 (sisi encode): pakai URL_SAFE | NO_WRAP agar tidak ada '=' padding
                    // dan URL-encode hasilnya agar aman di query string
                    val encodedUrl = URLEncoder.encode(
                        android.util.Base64.encodeToString(
                            srcUrl.toByteArray(),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                        ),
                        "UTF-8"
                    )
                    val localProxyUrl =
                        "http://127.0.0.1:${HydraxProxy.port}/?url=$encodedUrl&key=$fnKeyHex"

                    // FIX #11: newExtractorLink adalah suspend fun, tidak perlu wrapper
                    // (sudah dalam suspend context getUrl)
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
        return url.split("?").first().trimEnd('/').split('/')
            .lastOrNull()?.takeIf { it.matches(Regex("[a-zA-Z0-9_\\-]{6,20}")) }
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
    override var name    = "LK21 TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
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

            val html = app.get("$mainUrl/t/$id", headers = headers).text

            var m3u8Url = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8Url.isNullOrBlank()) {
                m3u8Url = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }
            if (m3u8Url.isNullOrBlank()) return null

            val isMp4 = m3u8Url.endsWith(".mp4", ignoreCase = true)
            val type  = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8

            sources.add(
                newExtractorLink(
                    source = "LK21 TurboVIP",
                    name   = "TurboVIP HD",
                    url    = m3u8Url,
                    type   = type
                ) {
                    this.referer  = "$mainUrl/"
                    this.quality  = Qualities.Unknown.value
                    this.headers  = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }
}

// =========================================================================
// EXTRACTOR 3: HOW NETWORK (P2P)
// =========================================================================
open class HowNetworkExtractor : ExtractorApi() {
    override var name    = "LK21 HowNetwork"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("id=").substringBefore("&")
            if (id.isEmpty()) return null

            val responseText = app.post(
                url     = "$mainUrl/api2.php?id=$id",
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

            // FIX #12: gunakan jsonMapper
            val parsedRes = try {
                jsonMapper.readValue(responseText, HowNetworkResponse::class.java)
            } catch (e: Exception) { null }

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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }
}

// =========================================================================
// EXTRACTOR 4: CAST HD
// =========================================================================
open class CastExtractor : ExtractorApi() {
    override var name    = "CAST HD"
    override var mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = false

    private fun b64url(b: ByteArray): String = android.util.Base64.encodeToString(
        b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    )

    private fun b64urlDecode(s: String): ByteArray {
        var std = s.replace("-", "+").replace("_", "/")
        val pad = 4 - (std.length % 4)
        if (pad != 4) std += "=".repeat(pad)
        return android.util.Base64.decode(std, android.util.Base64.DEFAULT)
    }

    private fun getRandomBytes(size: Int): ByteArray =
        ByteArray(size).apply { java.security.SecureRandom().nextBytes(this) }

    // FIX #7: DER parsing yang benar dengan handle long-form length encoding
    // DER signature bisa punya length > 127 byte, di mana byte pertama length
    // adalah 0x80|numBytes dan numBytes byte berikutnya adalah panjang sebenarnya.
    private fun parseDerLength(der: ByteArray, offset: Int): Pair<Int, Int> {
        val b = der[offset].toInt() and 0xFF
        return if (b < 0x80) {
            // Short form: panjang langsung di byte ini
            Pair(b, offset + 1)
        } else {
            // Long form: b & 0x7F = jumlah byte panjang berikutnya
            val numLenBytes = b and 0x7F
            var len = 0
            for (i in 0 until numLenBytes) {
                len = (len shl 8) or (der[offset + 1 + i].toInt() and 0xFF)
            }
            Pair(len, offset + 1 + numLenBytes)
        }
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        // DER SEQUENCE: 0x30 <len> 0x02 <rLen> <r> 0x02 <sLen> <s>
        var offset = 1  // skip 0x30 (SEQUENCE tag)
        val (_, seqBodyStart) = parseDerLength(der, offset)
        offset = seqBodyStart

        // Parse R
        check(der[offset] == 0x02.toByte()) { "Expected INTEGER tag for R" }
        offset++
        val (rLength, rStart) = parseDerLength(der, offset)
        offset = rStart
        val rBytes = der.copyOfRange(offset, offset + rLength)
        offset += rLength

        // Parse S
        check(der[offset] == 0x02.toByte()) { "Expected INTEGER tag for S" }
        offset++
        val (sLength, sStart) = parseDerLength(der, offset)
        offset = sStart
        val sBytes = der.copyOfRange(offset, offset + sLength)

        // Drop leading zero padding (DER tambahkan 0x00 jika bit tinggi set)
        val rStripped = rBytes.dropWhile { it == 0.toByte() }.toByteArray()
        val sStripped = sBytes.dropWhile { it == 0.toByte() }.toByteArray()

        // Pad ke 32 byte (P-256 = 32 byte per komponen)
        val rPadded = ByteArray(32); val sPadded = ByteArray(32)
        System.arraycopy(rStripped, 0, rPadded, 32 - rStripped.size, rStripped.size)
        System.arraycopy(sStripped, 0, sPadded, 32 - sStripped.size, sStripped.size)

        return rPadded + sPadded
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        val videoId = url.substringAfterLast("/")

        val commonHeaders = mapOf(
            "User-Agent"       to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Origin"           to mainUrl,
            "Referer"          to url,
            "X-Embed-Origin"   to "playeriframe.sbs",
            "X-Embed-Parent"   to url,
            "X-Embed-Referer"  to "https://playeriframe.sbs/"
        )

        try {
            val chalRes  = app.post("$mainUrl/api/videos/access/challenge", headers = commonHeaders)
            // FIX #12: gunakan jsonMapper
            val chalJson = try {
                jsonMapper.readValue(chalRes.text, CastChalResp::class.java)
            } catch (e: Exception) { null } ?: return null

            val nonce = chalJson.nonce          ?: return null
            val cid   = chalJson.challenge_id   ?: return null

            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(kp.private)
            sig.update(nonce.toByteArray(Charsets.UTF_8))
            // FIX #7: derToRaw sekarang handle long-form DER dengan benar
            val rawSignature = derToRaw(sig.sign())

            val pub = kp.public as ECPublicKey
            var xBytes = pub.w.affineX.toByteArray()
            var yBytes = pub.w.affineY.toByteArray()

            if (xBytes.size > 32) xBytes = xBytes.copyOfRange(xBytes.size - 32, xBytes.size)
            if (yBytes.size > 32) yBytes = yBytes.copyOfRange(yBytes.size - 32, yBytes.size)
            if (xBytes.size < 32) xBytes = ByteArray(32 - xBytes.size) { 0 } + xBytes
            if (yBytes.size < 32) yBytes = ByteArray(32 - yBytes.size) { 0 } + yBytes

            val attestPayload = mapOf(
                "viewer_id"    to b64url(getRandomBytes(16)),
                "device_id"    to b64url(getRandomBytes(16)),
                "challenge_id" to cid,
                "nonce"        to nonce,
                "signature"    to b64url(rawSignature),
                "public_key"   to mapOf(
                    "crv" to "P-256", "ext" to true,
                    "key_ops" to listOf("verify"), "kty" to "EC",
                    "x" to b64url(xBytes), "y" to b64url(yBytes)
                ),
                "client"     to mapOf("user_agent" to commonHeaders["User-Agent"]!!),
                "attributes" to mapOf("entropy" to "high")
            )

            val attestRes  = app.post("$mainUrl/api/videos/access/attest", headers = commonHeaders, json = attestPayload)
            val attestJson = try {
                jsonMapper.readValue(attestRes.text, CastAttestResp::class.java)
            } catch (e: Exception) { null } ?: return null
            val token = attestJson.token ?: return null

            val pbPayload = mapOf("fingerprint" to mapOf("token" to token))
            val pbRes     = app.post("$mainUrl/api/videos/$videoId/embed/playback", headers = commonHeaders, json = pbPayload)

            val pbResp = try {
                jsonMapper.readValue(pbRes.text, CastPbResp::class.java)?.playback
            } catch (e: Exception) { null } ?: return null

            val iv      = b64urlDecode(pbResp.iv      ?: return null)
            val payload = b64urlDecode(pbResp.payload  ?: return null)
            val keyParts = pbResp.key_parts            ?: return null

            val keysToTest = mutableListOf<ByteArray>()
            val chunks16   = mutableListOf<ByteArray>()

            for (p in keyParts) {
                if (p.length == 32) keysToTest.add(p.toByteArray(Charsets.UTF_8))
                try {
                    val dec = b64urlDecode(p)
                    if (dec.size == 32)      keysToTest.add(dec)
                    else if (dec.size == 16) chunks16.add(dec)
                } catch (e: Exception) {}
            }

            for (i in chunks16.indices) {
                for (j in chunks16.indices) {
                    if (i != j) keysToTest.add(chunks16[i] + chunks16[j])
                }
            }

            var realUrl: String? = null
            var qualityLabel     = "HD"

            for (keyBytes in keysToTest) {
                try {
                    val cipher    = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec      = GCMParameterSpec(128, iv)
                    val secretKey = SecretKeySpec(keyBytes, "AES")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                    val decrypted = cipher.doFinal(payload)

                    val jsonString = String(decrypted, Charsets.UTF_8)
                    // FIX #12: gunakan jsonMapper
                    val parsedData = jsonMapper.readValue(jsonString, CastDecrypted::class.java)

                    realUrl      = parsedData?.sources?.firstOrNull()?.url
                    qualityLabel = parsedData?.sources?.firstOrNull()?.label ?: "HD"

                    if (realUrl != null) break
                } catch (e: Exception) {}
            }

            if (realUrl != null) {
                sources.add(
                    newExtractorLink(
                        source = "CAST HD",
                        name   = "CAST $qualityLabel",
                        url    = realUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin"     to mainUrl,
                            "Referer"    to "$mainUrl/",
                            "User-Agent" to commonHeaders["User-Agent"]!!
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sources
    }
}
