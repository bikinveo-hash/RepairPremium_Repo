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
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.interfaces.ECPublicKey
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

val mapper: ObjectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// =============================================================================
// DATA CLASSES
// =============================================================================
data class HydraxData(
    @JsonProperty("slug")    val slug: String?   = null,
    @JsonProperty("md5_id")  val md5_id: String? = null,
    @JsonProperty("user_id") val user_id: String? = null,
    @JsonProperty("media")   val media: String?  = null
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


// =============================================================================
// LOCAL PROXY UNTUK HYDRAX (AES/CTR DENGAN SEEK YANG BENAR)
// =============================================================================
object HydraxProxy {
    var port: Int = 0

    // FIX #3: Pakai AtomicBoolean agar thread-safe (tidak ada race condition)
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    // FIX #2: Pakai thread pool, bukan thread baru per koneksi
    private val threadPool = Executors.newCachedThreadPool()

    fun start() {
        // FIX #3: compareAndSet memastikan hanya satu instance yang start
        if (!isRunning.compareAndSet(false, true)) return
        try {
            serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            thread(isDaemon = true) {
                while (isRunning.get()) {
                    try {
                        val client = serverSocket!!.accept()
                        // FIX #2: Submit ke thread pool, bukan spawn thread baru
                        threadPool.submit { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning.get()) e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            isRunning.set(false)
            e.printStackTrace()
        }
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

            val query  = path.substringAfter("?")
            val params = query.split("&").associate {
                val kv = it.split("=", limit = 2)
                kv[0] to (kv.getOrElse(1) { "" })
            }

            val encodedUrl = params["url"] ?: return
            val keyHex     = params["key"]  ?: return

            // FIX #4: Base64 decode dibungkus try-catch
            val realUrl = try {
                String(Base64.decode(encodedUrl, Base64.URL_SAFE), Charsets.UTF_8)
            } catch (e: Exception) {
                return
            }

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val reqStart = rangeHeader
                ?.replace("bytes=", "")
                ?.split("-")
                ?.getOrNull(0)
                ?.toLongOrNull() ?: 0L

            val request = Request.Builder()
                .url(realUrl)
                .header("User-Agent",       "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer",          "https://abyssplayer.com/")
                .header("Origin",           "https://abyssplayer.com")
                .header("Accept-Encoding",  "identity")
                .apply { if (rangeHeader != null) header("Range", rangeHeader) }
                .build()

            response = app.baseClient.newCall(request).execute()
            if (!response.isSuccessful) return

            val code = response.code
            output.write("HTTP/1.1 $code ${response.message}\r\n".toByteArray())
            for ((k, v) in response.headers) {
                if (k.equals("transfer-encoding", true) ||
                    k.equals("content-encoding",  true) ||
                    k.equals("connection",         true)) continue
                output.write("$k: $v\r\n".toByteArray())
            }
            output.write("Connection: close\r\n\r\n".toByteArray())

            val inputStream = response.body.byteStream()

            // ─────────────────────────────────────────────────────────────────
            // FIX #1: AES/CTR SEEK YANG BENAR
            //
            // AES/CTR bekerja dengan "counter block":
            //   Block ke-N = Encrypt(IV + N)  → XOR dengan plaintext
            //
            // Untuk seek ke byte offset B:
            //   Block yang dibutuhkan = B / 16
            //   Byte dalam block      = B % 16
            //
            // Kita LANGSUNG set counter ke nilai yang tepat,
            // tanpa perlu loop decrypt dummy bytes yang lambat.
            // ─────────────────────────────────────────────────────────────────
            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)   // 32 bytes
            val secretKey = SecretKeySpec(keyBytes, "AES")

            // Hitung block offset dan byte-dalam-block
            val blockOffset   = reqStart / 16L
            val byteInBlock   = (reqStart % 16L).toInt()

            // Ambil IV awal (16 byte pertama dari key)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            // Increment IV sebagai big-endian integer → counter yang tepat
            val ivInt        = BigInteger(1, ivBytes)
            val newCounterInt = ivInt.add(BigInteger.valueOf(blockOffset))

            // Konversi kembali ke 16-byte array (big-endian, zero-padded)
            var newIvBytes = newCounterInt.toByteArray()
            newIvBytes = when {
                newIvBytes.size < 16 -> ByteArray(16 - newIvBytes.size) + newIvBytes
                newIvBytes.size > 16 -> newIvBytes.copyOfRange(newIvBytes.size - 16, newIvBytes.size)
                else                 -> newIvBytes
            }

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(newIvBytes))

            // Kalau reqStart tidak tepat di awal block, skip sisa byte di block pertama
            if (byteInBlock > 0) {
                cipher.update(ByteArray(byteInBlock))
            }

            // Stream data dengan dekripsi hanya pada 64KB pertama
            var offset = reqStart
            val buffer = ByteArray(32768)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (offset < 65536L) {
                    val n         = minOf(bytesRead.toLong(), 65536L - offset).toInt()
                    val decrypted = cipher.update(buffer, 0, n)
                    if (decrypted != null) output.write(decrypted)
                    if (n < bytesRead)    output.write(buffer, n, bytesRead - n)
                } else {
                    output.write(buffer, 0, bytesRead)
                }
                offset += bytesRead
                output.flush()
            }

        } catch (e: SocketException) {
            // Normal saat user seek / stop video
        } catch (e: Exception) {
            // Abaikan error streaming lainnya
        } finally {
            try { response?.close() } catch (_: Exception) {}
            try { client.close()    } catch (_: Exception) {}
        }
    }
}


