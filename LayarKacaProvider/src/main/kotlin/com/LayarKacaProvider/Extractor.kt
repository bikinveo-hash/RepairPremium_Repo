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
    // FIX: tambah dua field ini yang sebelumnya tidak di-parse.
    // size     = total ukuran file asli (semua part digabung).
    // partSize = ukuran satu URL/CDN-file (satu bagian dari total).
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

// =========================================================================
// HYDRAX PROXY
// =========================================================================
object HydraxProxy {
    var port: Int = 0
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

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
            // Timeout lebih longgar untuk koneksi lambat
            client.soTimeout = 30_000

            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()

            // ── Baca request line ─────────────────────────────────────────
            val requestLine = reader.readLine() ?: return
            val reqParts    = requestLine.split(" ")
            if (reqParts.size < 2) return
            val reqPath = reqParts[1]
            if (!reqPath.contains("?url=")) return

            // ── Parse query params ────────────────────────────────────────
            val params = reqPath.substringAfter("?")
                .split("&")
                .associate {
                    val kv = it.split("=", limit = 2)
                    kv[0] to (if (kv.size > 1) kv[1] else "")
                }

            val encodedUrl = params["url"] ?: return
            val keyHex     = params["key"]  ?: return
            val realUrl    = String(Base64.decode(encodedUrl, Base64.URL_SAFE))

            // ── Baca semua headers dari ExoPlayer ─────────────────────────
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            // Byte yang diminta ExoPlayer
            val reqStart: Long = rangeHeader
                ?.replace("bytes=", "")
                ?.split("-")?.getOrNull(0)
                ?.toLongOrNull() ?: 0L

            // ── Request ke CDN ────────────────────────────────────────────
            val cdnRequest = Request.Builder()
                .url(realUrl)
                .header("User-Agent",      "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer",         "https://abyssplayer.com/")
                .header("Origin",          "https://abyssplayer.com")
                .header("Accept-Encoding", "identity")
                .apply { if (rangeHeader != null) header("Range", rangeHeader) }
                .build()

            response = app.baseClient.newCall(cdnRequest).execute()
            if (!response.isSuccessful) return

            val code         = response.code
            val contentRange = response.header("Content-Range") // "bytes START-END/TOTAL"

            // ── FIX UTAMA: tentukan actualOffset dari respons CDN ─────────
            //
            // BUG SEBELUMNYA:
            //   Proxy selalu pakai reqStart sebagai cipher offset.
            //   Tapi CDN Hydrax kadang balas dengan HTTP 200 OK dan
            //   mengirim data dari byte ke-0, mengabaikan Range header.
            //   Akibatnya cipher di-advance ke reqStart (misal 5.000.000),
            //   padahal data yang datang dari CDN dimulai dari byte 0.
            //   Hasil dekripsi 64KB pertama = garbage → ExoPlayer stuck.
            //
            // FIX:
            //   Baca Content-Range dari respons CDN untuk tahu dari byte
            //   berapa data yang sebenarnya dikirim, baru advance cipher.
            //
            val actualOffset: Long = when {
                code == 206 && contentRange != null -> {
                    // CDN honor Range. Baca START dari "bytes START-END/TOTAL"
                    contentRange
                        .substringAfter("bytes ", "")
                        .substringBefore("-")
                        .toLongOrNull() ?: reqStart
                }
                code == 200 -> {
                    // CDN abaikan Range, kirim dari awal file → offset = 0
                    0L
                }
                else -> reqStart
            }

            // ── Siapkan cipher AES-CTR ────────────────────────────────────
            //
            // Sistem enkripsi Hydrax (dari sw_bundle.js):
            //   - Hanya 65536 byte PERTAMA per file yang dienkripsi AES-CTR
            //   - Key = fn_key (MD5 dari nama file), 32 hex char, pakai as-is UTF-8
            //   - IV  = 16 byte pertama key
            //   - Selebihnya (byte 65536+) adalah plain data, tidak dienkripsi
            //
            val keyBytes  = keyHex.toByteArray(Charsets.UTF_8)   // 32 bytes
            val ivBytes   = keyBytes.copyOfRange(0, 16)            // IV = 16 byte pertama
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher    = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ivBytes))

            // Advance cipher state ke actualOffset (hanya jika masih di region enkripsi)
            if (actualOffset in 1L until 65536L) {
                var remaining = actualOffset
                val skipBuf   = ByteArray(8192)
                while (remaining > 0) {
                    val n = minOf(remaining, skipBuf.size.toLong()).toInt()
                    cipher.update(skipBuf, 0, n)
                    remaining -= n
                }
            }

            // ── Forward response headers ke ExoPlayer ────────────────────
            output.write("HTTP/1.1 $code ${response.message}\r\n".toByteArray())

            for ((hKey, hVal) in response.headers) {
                // Skip hop-by-hop headers yang tidak boleh diforward
                if (hKey.equals("transfer-encoding", ignoreCase = true)) continue
                if (hKey.equals("content-encoding",  ignoreCase = true)) continue
                if (hKey.equals("connection",         ignoreCase = true)) continue
                output.write("$hKey: $hVal\r\n".toByteArray())
            }
            output.write("Connection: close\r\n\r\n".toByteArray())

            // ── Stream + dekripsi ─────────────────────────────────────────
            val inputStream = response.body.byteStream()
            val buffer      = ByteArray(32768)
            var bytesRead:  Int
            var streamPos   = actualOffset

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (streamPos < 65536L) {
                    // Buffer (sebagian atau seluruhnya) di region terenkripsi
                    val n = minOf(bytesRead.toLong(), 65536L - streamPos).toInt()

                    // Dekripsi bagian terenkripsi
                    val decrypted = cipher.update(buffer, 0, n)
                    if (decrypted != null) output.write(decrypted)

                    // Sisa buffer (jika ada) sudah plain
                    if (n < bytesRead) output.write(buffer, n, bytesRead - n)

                } else {
                    // Seluruh buffer plain (di luar 64KB pertama)
                    output.write(buffer, 0, bytesRead)
                }

                streamPos += bytesRead
                output.flush()
            }

        } catch (_: SocketException) {
            // Normal: ExoPlayer putus koneksi saat seek atau stop
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { response?.close() } catch (_: Exception) {}
            try { client.close()    } catch (_: Exception) {}
        }
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

                // TAMBAHKAN BARIS INI:
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
// EXTRACTOR 2: TURBO VIP  (tidak ada perubahan)
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
// EXTRACTOR 3: HOW NETWORK  (tidak ada perubahan)
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
// EXTRACTOR 4: CAST HD  (tidak ada perubahan)
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
