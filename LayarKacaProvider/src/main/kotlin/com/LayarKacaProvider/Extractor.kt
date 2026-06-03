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

val mapper: ObjectMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// =========================================================================
// DATA CLASSES UNTUK TYPE-SAFE JSON PARSING (ANTI-WARNING)
// =========================================================================
data class HydraxData(
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("md5_id") val md5_id: String? = null,
    @JsonProperty("user_id") val user_id: String? = null,
    @JsonProperty("media") val media: String? = null
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
    @JsonProperty("path") val path: String? = null,
    @JsonProperty("url") val url: String? = null
)

data class HowNetworkResponse(
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("file") val file: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("title") val title: String?
)

data class CastChalResp(
    @JsonProperty("nonce") val nonce: String?, 
    @JsonProperty("challenge_id") val challenge_id: String?
)
data class CastAttestResp(
    @JsonProperty("token") val token: String?
)
data class CastPbResp(
    @JsonProperty("playback") val playback: CastPlaybackInfo?
)
data class CastPlaybackInfo(
    @JsonProperty("iv") val iv: String?, 
    @JsonProperty("payload") val payload: String?, 
    @JsonProperty("key_parts") val key_parts: List<String>?
)
data class CastDecrypted(
    @JsonProperty("sources") val sources: List<CastSource>?
)
data class CastSource(
    @JsonProperty("url") val url: String?, 
    @JsonProperty("label") val label: String?, 
    @JsonProperty("type") val type: String?
)

// =========================================================================
// MESIN SERVER PROXY LOKAL (OBAT ANTI-CRONET & ANTI-EOF)
// =========================================================================
object HydraxProxy {
    @Volatile var port: Int = 0
    @Volatile private var isRunning = false
    @Volatile private var serverSocket: ServerSocket? = null