// =============================================================================
// EXTRACTOR 1: ABYSS / HYDRAX
// =============================================================================
open class AbyssExtractor : ExtractorApi() {
    override val name         = "Abyss"
    override val mainUrl      = "https://abyssplayer.com"
    override val requiresReferer = true

    private fun baseHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
        "Referer"    to referer,
        "Origin"     to mainUrl,
        "Accept"     to "*/*"
    )

    // FIX #5: Override getUrl versi baru (dengan subtitleCallback & callback)
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
            val dataJson = mapper.readValue(decodedDatas, HydraxData::class.java)

            val infoSlug = dataJson.slug ?: slug
            val md5Id    = dataJson.md5_id  ?: return
            val userId   = dataJson.user_id ?: return
            val mediaStr = dataJson.media   ?: return

            val hashInput = "$userId:$infoSlug:$md5Id".toByteArray(Charsets.UTF_8)
            val keyHex    = MessageDigest.getInstance("MD5").digest(hashInput)
                .joinToString("") { "%02x".format(it) }

            val keyBytes  = keyHex.toByteArray(Charsets.UTF_8)
            val ivBytes   = keyBytes.copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )

            val decryptedBytes = cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1))
            val mediaJson      = mapper.readValue(
                String(decryptedBytes, Charsets.UTF_8),
                HydraxMedia::class.java
            )

            mediaJson.mp4?.sources?.forEach { src ->
                val label   = src.label ?: "Unknown"
                val path    = src.path  ?: return@forEach
                val baseUrl = src.url   ?: return@forEach
                if (path.isEmpty() || baseUrl.isEmpty()) return@forEach

                val srcUrl   = "$baseUrl/$path"
                val filename = path.substringAfterLast("/")
                val fnKeyHex = MessageDigest.getInstance("MD5")
                    .digest(filename.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }

                HydraxProxy.start()

                val encodedUrl    = android.util.Base64.encodeToString(
                    srcUrl.toByteArray(Charsets.UTF_8),
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                val localProxyUrl = "http://127.0.0.1:${HydraxProxy.port}/?url=$encodedUrl&key=$fnKeyHex"

                callback(
                    newExtractorLink(
                        source = name,
                        name   = "$name $label",
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
        if (vParam.isNotEmpty()) return vParam
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


// =============================================================================
// EXTRACTOR 2: TURBO VIP
// =============================================================================
open class Lk21TurboExtractor : ExtractorApi() {
    override var name         = "LK21 TurboVIP"
    override var mainUrl      = "https://turbovidhls.com"
    override val requiresReferer = false

    // FIX #5: Override getUrl versi baru
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty()) return

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Referer"    to "https://playeriframe.sbs/"
            )

            val html = app.get("$mainUrl/t/$id", headers = headers).text

            var m3u8Url = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8Url.isNullOrBlank()) {
                m3u8Url = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }
            if (m3u8Url.isNullOrBlank()) return

            val type = if (m3u8Url.endsWith(".mp4", ignoreCase = true))
                ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8

            callback(
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
    }
}


// =============================================================================
// EXTRACTOR 3: HOW NETWORK (P2P)
// =============================================================================
open class HowNetworkExtractor : ExtractorApi() {
    override var name         = "LK21 HowNetwork"
    override var mainUrl      = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

    // FIX #5: Override getUrl versi baru
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfter("id=").substringBefore("&")
            if (id.isEmpty()) return

            val response = app.post(
                url  = "$mainUrl/api2.php?id=$id",
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

            val parsedRes  = try { mapper.readValue(response, HowNetworkResponse::class.java) } catch (e: Exception) { null }
            val m3u8Url    = parsedRes?.file
            if (m3u8Url.isNullOrBlank()) return

            callback(
                newExtractorLink(
                    source = "LK21 HowNetwork",
                    name   = "HowNetwork HD",
                    url    = m3u8Url,
                    type   = ExtractorLinkType.M3U8
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
    }
}


// =============================================================================
// EXTRACTOR 4: CAST HD
// =============================================================================
open class CastExtractor : ExtractorApi() {
    override var name         = "CAST HD"
    override var mainUrl      = "https://weneverbeenfree.com"
    override val requiresReferer = false

    private fun b64url(b: ByteArray): String =
        android.util.Base64.encodeToString(
            b,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )

    private fun b64urlDecode(s: String): ByteArray {
        var std = s.replace("-", "+").replace("_", "/")
        val pad = 4 - (std.length % 4)
        if (pad != 4) std += "=".repeat(pad)
        return android.util.Base64.decode(std, android.util.Base64.DEFAULT)
    }

    private fun getRandomBytes(size: Int): ByteArray =
        ByteArray(size).apply { java.security.SecureRandom().nextBytes(this) }

    private fun derToRaw(der: ByteArray): ByteArray {
        var offset  = 2
        val rLength = der[offset + 1].toInt()
        val rOffset = offset + 2
        offset     += 2 + rLength
        val sLength = der[offset + 1].toInt()
        val sOffset = offset + 2

        val rStr = der.copyOfRange(rOffset, rOffset + rLength).dropWhile { it == 0.toByte() }.toByteArray()
        val sStr = der.copyOfRange(sOffset, sOffset + sLength).dropWhile { it == 0.toByte() }.toByteArray()

        val rPadded = ByteArray(32)
        val sPadded = ByteArray(32)
        System.arraycopy(rStr, 0, rPadded, 32 - rStr.size, rStr.size)
        System.arraycopy(sStr, 0, sPadded, 32 - sStr.size, sStr.size)
        return rPadded + sPadded
    }

    // FIX #5: Override getUrl versi baru
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast("/")

        val commonHeaders = mapOf(
            "User-Agent"      to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Origin"          to mainUrl,
            "Referer"         to url,
            "X-Embed-Origin"  to "playeriframe.sbs",
            "X-Embed-Parent"  to url,
            "X-Embed-Referer" to "https://playeriframe.sbs/"
        )

        try {
            val chalRes  = app.post("$mainUrl/api/videos/access/challenge", headers = commonHeaders)
            val chalJson = try { mapper.readValue(chalRes.text, CastChalResp::class.java) } catch (e: Exception) { null } ?: return

            val nonce = chalJson.nonce          ?: return
            val cid   = chalJson.challenge_id   ?: return

            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(kp.private)
            sig.update(nonce.toByteArray(Charsets.UTF_8))
            val rawSignature = derToRaw(sig.sign())

            val pub    = kp.public as ECPublicKey
            var xBytes = pub.w.affineX.toByteArray()
            var yBytes = pub.w.affineY.toByteArray()

            if (xBytes.size > 32) xBytes = xBytes.copyOfRange(xBytes.size - 32, xBytes.size)
            if (yBytes.size > 32) yBytes = yBytes.copyOfRange(yBytes.size - 32, yBytes.size)
            if (xBytes.size < 32) xBytes = ByteArray(32 - xBytes.size) + xBytes
            if (yBytes.size < 32) yBytes = ByteArray(32 - yBytes.size) + yBytes

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
            val attestJson = try { mapper.readValue(attestRes.text, CastAttestResp::class.java) } catch (e: Exception) { null } ?: return
            val token      = attestJson.token ?: return

            val pbPayload = mapOf("fingerprint" to mapOf("token" to token))
            val pbRes     = app.post("$mainUrl/api/videos/$videoId/embed/playback", headers = commonHeaders, json = pbPayload)
            val pbResp    = try { mapper.readValue(pbRes.text, CastPbResp::class.java)?.playback } catch (e: Exception) { null } ?: return

            val iv      = b64urlDecode(pbResp.iv      ?: return)
            val payload = b64urlDecode(pbResp.payload  ?: return)
            val keyParts = pbResp.key_parts            ?: return

            val keysToTest = mutableListOf<ByteArray>()
            val chunks16   = mutableListOf<ByteArray>()

            for (p in keyParts) {
                if (p.length == 32) keysToTest.add(p.toByteArray(Charsets.UTF_8))
                try {
                    val dec = b64urlDecode(p)
                    if (dec.size == 32)      keysToTest.add(dec)
                    else if (dec.size == 16) chunks16.add(dec)
                } catch (_: Exception) {}
            }

            for (i in chunks16.indices) {
                for (j in chunks16.indices) {
                    if (i != j) keysToTest.add(chunks16[i] + chunks16[j])
                }
            }

            for (keyBytes in keysToTest) {
                try {
                    val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
                    aesCipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keyBytes, "AES"),
                        GCMParameterSpec(128, iv)
                    )
                    val decrypted  = aesCipher.doFinal(payload)
                    val parsedData = mapper.readValue(String(decrypted, Charsets.UTF_8), CastDecrypted::class.java)
                    val realUrl    = parsedData?.sources?.firstOrNull()?.url ?: continue
                    val qualLabel  = parsedData.sources?.firstOrNull()?.label ?: "HD"

                    callback(
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
                                "User-Agent" to commonHeaders["User-Agent"]!!
                            )
                        }
                    )
                    break  // Berhasil, hentikan loop key
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
