package com.LayarKacaProvider

import android.util.Base64
import android.util.Log
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
// MESIN SERVER PROXY LOKAL (OBAT ANTI-CRONET & BYPASS LIMIT 512MB HYDRAX)
// =========================================================================
object HydraxProxy {
    var port: Int = 0
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    // Dispatcher khusus untuk membongkar limit koneksi OkHttp (Anti-antre saat seek)
    private val proxyClient by lazy {
        app.baseClient.newBuilder()
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .dispatcher(okhttp3.Dispatcher().apply { 
                maxRequests = 100
                maxRequestsPerHost = 100 
            })
            .build()
    }

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            isRunning = true
            thread {
                while (isRunning) {
                    try {
                        val client = serverSocket!!.accept()
                        thread { handleClient(client) }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e("HydraxProxy", "Gagal start server proxy: ${e.message}")
        }
    }

    private fun handleClient(client: Socket) {
        var response: Response? = null
        val clientId = System.currentTimeMillis().toString().takeLast(5)
        
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
            val params = query.split("&").associate { 
                val kv = it.split("=")
                kv[0] to (if (kv.size > 1) kv[1] else "")
            }
            
            val encodedUrl = params["url"] ?: return
            val keyHex = params["key"] ?: return
            val realUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE))

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val reqStart = rangeHeader?.replace("bytes=", "")?.split("-")?.get(0)?.toLongOrNull() ?: 0L
            Log.i("HydraxProxy", "[$clientId] [->] EXO MINTA RANGE : ${rangeHeader ?: "FULL (bytes=0-)"}")

            // ====================================================================
            // FORMULA INJEKSI SERVICE WORKER HYDRAX (PEMECAH CHUNK 512 MB)
            // ====================================================================
            val LIMIT_512MB = 536870912L // Persis 512 MB
            val partIndex = reqStart / LIMIT_512MB
            val localOffset = reqStart % LIMIT_512MB

            // Modifikasi URL asli untuk melompat ke Part yang tepat (contoh: url, url1, url2)
            val targetUrl = if (partIndex > 0) "$realUrl$partIndex" else realUrl

            // Request Range yang dikirim ke Hydrax harus direset sesuai offset part-nya
            val serverRangeHeader = "bytes=$localOffset-"
            // ====================================================================

            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://abyssplayer.com/")
                .header("Origin", "https://abyssplayer.com")
                .header("Accept-Encoding", "identity") // Wajib identity agar tidak di-gzip
                .header("Range", serverRangeHeader)
                .build()

            val startTime = System.currentTimeMillis()
            Log.d("HydraxProxy", "[$clientId] [*] Meneruskan request ke Hydrax Part $partIndex...")
            
            response = proxyClient.newCall(request).execute()
            val timeTaken = System.currentTimeMillis() - startTime
            
            val code = response.code

            // ====================================================================
            // MANIPULASI HEADER BALASAN UNTUK MEMBOHONGI EXOPLAYER (SPOOFING)
            // ====================================================================
            // FIX: Menggunakan Elvis Operator (?:) agar tipe terdeteksi tegas sebagai String Non-null
            var contentRange = response.header("Content-Range") ?: "Kosong"
            var contentLength = response.header("Content-Length") ?: "Kosong"
            var spoofedContentRange = contentRange

            if (code == 206 && contentRange != "Kosong") {
                try {
                    // Contoh format dari server: "bytes 0-536870911/536870912"
                    val rangeData = contentRange.replace("bytes ", "", ignoreCase = true).split("/")
                    if (rangeData.size == 2) {
                        val rangePositions = rangeData[0].split("-")
                        if (rangePositions.size == 2) {
                            val serverStart = rangePositions[0].toLong()
                            val serverEnd = rangePositions[1].toLong()
                            
                            // Kalkulasi ulang (Spoofing) dengan mengembalikan index ke skala aslinya
                            val spoofedStart = serverStart + (partIndex * LIMIT_512MB)
                            val spoofedEnd = serverEnd + (partIndex * LIMIT_512MB)
                            
                            // Gunakan * (wildcard) untuk total file agar ExoPlayer bisa meminta sisa file dengan dinamis
                            spoofedContentRange = "bytes $spoofedStart-$spoofedEnd/*" 
                        }
                    }
                } catch (e: Exception) {
                    Log.w("HydraxProxy", "[$clientId] Gagal memanipulasi Content-Range: ${e.message}")
                }
            }