    @Synchronized
    fun start() {
        if (isRunning) return
        try {
            val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            isRunning = true
            thread(isDaemon = true) {
                while (isRunning) {
                    try {
                        val client = ss.accept()
                        thread(isDaemon = true) { handleClient(client) }
                    } catch (e: Exception) {
                        // ServerSocket closed or interrupted — exit loop cleanly
                        if (!isRunning) break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        port = 0
    }

    private fun handleClient(client: Socket) {
        var response: Response? = null
        try {
            // Timeout agresif: 30 detik untuk baca header, cegah hang pada persistent connection
            client.soTimeout = 30000

            val rawInput  = client.getInputStream()
            val output    = client.getOutputStream()

            // ---------------------------------------------------------------
            // FIX: Baca HTTP header byte-per-byte sampai \r\n\r\n.
            // BufferedReader.readLine() berbahaya di sini karena ia akan terus
            // menunggu data dari socket (blocking) pada HTTP/1.1 keep-alive
            // connection — ExoPlayer tidak selalu menutup koneksi setelah header.
            // Dengan membaca sampai \r\n\r\n kita tahu persis kapan header selesai.
            // ---------------------------------------------------------------
            val headerBuf  = StringBuilder()
            val byteArr    = ByteArray(1)
            var lastFour   = ""
            var headerDone = false
            val maxHeader  = 8192 // batas maksimal header 8KB
            while (headerBuf.length < maxHeader) {
                val n = rawInput.read(byteArr)
                if (n == -1) break
                val ch = byteArr[0].toInt().toChar()
                headerBuf.append(ch)
                lastFour = (lastFour + ch).takeLast(4)
                if (lastFour == "\r\n\r\n") { headerDone = true; break }
            }
            if (!headerDone) return

            // Parse request line dan semua header
            val headerLines = headerBuf.toString().split("\r\n")
            val requestLine = headerLines.firstOrNull() ?: return
            val reqParts    = requestLine.split(" ")
            if (reqParts.size < 2) return
            val path = reqParts[1]
            if (!path.contains("?url=")) return

            val query  = path.substringAfter("?")
            val params = query.split("&").associate { param ->
                val eqIdx = param.indexOf('=')
                if (eqIdx == -1) param to ""
                else param.substring(0, eqIdx) to param.substring(eqIdx + 1)
            }

            val encodedUrl = params["url"] ?: return
            val keyHex     = params["key"] ?: return
            val realUrl    = String(Base64.decode(encodedUrl, Base64.URL_SAFE))

            // Ambil Range header dari request ExoPlayer
            var rangeHeader: String? = null
            for (line in headerLines.drop(1)) {
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substringAfter(":").trim()
                    break
                }
            }

            // reqStart = byte pertama yang diminta player
            val reqStart = rangeHeader
                ?.removePrefix("bytes=")
                ?.split("-")?.getOrNull(0)
                ?.toLongOrNull() ?: 0L

            // ---------------------------------------------------------------
            // FIX: Kirim Range request ke CDN Hydrax.
            // Ada 3 skenario response CDN:
            //   (A) 206 Partial Content + data dari reqStart  → ideal, langsung pakai
            //   (B) 416 Range Not Satisfiable                 → retry tanpa Range header,
            //                                                    lalu skip manual ke reqStart
            //   (C) 200 OK (CDN abaikan Range, kirim dari 0) → skip manual ke reqStart
            // ---------------------------------------------------------------
            fun buildCdnRequest(withRange: Boolean) = Request.Builder()
                .url(realUrl)
                .header("User-Agent",       "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer",          "https://abyssplayer.com/")
                .header("Origin",           "https://abyssplayer.com")
                .header("Accept-Encoding",  "identity")
                .apply { if (withRange && rangeHeader != null) header("Range", rangeHeader) }
                .build()

            response = app.baseClient.newCall(buildCdnRequest(withRange = true)).execute()

            // Skenario B: CDN tolak Range → retry dari awal tanpa Range header
            if (response.code == 416) {
                response.close()
                response = app.baseClient.newCall(buildCdnRequest(withRange = false)).execute()
            }

            if (!response.isSuccessful) {
                // Kirim 503 ke player agar tidak hang menunggu response
                output.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val cdnCode = response.code
            val body    = response.body
            val inputStream = body.byteStream()

            // ---------------------------------------------------------------
            // Skenario C: CDN balas 200 (bukan 206) padahal kita minta range.
            // CDN kirim dari byte 0, kita harus skip manual sampai reqStart.
            // Ini sering terjadi saat seek ke menit terakhir — CDN kecil tidak
            // support Range request dan selalu kirim file dari awal.
            // ---------------------------------------------------------------
            var streamOffset = 0L // posisi aktual yang sudah kita baca dari CDN stream
            if (cdnCode == 200 && reqStart > 0) {
                // Skip byte dari CDN sampai posisi reqStart
                val skipBuf   = ByteArray(65536)
                var remaining = reqStart
                while (remaining > 0) {
                    val toRead = minOf(remaining, skipBuf.size.toLong()).toInt()
                    val nRead  = inputStream.read(skipBuf, 0, toRead)
                    if (nRead == -1) break
                    remaining    -= nRead
                    streamOffset += nRead
                }
                // Setelah skip, posisi stream sudah di reqStart
            }

            // Tentukan status code dan header yang dikirim ke player
            // Kalau CDN balas 206, teruskan 206. Kalau CDN balas 200 tapi kita
            // sudah skip manual, tetap kirim 206 agar ExoPlayer tahu ini partial.
            val replyCode = if (reqStart > 0) 206 else 200
            val replyMsg  = if (replyCode == 206) "Partial Content" else "OK"

            output.write("HTTP/1.1 $replyCode $replyMsg\r\n".toByteArray())

            // Forward header CDN ke player, kecuali header yang perlu dikontrol proxy
            val skipHeaders = setOf(
                "transfer-encoding", "content-encoding", "connection",
                "content-range", "content-length" // kita recompute ini di bawah
            )
            for ((key, value) in response.headers) {
                if (key.lowercase() in skipHeaders) continue
                output.write("$key: $value\r\n".toByteArray())
            }

            // Tambah Content-Range header yang benar agar ExoPlayer tidak bingung
            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            if (replyCode == 206) {
                if (contentLength != null) {
                    val totalSize = if (cdnCode == 206) reqStart + contentLength else contentLength
                    val endByte   = totalSize - 1
                    output.write("Content-Range: bytes $reqStart-$endByte/$totalSize\r\n".toByteArray())
                    output.write("Content-Length: ${totalSize - reqStart}\r\n".toByteArray())
                } else {
                    output.write("Content-Range: bytes $reqStart-*/*\r\n".toByteArray())
                }
            } else {
                if (contentLength != null) {
                    output.write("Content-Length: $contentLength\r\n".toByteArray())
                }
            }

            output.write("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()

            // ---------------------------------------------------------------
            // STREAM + DECRYPT
            //
            // HYDRAX enkripsi HANYA 64KB pertama (byte 0–65535), sisanya plaintext.
            //
            //   - streamOffset = posisi aktual di CDN stream (setelah skip manual)
            //   - offset       = posisi byte yang sedang dikirim ke player
            //
            // Cipher CTR hanya diinisialisasi kalau zona enkripsi masih relevan.
            // ---------------------------------------------------------------
            val keyBytes  = keyHex.toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "AES")

            // streamOffset setelah skip manual = reqStart (kalau skenario C)
            // atau 0 (kalau skenario A/B, CDN sudah kirim dari reqStart)
            val decryptOffset = if (cdnCode == 206) reqStart else streamOffset

            val cipher: Cipher? = if (decryptOffset < 65536L) {
                val ivSpec = IvParameterSpec(keyBytes.copyOfRange(0, 16))
                Cipher.getInstance("AES/CTR/NoPadding").also {
                    it.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                    // Advance CTR ke posisi decryptOffset (max 65535, aman untuk toInt())
                    if (decryptOffset > 0) it.update(ByteArray(decryptOffset.toInt()))
                }
            } else {
                null // sudah di zona plaintext, tidak perlu cipher
            }

            var offset = decryptOffset
            val buffer = ByteArray(32768)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                when {
                    // Kasus 1: seluruh chunk di zona plaintext → forward langsung
                    offset >= 65536L -> {
                        output.write(buffer, 0, bytesRead)
                    }
                    // Kasus 2: chunk melintasi batas enkripsi (boundary crossing)
                    offset + bytesRead > 65536L -> {
                        val encPart = (65536L - offset).toInt()
                        val dec = cipher!!.update(buffer, 0, encPart)
                        if (dec != null) output.write(dec)
                        output.write(buffer, encPart, bytesRead - encPart)
                    }
                    // Kasus 3: seluruh chunk masih di zona enkripsi
                    else -> {
                        val dec = cipher!!.update(buffer, 0, bytesRead)
                        if (dec != null) output.write(dec)
                    }
                }
                offset += bytesRead
                output.flush()
            }

        } catch (e: SocketException) {
            // Wajar saat user seek brutal atau player tutup koneksi lebih awal
        } catch (e: Exception) {
            // Abaikan error streaming lain
        } finally {
            try { response?.close() } catch (e: Exception) {}
            try { client.close() }    catch (e: Exception) {}
        }
    }
}

// =========================================================================
// EXTRACTOR 1: ABYSS / HYDRAX (TRANSPARENT LOCAL PROXY)
// =========================================================================
open class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    private fun baseHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
        "Referer"    to referer,
        "Origin"     to mainUrl,
        "Accept"     to "*/*"
    )

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val pageRef = referer ?: "$mainUrl/"
        val slug = extractSlugFromUrl(url) ?: return
        val hdrs = baseHeaders(pageRef)

        try {
            val html = app.get("$mainUrl/?v=$slug", headers = hdrs).text
            val datas = Regex("""datas\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return

            // Perbaikan Warning 2-5: Parsing menggunakan Type-Safe Data Classes
            val decodedDatas = String(android.util.Base64.decode(datas, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
            val dataJson = mapper.readValue(decodedDatas, HydraxData::class.java)

            val infoSlug = dataJson.slug ?: slug
            val md5Id = dataJson.md5_id ?: return
            val userId = dataJson.user_id ?: return
            val mediaStr = dataJson.media ?: return

            val hashInput = "$userId:$infoSlug:$md5Id".toByteArray(Charsets.UTF_8)
            val md5Hash = MessageDigest.getInstance("MD5").digest(hashInput)
            val keyHex = md5Hash.joinToString("") { "%02x".format(it) }

            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1))
            val mediaJson = mapper.readValue(String(decryptedBytes, Charsets.UTF_8), HydraxMedia::class.java)

            val mp4Sources = mediaJson.mp4?.sources

            mp4Sources?.forEach { src ->
                val label = src.label ?: "Unknown"
                val codec = src.codec ?: "h264"
                val path = src.path ?: ""
                val baseUrl = src.url ?: ""

                if (path.isNotEmpty() && baseUrl.isNotEmpty()) {
                    val srcUrl = "$baseUrl/$path"

                    val filename = path.substringAfterLast("/")
                    val fnHash = MessageDigest.getInstance("MD5").digest(filename.toByteArray(Charsets.UTF_8))
                    val fnKeyHex = fnHash.joinToString("") { "%02x".format(it) }

                    HydraxProxy.start()

                    val encodedUrl = android.util.Base64.encodeToString(srcUrl.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                    val localProxyUrl = "http://127.0.0.1:${HydraxProxy.port}/?url=$encodedUrl&key=$fnKeyHex"

                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = localProxyUrl,
                            type = ExtractorLinkType.VIDEO
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
        Regex("""(?:e|embed|v|play)/([a-zA-Z0-9_\-]{6,20})""").find(url)?.groupValues?.get(1)?.let { return it }
        return url.split("?").first().trimEnd('/').split('/').lastOrNull()?.takeIf { it.matches(Regex("[a-zA-Z0-9_\\-]{6,20}")) }
    }

    private fun labelToQuality(label: String): Int = when {
        label.contains("2160") || label.contains("4k", ignoreCase = true) -> Qualities.P2160.value
        label.contains("1440") -> Qualities.P1440.value
        label.contains("1080") -> Qualities.P1080.value
        label.contains("720") -> Qualities.P720.value
        label.contains("480") -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }
}

// =========================================================================
// EXTRACTOR 2: TURBO VIP
// =========================================================================
open class Lk21TurboExtractor : ExtractorApi() {
    override var name      = "LK21 TurboVIP"
    override var mainUrl   = "https://turbovidhls.com"
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

            val response = app.get("$mainUrl/t/$id", headers = headers)
            val html     = response.text

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
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin"  to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
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
    override var name = "LK21 HowNetwork"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val id = url.substringAfter("id=").substringBefore("&")
            if (id.isEmpty()) return null

            val response = app.post(
                url = "$mainUrl/api2.php?id=$id",
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to url,
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                ),
                data = mapOf(
                    "r" to "https://playeriframe.sbs/",
                    "d" to "cloud.hownetwork.xyz"
                )
            ).text

            val parsedRes = try { mapper.readValue(response, HowNetworkResponse::class.java) } catch(e: Exception) { null }
            val m3u8Url = parsedRes?.file

            if (!m3u8Url.isNullOrBlank()) {
                sources.add(
                    newExtractorLink(
                        source = "LK21 HowNetwork",
                        name = "HowNetwork HD",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/"
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

// =========================================================================
// EXTRACTOR 4: CAST HD
// =========================================================================
open class CastExtractor : ExtractorApi() {
    override var name = "CAST HD"
    override var mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = false

    private fun b64url(b: ByteArray): String = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    private fun b64urlDecode(s: String): ByteArray {
        var standardized = s.replace("-", "+").replace("_", "/")
        val padding = 4 - (standardized.length % 4)
        if (padding != 4) {
            standardized += "=".repeat(padding)
        }
        return android.util.Base64.decode(standardized, android.util.Base64.DEFAULT)
    }
    private fun getRandomBytes(size: Int): ByteArray = ByteArray(size).apply { java.security.SecureRandom().nextBytes(this) }

    private fun derToRaw(der: ByteArray): ByteArray {
        // DER SEQUENCE tag (0x30) at [0], length at [1] — skip to inner SEQUENCE
        var offset = 2
        // INTEGER tag (0x02) at offset, length at offset+1
        val rLength = der[offset + 1].toInt() and 0xFF
        val rOffset = offset + 2
        offset += 2 + rLength
        val sLength = der[offset + 1].toInt() and 0xFF
        val sOffset = offset + 2

        val rStr = der.copyOfRange(rOffset, rOffset + rLength).dropWhile { it == 0.toByte() }.toByteArray()
        val sStr = der.copyOfRange(sOffset, sOffset + sLength).dropWhile { it == 0.toByte() }.toByteArray()

        // Clamp to max 32 bytes in case of unexpected oversized values
        val rClamped = if (rStr.size > 32) rStr.copyOfRange(rStr.size - 32, rStr.size) else rStr
        val sClamped = if (sStr.size > 32) sStr.copyOfRange(sStr.size - 32, sStr.size) else sStr

        val rPadded = ByteArray(32) { 0 }
        val sPadded = ByteArray(32) { 0 }

        System.arraycopy(rClamped, 0, rPadded, 32 - rClamped.size, rClamped.size)
        System.arraycopy(sClamped, 0, sPadded, 32 - sClamped.size, sClamped.size)

        return rPadded + sPadded
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        val videoId = url.substringAfterLast("/")
        
        val commonHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Origin" to mainUrl,
            "Referer" to url,
            "X-Embed-Origin" to "playeriframe.sbs",
            "X-Embed-Parent" to url,
            "X-Embed-Referer" to "https://playeriframe.sbs/"
        )

        try {
            val chalRes = app.post("$mainUrl/api/videos/access/challenge", headers = commonHeaders)
            val chalJson = try { mapper.readValue(chalRes.text, CastChalResp::class.java) } catch(e:Exception){ null } ?: return null
    
            val nonce = chalJson.nonce ?: return null
            val cid = chalJson.challenge_id ?: return null

            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(kp.private)
            sig.update(nonce.toByteArray(Charsets.UTF_8))
            val rawSignature = derToRaw(sig.sign())

            val pub = kp.public as ECPublicKey
            var xBytes = pub.w.affineX.toByteArray()
            var yBytes = pub.w.affineY.toByteArray()
            
            if (xBytes.size > 32) xBytes = xBytes.copyOfRange(xBytes.size - 32, xBytes.size)
            if (yBytes.size > 32) yBytes = yBytes.copyOfRange(yBytes.size - 32, yBytes.size)
            if (xBytes.size < 32) xBytes = ByteArray(32 - xBytes.size) { 0 } + xBytes
            if (yBytes.size < 32) yBytes = ByteArray(32 - yBytes.size) { 0 } + yBytes

            val attestPayload = mapOf(
                "viewer_id" to b64url(getRandomBytes(16)),
                "device_id" to b64url(getRandomBytes(16)),
                "challenge_id" to cid,
                "nonce" to nonce,
                "signature" to b64url(rawSignature),
                "public_key" to mapOf(
                    "crv" to "P-256", "ext" to true, "key_ops" to listOf("verify"), "kty" to "EC",
                    "x" to b64url(xBytes), "y" to b64url(yBytes)
                ),
                "client" to mapOf("user_agent" to commonHeaders["User-Agent"]!!),
                "attributes" to mapOf("entropy" to "high")
            )

            val attestRes = app.post("$mainUrl/api/videos/access/attest", headers = commonHeaders, json = attestPayload)
            val attestJson = try { mapper.readValue(attestRes.text, CastAttestResp::class.java) } catch(e:Exception){ null } ?: return null
            val token = attestJson.token ?: return null

            val pbPayload = mapOf("fingerprint" to mapOf("token" to token))
            val pbRes = app.post("$mainUrl/api/videos/$videoId/embed/playback", headers = commonHeaders, json = pbPayload)
            
            val pbResp = try { mapper.readValue(pbRes.text, CastPbResp::class.java)?.playback } catch(e:Exception){ null } ?: return null
            val iv = b64urlDecode(pbResp.iv ?: return null)
            val payload = b64urlDecode(pbResp.payload ?: return null)
            val keyParts = pbResp.key_parts ?: return null

            val keysToTest = mutableListOf<ByteArray>()
            val chunks16 = mutableListOf<ByteArray>()

            for (p in keyParts) {
                if (p.length == 32) keysToTest.add(p.toByteArray(Charsets.UTF_8))
                try {
                    val dec = b64urlDecode(p)
                    if (dec.size == 32) keysToTest.add(dec)
                    else if (dec.size == 16) chunks16.add(dec)
                } catch (e: Exception) {}
            }

            for (i in chunks16.indices) {
                for (j in chunks16.indices) {
                    if (i != j) keysToTest.add(chunks16[i] + chunks16[j])
                }
            }

            var realUrl: String? = null
            var qualityLabel = "HD"

            for (keyBytes in keysToTest) {
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(128, iv)
                    val secretKey = SecretKeySpec(keyBytes, "AES")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                    val decrypted = cipher.doFinal(payload)
                    
                    val jsonString = String(decrypted, Charsets.UTF_8)
                    val parsedData = mapper.readValue(jsonString, CastDecrypted::class.java)
                    
                    realUrl = parsedData?.sources?.firstOrNull()?.url
                    qualityLabel = parsedData?.sources?.firstOrNull()?.label ?: "HD"
                    
                    if (realUrl != null) break 
                } catch (e: Exception) {}
            }

            if (realUrl != null) {
                sources.add(
                    newExtractorLink(
                        source = "CAST HD",
                        name = "CAST $qualityLabel",
                        url = realUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
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