            Log.i("HydraxProxy", "[$clientId] [<-] RESPON HYDRAX   : HTTP $code | Waktu: ${timeTaken}ms | C-Range: $spoofedContentRange | C-Length: $contentLength")

            // Tulis header HTTP balasan ke Player
            output.write("HTTP/1.1 $code ${response.message}\r\n".toByteArray())
            for ((key, value) in response.headers) {
                if (key.equals("transfer-encoding", true) || 
                    key.equals("content-encoding", true) || 
                    key.equals("connection", true) ||
                    key.equals("content-range", true)) continue // Abaikan content-range asli dari server
                output.write("$key: $value\r\n".toByteArray())
            }

            // Kirim Content-Range hasil spoofing jika status 206
            if (code == 206 && spoofedContentRange != "Kosong") {
                output.write("Content-Range: $spoofedContentRange\r\n".toByteArray())
            }

            output.write("Connection: close\r\n\r\n".toByteArray())

            // Wajib memanggil flush() di sini!
            output.flush()

            if (!response.isSuccessful) {
                Log.w("HydraxProxy", "[$clientId] [!] Hydrax Error $code. Header berhasil diteruskan ke ExoPlayer. Memutus body stream.")
                return
            }

            val body = response.body
            if (body == null) {
                Log.w("HydraxProxy", "[$clientId] [!] Body response dari Hydrax null!")
                return
            }
            val inputStream = body.byteStream()

            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(keyBytes.copyOfRange(0, 16))
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            // Menggunakan reqStart original karena cipher offset terikat dengan file utuh
            if (reqStart > 0 && reqStart < 65536) {
                cipher.update(ByteArray(reqStart.toInt()))
                Log.d("HydraxProxy", "[$clientId] [+] Cipher dimajukan sebanyak $reqStart byte")
            } else if (reqStart >= 65536) {
                Log.d("HydraxProxy", "[$clientId] [+] Bypass dekripsi karena offset >= 64KB ($reqStart)")
            }

            var offset = reqStart
            var totalSent = 0L
            val buffer = ByteArray(32768)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (offset < 65536) {
                    val n = minOf(bytesRead.toLong(), 65536L - offset).toInt()
                    val decrypted = cipher.update(buffer, 0, n)
                    if (decrypted != null) {
                        output.write(decrypted)
                    }
                    if (n < bytesRead) {
                        output.write(buffer, n, bytesRead - n)
                    }
                } else {
                    output.write(buffer, 0, bytesRead)
                }
                offset += bytesRead
                totalSent += bytesRead
                output.flush() 
            }
            val finalTime = (System.currentTimeMillis() - startTime - timeTaken) / 1000.0
            val kbps = if (finalTime > 0) (totalSent / 1024.0) / finalTime else 0.0
            Log.i("HydraxProxy", "[$clientId] [OK] Streaming Selesai. Total: $totalSent bytes | Speed: ${String.format("%.2f", kbps)} KB/s")

        } catch (e: SocketException) {
            Log.w("HydraxProxy", "[$clientId] [X] ExoPlayer memutus koneksi/Seek/Cancel: ${e.message ?: "Broken Pipe/Connection Reset"}")
        } catch (e: Exception) {
            Log.e("HydraxProxy", "[$clientId] [!] Error Stream putus di tengah jalan: ${e.message}")
        } finally {
            try { response?.close() } catch (e: Exception) {}
            try { client.close() } catch (e: Exception) {}
            Log.d("HydraxProxy", "[$clientId] [-] Socket ditutup & Memori dibersihkan.")
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
        var offset = 2
        val rLength = der[offset + 1].toInt()
        val rOffset = offset + 2
        offset += 2 + rLength
        val sLength = der[offset + 1].toInt()
        val sOffset = offset + 2
        
        val rStr = der.copyOfRange(rOffset, rOffset + rLength).dropWhile { it == 0.toByte() }.toByteArray()
        val sStr = der.copyOfRange(sOffset, sOffset + sLength).dropWhile { it == 0.toByte() }.toByteArray()
        
        val rPadded = ByteArray(32) { 0 }
        val sPadded = ByteArray(32) { 0 }
    
        System.arraycopy(rStr, 0, rPadded, 32 - rStr.size, rStr.size)
        System.arraycopy(sStr, 0, sPadded, 32 - sStr.size, sStr.size)
        
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
