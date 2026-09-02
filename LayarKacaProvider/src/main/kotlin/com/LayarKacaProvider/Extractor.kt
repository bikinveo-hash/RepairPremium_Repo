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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.interfaces.ECPublicKey
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

// Penggunaan jsonMapper unik khusus ekstraktor agar tidak tabrakan dengan classpath global
private val jsonMapper: ObjectMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// =========================================================================
// DATA CLASSES UNTUK TYPE-SAFE JSON PARSING
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
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("size") val size: Long? = null
)

data class HowNetworkResponse(
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("file") val file: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("title") val title: String?
)

data class PlayCdnPlayerData(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("token") val token: String? = null
)

data class PlayCdnVerifyResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("fileUrl") val fileUrl: String? = null,
    @JsonProperty("message") val message: String? = null
)

data class PlayCdnVerifyPayload(
    @JsonProperty("token") val token: String,
    @JsonProperty("is_ios") val isIos: Boolean
)

data class CastChalResp(
    @JsonProperty("nonce") val nonce: String?,
    @JsonProperty("challenge_id") val challenge_id: String?
)
data class CastAttestResp(
    @JsonProperty("viewer_id") val viewer_id: String?,
    @JsonProperty("device_id") val device_id: String?,
    @JsonProperty("token") val token: String?
)
data class CastCaptchaResp(
    @JsonProperty("pow_token") val pow_token: String?,
    @JsonProperty("pow_nonce") val pow_nonce: String?,
    @JsonProperty("pow_difficulty") val pow_difficulty: Int?
)
data class CastVerifyResp(
    @JsonProperty("token") val token: String?,
    @JsonProperty("captcha_token") val captcha_token: String?
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
    @Volatile private var isRunning = false
    private var serverSocket: ServerSocket? = null

    private data class PartMap(
        val partSizes: List<Long>,
        val cumulativeStarts: List<Long>,
        val virtualTotalSize: Long
    )

    private val partMapCache = ConcurrentHashMap<String, PartMap>()
    private const val MAX_DISCOVERY_PARTS = 32

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

    fun sourceId(realUrl: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(realUrl.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }

    private fun physicalPartUrl(realUrl: String, partIndex: Int): String =
        if (partIndex == 0) realUrl else "$realUrl$partIndex"

    private fun discoverPartMap(
        realUrl: String,
        expectedVirtualSize: Long?,
        sourceId: String
    ): PartMap? {
        val sizes = mutableListOf<Long>()
        var total = 0L

        for (partIndex in 0 until MAX_DISCOVERY_PARTS) {
            val probe = Request.Builder()
                .url(physicalPartUrl(realUrl, partIndex))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://abyssplayer.com/")
                .header("Origin", "https://abyssplayer.com")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0")
                .build()

            val partSize = try {
                proxyClient.newCall(probe).execute().use { probeResponse ->
                    if (probeResponse.code !in 200..299) {
                        if (expectedVirtualSize == null && sizes.isNotEmpty() &&
                            probeResponse.code in setOf(404, 416)
                        ) {
                            null
                        } else {
                            Log.e(
                                "HydraxProxy",
                                "sourceMapFailed sourceId=$sourceId part=$partIndex " +
                                    "status=${probeResponse.code}"
                            )
                            return null
                        }
                    } else {
                        val physicalRange = probeResponse.header("Content-Range").orEmpty()
                        Regex("(?i)^bytes\\s+\\d+-\\d+/(\\d+)$")
                            .find(physicalRange)?.groupValues?.getOrNull(1)?.toLongOrNull()
                            ?: if (probeResponse.code == 200) {
                                probeResponse.header("Content-Length")?.toLongOrNull()
                            } else {
                                null
                            }
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    "HydraxProxy",
                    "sourceMapFailed sourceId=$sourceId part=$partIndex " +
                        "error=${e.javaClass.simpleName}"
                )
                return null
            }

            if (partSize == null) {
                if (expectedVirtualSize == null && sizes.isNotEmpty()) break
                Log.e("HydraxProxy", "sourceMapFailed sourceId=$sourceId reason=part_size_missing")
                return null
            }
            if (partSize <= 0L) {
                Log.e("HydraxProxy", "sourceMapFailed sourceId=$sourceId reason=invalid_part_size")
                return null
            }

            sizes.add(partSize)
            total += partSize

            if (expectedVirtualSize != null) {
                when {
                    total == expectedVirtualSize -> break
                    total > expectedVirtualSize -> {
                        Log.e(
                            "HydraxProxy",
                            "sourceMapFailed sourceId=$sourceId reason=parts_exceed_metadata " +
                                "sum=$total expected=$expectedVirtualSize"
                        )
                        return null
                    }
                }
            }
        }

        if (sizes.isEmpty()) return null
        if (expectedVirtualSize != null && total != expectedVirtualSize) {
            Log.e(
                "HydraxProxy",
                "sourceMapFailed sourceId=$sourceId reason=metadata_not_reached " +
                    "sum=$total expected=$expectedVirtualSize"
            )
            return null
        }

        val virtualTotal = expectedVirtualSize ?: total
        val starts = ArrayList<Long>(sizes.size)
        var cumulative = 0L
        sizes.forEach { size ->
            starts.add(cumulative)
            cumulative += size
        }
        val map = PartMap(sizes.toList(), starts, virtualTotal)
        Log.i(
            "HydraxProxy",
            "sourceMap sourceId=$sourceId virtualTotal=${map.virtualTotalSize} " +
                "partCount=${map.partSizes.size} partSizes=${map.partSizes}"
        )
        return map
    }

    private fun getPartMap(
        realUrl: String,
        expectedVirtualSize: Long?,
        sourceId: String
    ): PartMap? {
        partMapCache[realUrl]?.let { return it }
        return synchronized(partMapCache) {
            partMapCache[realUrl] ?: discoverPartMap(
                realUrl,
                expectedVirtualSize,
                sourceId
            )?.also { partMapCache[realUrl] = it }
        }
    }

    private fun writeRangeNotSatisfiable(
        output: java.io.OutputStream,
        virtualTotalSize: Long
    ) {
        output.write("HTTP/1.1 416 Range Not Satisfiable\r\n".toByteArray())
        output.write("Content-Range: bytes */$virtualTotalSize\r\n".toByteArray())
        output.write("Content-Length: 0\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.flush()
    }

    fun start() {
        Log.i("HydraxProxy", "H3_DYNAMIC_MULTIPART_BUILD=20260901_H3_V1")
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
                    } catch (e: Exception) {
                        if (isRunning) Log.w("HydraxProxy", "Accept error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HydraxProxy", "Gagal start server proxy: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        port = 0
        Log.i("HydraxProxy", "Proxy server dihentikan.")
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
            val params = query.split("&").associate { pair ->
                val eqIdx = pair.indexOf('=')
                if (eqIdx < 0) pair to ""
                else pair.substring(0, eqIdx) to pair.substring(eqIdx + 1)
            }

            val encodedUrl = params["url"] ?: return
            val keyHex = params["key"] ?: return
            val realUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE))
            val expectedVirtualSize = params["size"]?.toLongOrNull()?.takeIf { it > 0L }
            val sourceId = params["sid"]?.takeIf { it.matches(Regex("[a-fA-F0-9]{12}")) }
                ?: HydraxProxy.sourceId(realUrl)

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val requestedRange = rangeHeader
                ?.removePrefix("bytes=")
                ?.substringBefore(",")
                ?.split("-", limit = 2)
            val reqStart = requestedRange?.getOrNull(0)?.toLongOrNull() ?: 0L
            val reqEnd = requestedRange?.getOrNull(1)?.toLongOrNull()
            Log.i("HydraxProxy", "[$clientId] [->] EXO MINTA RANGE : ${rangeHeader ?: "FULL (bytes=0-)"}")

            val partMap = getPartMap(realUrl, expectedVirtualSize, sourceId)
            if (partMap == null) {
                Log.e("HydraxProxy", "[$clientId] sourceMapUnavailable sourceId=$sourceId")
                output.write("HTTP/1.1 503 Service Unavailable\r\n".toByteArray())
                output.write("Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            if (reqStart < 0L || reqStart >= partMap.virtualTotalSize ||
                (reqEnd != null && reqEnd < reqStart)
            ) {
                Log.w(
                    "HydraxProxy",
                    "[$clientId] invalidGlobalRange sourceId=$sourceId globalStart=$reqStart " +
                        "virtualTotal=${partMap.virtualTotalSize}"
                )
                writeRangeNotSatisfiable(output, partMap.virtualTotalSize)
                return
            }

            val partIndex = partMap.cumulativeStarts.indices.firstOrNull { index ->
                val start = partMap.cumulativeStarts[index]
                reqStart >= start && reqStart < start + partMap.partSizes[index]
            }
            if (partIndex == null) {
                Log.e("HydraxProxy", "[$clientId] sourceMapGap sourceId=$sourceId globalStart=$reqStart")
                writeRangeNotSatisfiable(output, partMap.virtualTotalSize)
                return
            }

            val partStart = partMap.cumulativeStarts[partIndex]
            val partSize = partMap.partSizes[partIndex]
            val localOffset = reqStart - partStart
            val requestedLocalEnd = reqEnd?.minus(partStart)
            val localEnd = minOf(partSize - 1L, requestedLocalEnd ?: (partSize - 1L))

            val targetUrl = physicalPartUrl(realUrl, partIndex)
            val serverRangeHeader = "bytes=$localOffset-$localEnd"
            Log.i(
                "HydraxProxy",
                "[$clientId] H3_MAP globalStart=$reqStart sourceId=$sourceId " +
                    "mappedPart=$partIndex partStart=$partStart localOffset=$localOffset " +
                    "virtualTotal=${partMap.virtualTotalSize}"
            )

            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://abyssplayer.com/")
                .header("Origin", "https://abyssplayer.com")
                .header("Accept-Encoding", "identity")
                .header("Range", serverRangeHeader)
                .build()

            val startTime = System.currentTimeMillis()
            Log.d(
                "HydraxProxy",
                "[$clientId] upstreamRequest sourceId=$sourceId physicalRange=$serverRangeHeader"
            )

            response = proxyClient.newCall(request).execute()
            val timeTaken = System.currentTimeMillis() - startTime

            val code = response.code
            val contentRange = response.header("Content-Range") ?: "Kosong"
            val contentLength = response.header("Content-Length") ?: "Kosong"
            var translatedContentRange: String? = null

            if (code == 206 && contentRange != "Kosong") {
                try {
                    val match = Regex("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+)$").find(contentRange)
                    val physicalStart = match?.groupValues?.getOrNull(1)?.toLongOrNull()
                    val physicalEnd = match?.groupValues?.getOrNull(2)?.toLongOrNull()
                    if (physicalStart != null && physicalEnd != null) {
                        val globalStart = partStart + physicalStart
                        val globalEnd = partStart + physicalEnd
                        translatedContentRange =
                            "bytes $globalStart-$globalEnd/${partMap.virtualTotalSize}"
                    }
                } catch (e: Exception) {
                    Log.w("HydraxProxy", "[$clientId] contentRangeTranslateFailed: ${e.message}")
                }
            }

            Log.i(
                "HydraxProxy",
                "[$clientId] upstreamStatus=$code sourceId=$sourceId " +
                    "physicalRange=$contentRange virtualRange=${translatedContentRange ?: "NONE"} " +
                    "virtualTotal=${partMap.virtualTotalSize} timeMs=$timeTaken contentLength=$contentLength"
            )

            output.write("HTTP/1.1 $code ${response.message}\r\n".toByteArray())
            for ((key, value) in response.headers) {
                if (key.equals("transfer-encoding", true) ||
                    key.equals("content-encoding", true) ||
                    key.equals("connection", true) ||
                    key.equals("content-range", true)) continue
                output.write("$key: $value\r\n".toByteArray())
            }

            if (code == 206 && translatedContentRange != null) {
                output.write("Content-Range: $translatedContentRange\r\n".toByteArray())
            }

            output.write("Connection: close\r\n\r\n".toByteArray())
            output.flush()

            if (!response.isSuccessful) {
                Log.w("HydraxProxy", "[$clientId] [!] Hydrax Error $code. Memutus body stream.")
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
                    if (decrypted != null) output.write(decrypted)
                    if (n < bytesRead) output.write(buffer, n, bytesRead - n)
                } else {
                    output.write(buffer, 0, bytesRead)
                }
                offset += bytesRead
                totalSent += bytesRead
                output.flush()
            }
            val finalTime = (System.currentTimeMillis() - startTime - timeTaken) / 1000.0
            val kbps = if (finalTime > 0) (totalSent / 1024.0) / finalTime else 0.0
            
            // FIX: Mengembalikan variabel log ke referensi yang benar (clientId) untuk menghindari Unresolved Reference
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
// EXTRACTOR 1: ABYSS / HYDRAX (TRANSPARENT LOCAL PROXY) -> 100% UTUH
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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageRef = referer ?: "$mainUrl/"
        val slug = extractSlugFromUrl(url) ?: return
        val hdrs = baseHeaders(pageRef)

        try {
            val html = app.get("$mainUrl/?v=$slug", headers = hdrs).text
            val datas = Regex("""datas\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return

            val decodedDatas = String(Base64.decode(datas, Base64.DEFAULT), Charsets.ISO_8859_1)
            val dataJson = jsonMapper.readValue(decodedDatas, HydraxData::class.java)

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
            val mediaJson = jsonMapper.readValue(String(decryptedBytes, Charsets.UTF_8), HydraxMedia::class.java)

            val mp4Sources = mediaJson.mp4?.sources

            mp4Sources?.forEach { src ->
                val label = src.label ?: "Unknown"
                val path = src.path ?: ""
                val baseUrl = src.url ?: ""

                if (path.isNotEmpty() && baseUrl.isNotEmpty()) {
                    val srcUrl = "$baseUrl/$path"

                    val filename = path.substringAfterLast("/")
                    val fnHash = MessageDigest.getInstance("MD5").digest(filename.toByteArray(Charsets.UTF_8))
                    val fnKeyHex = fnHash.joinToString("") { "%02x".format(it) }
                    val sourceId = HydraxProxy.sourceId(srcUrl)

                    HydraxProxy.start()

                    val encodedUrl = Base64.encodeToString(srcUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                    val sizeParam = src.size?.takeIf { it > 0L }?.toString().orEmpty()
                    val localProxyUrl =
                        "http://127.0.0.1:${HydraxProxy.port}/?url=$encodedUrl" +
                            "&key=$fnKeyHex&size=$sizeParam&sid=$sourceId"
                    Log.i(
                        "HydraxProxy",
                        "H3_SOURCE_REGISTER label=$label codec=${src.codec.orEmpty()} " +
                            "virtualTotal=${src.size ?: "UNKNOWN"} sourceId=$sourceId"
                    )

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
        label.contains("720")  -> Qualities.P720.value
        label.contains("480")  -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }
}

// =========================================================================
// EXTRACTOR 2: TURBO VIP -> 100% UTUH
// =========================================================================
open class Lk21TurboExtractor : ExtractorApi() {
    override var name    = "LK21 TurboVIP"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = false

    companion object {
        private const val DEBUG_TAG = "LAYARKACA_DEBUG"
        private const val TURBO_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
    }

    private fun originOf(value: String): String? = try {
        val uri = URI(value)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) null
        else "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    } catch (_: Exception) {
        null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val id = url.substringAfter("/t/").substringBefore("?")
            if (id.isEmpty() || !url.contains("/t/")) {
                Log.w(DEBUG_TAG, "extractor=TurboVIP stop=invalid_player_url url=$url")
                return
            }

            // Untuk jalur videonode, `url` adalah iframe aktual dari wrapper
            // (emturbovid atau turbovidhls), bukan ID opaque pada data-url.
            val playerUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                "$mainUrl/t/$id"
            }
            val wrapperReferer = referer ?: "https://playeriframe.sbs/"

            val pageHeaders = mutableMapOf(
                "User-Agent" to TURBO_UA,
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
                "Referer" to wrapperReferer
            ).apply {
                originOf(wrapperReferer)?.let { put("Origin", it) }
            }

            Log.d(DEBUG_TAG, "extractor=TurboVIP request player=$playerUrl referer=$wrapperReferer")
            val playerResponse = app.get(playerUrl, headers = pageHeaders)
            val finalPlayerUrl = playerResponse.url
            val html = playerResponse.text
            Log.d(
                DEBUG_TAG,
                "extractor=TurboVIP status=${playerResponse.code} requested=$playerUrl final=$finalPlayerUrl"
            )

            var m3u8Url = Regex("""data-hash="([^"]+)"""").find(html)?.groupValues?.get(1)
            if (m3u8Url.isNullOrBlank()) {
                m3u8Url = Regex("""urlPlay\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            }
            if (m3u8Url.isNullOrBlank()) {
                Log.w(DEBUG_TAG, "extractor=TurboVIP stop=media_not_found final=$finalPlayerUrl")
                return
            }

            Log.d(DEBUG_TAG, "extractor=TurboVIP media=$m3u8Url player=$finalPlayerUrl")

            val type = if (m3u8Url.substringBefore("?").endsWith(".mp4", ignoreCase = true)) {
                ExtractorLinkType.VIDEO
            } else {
                ExtractorLinkType.M3U8
            }

            callback(
                newExtractorLink(
                    source = "LK21 TurboVIP",
                    name   = "TurboVIP HD",
                    url    = m3u8Url,
                    type   = type
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
            Log.d(
                DEBUG_TAG,
                "extractor=TurboVIP callback media=$m3u8Url type=$type mediaHeaders=none"
            )
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "extractor=TurboVIP exception url=$url", e)
        }
    }
}

// =========================================================================
// EXTRACTOR 3: PLAYCDN P2P
// =========================================================================
open class PlayCdnP2PExtractor : ExtractorApi() {
    override var name = "LK21 PlayCDN P2P"
    override var mainUrl = "https://playcdn.de"
    override val requiresReferer = false

    companion object {
        private const val DEBUG_TAG = "LAYARKACA_DEBUG"
        private const val PLAYCDN_ORIGIN = "https://playcdn.de"
        private const val PLAYCDN_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }

    private class SessionCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(this.cookies) {
                cookies.forEach { incoming ->
                    this.cookies.removeAll {
                        it.name == incoming.name &&
                            it.domain == incoming.domain &&
                            it.path == incoming.path
                    }
                    if (incoming.expiresAt > System.currentTimeMillis()) {
                        this.cookies.add(incoming)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return synchronized(cookies) {
                cookies.removeAll { it.expiresAt <= now }
                cookies.filter { it.matches(url) }
            }
        }
    }

    private fun requestBuilder(url: String, referer: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", PLAYCDN_UA)
        .header("Accept", "*/*")
        .header("Origin", PLAYCDN_ORIGIN)
        .header("Referer", referer)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cookieJar = SessionCookieJar()
            val client = app.baseClient.newBuilder()
                .cookieJar(cookieJar)
                .followRedirects(true)
                .build()

            val pageRequest = Request.Builder()
                .url(url)
                .header("User-Agent", PLAYCDN_UA)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .apply { referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) } }
                .build()

            val (playerUrl, playerHtml) = client.newCall(pageRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("player HTTP ${response.code}")
                }
                response.request.url.toString() to response.body?.string().orEmpty()
            }

            val dataJson = Regex(
                """\bvar\s+data\s*=\s*(\{.*?\})\s*;""",
                RegexOption.DOT_MATCHES_ALL
            )
                .find(playerHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: throw IllegalStateException("player data object tidak ditemukan")
            val playerData = jsonMapper.readValue(dataJson, PlayCdnPlayerData::class.java)
            val id = playerData.id?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("data.id kosong")
            val token = playerData.token?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("data.token kosong")
            Log.d(
                DEBUG_TAG,
                "extractor=PlayCDN player id=$id playerHost=" +
                    runCatching { URI(playerUrl).host }.getOrNull() +
                    " tokenLength=${token.length}"
            )

            val payload = PlayCdnVerifyPayload(
                token = token,
                isIos = false
            )

            val verifyBody = jsonMapper.writeValueAsString(payload)
                .toRequestBody("application/json".toMediaType())
            val verifyRequest = requestBuilder("$mainUrl/verify.php", playerUrl)
                .post(verifyBody)
                .build()
            val (verifyHttpStatus, verify) = client.newCall(verifyRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("verify HTTP ${response.code}")
                }
                response.code to jsonMapper.readValue(
                    response.body?.string().orEmpty(), PlayCdnVerifyResponse::class.java
                )
            }
            if (!verify.status.equals("success", ignoreCase = true)) {
                throw IllegalStateException(
                    "verify status=${verify.status} message=${verify.message.orEmpty()}"
                )
            }
            val mediaUrl = verify.fileUrl?.takeIf {
                it.startsWith("https://") || it.startsWith("http://")
            } ?: throw IllegalStateException("fileUrl absolut tidak ditemukan")
            val mediaType = if (
                mediaUrl.substringBefore("?").endsWith(".mp4", ignoreCase = true)
            ) {
                ExtractorLinkType.VIDEO
            } else {
                ExtractorLinkType.M3U8
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = "PlayCDN P2P",
                    url = mediaUrl,
                    type = mediaType
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
            Log.d(
                DEBUG_TAG,
                "extractor=PlayCDN verify status=${verify.status} http=$verifyHttpStatus " +
                    "id=$id type=$mediaType mediaHost=" +
                    runCatching { URI(mediaUrl).host }.getOrNull()
            )
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "extractor=PlayCDN failed player=$url", e)
        }
    }
}

// =========================================================================
// EXTRACTOR 4: HOW NETWORK -> 100% UTUH
// =========================================================================
open class HowNetworkExtractor : ExtractorApi() {
    override var name    = "LK21 HowNetwork"
    override var mainUrl = "https://cloud.hownetwork.xyz"
    override val requiresReferer = false

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

            val parsedRes = try { jsonMapper.readValue(response, HowNetworkResponse::class.java) } catch (e: Exception) { null }
            val m3u8Url = parsedRes?.file

            if (!m3u8Url.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = "LK21 HowNetwork",
                        name   = "HowNetwork HD",
                        url    = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin"  to mainUrl,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// =========================================================================
// EXTRACTOR 4: CAST HD (YANG SUKSES JEBOL VERIFIKASI SELESAI SINKRONISASI)
// =========================================================================
open class CastExtractor : ExtractorApi() {
    override var name    = "CAST HD"
    override var mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = false

    companion object {
        private const val DEBUG_TAG = "LAYARKACA_DEBUG"
        private const val CAST_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
        private const val CAST_MEDIA_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    }

    private fun re(t: Long, e: Int): Long = ((t shl e) or (t ushr (32 - e))) and 0xFFFFFFFFL
    private fun ht(t: Long, e: Long): Long = (t * e) and 0xFFFFFFFFL

    private fun ye(t: LongArray) {
        t[0] = (t[0] + t[1]) and 0xFFFFFFFFL
        t[3] = re(t[3] xor t[0], 16)
        t[2] = (t[2] + t[3]) and 0xFFFFFFFFL
        t[1] = re(t[1] xor t[2], 12)
        t[0] = (t[0] + t[1]) and 0xFFFFFFFFL
        t[3] = re(t[3] xor t[0], 8)
        t[2] = (t[2] + t[3]) and 0xFFFFFFFFL
        t[1] = re(t[1] xor t[2], 7)
    }

    private fun gr(t: ByteArray): LongArray {
        val e = longArrayOf(1779033703L, 3144134277L, 1013904242L, 2773480762L)
        for (i in t.indices) {
            e[0] = (e[0] + (t[i].toLong() and 0xFFL)) and 0xFFFFFFFFL
            e[0] = re(e[0], 7)
            ye(e)
        }
        for (i in 0 until 8) { ye(e) }
        val r = LongArray(512)
        for (i in 0 until 512) {
            ye(e)
            r[i] = (e[0] xor e[2]) and 0xFFFFFFFFL
        }
        for (i in 0 until 2) {
            for (s in 0 until 512) {
                val a = (r[s] and 511L).toInt()
                var c = (r[s] + r[a]) and 0xFFFFFFFFL
                c = re(c, 13)
                c = (c xor ht(r[(s + 1) and 511], 2654435761L)) and 0xFFFFFFFFL
                r[s] = c
                e[0] = (e[0] xor c) and 0xFFFFFFFFL
                ye(e)
            }
        }
        val n = LongArray(8)
        for (i in 0 until 8) {
            ye(e)
            var sVal = e[0]
            val a = i * 64
            for (c in 0 until 64) {
                val d = r[a + c]
                sVal = (sVal + d) and 0xFFFFFFFFL
                sVal = re(sVal, 5)
                sVal = (sVal xor ht(d, 2246822519L)) and 0xFFFFFFFFL
            }
            n[i] = (sVal xor e[2]) and 0xFFFFFFFFL
        }
        return n
    }

    private fun wr(t: LongArray): Int {
        var e = 0
        for (n in t) {
            val nM = n and 0xFFFFFFFFL
            if (nM == 0L) { e += 32; continue }
            return e + java.lang.Integer.numberOfLeadingZeros(nM.toInt())
        }
        return e
    }

    private fun solvePow(powToken: String?, powNonce: String?, difficulty: Int): String? {
        if (difficulty <= 0) return "0"
        val keys = listOfNotNull(powNonce, powToken)
        for (baseKey in keys) {
            val prefix = "$baseKey:"
            var solution = 0L
            val startTime = System.currentTimeMillis()
            while (true) {
                val candidate = "$prefix$solution"
                if (wr(gr(candidate.toByteArray(Charsets.UTF_8))) >= difficulty) {
                    return solution.toString()
                }
                solution++
                if (solution % 2048 == 0L && (System.currentTimeMillis() - startTime) > 15000L) {
                    break
                }
            }
        }
        return null
    }

    private fun b64url(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun b64urlDecode(s: String): ByteArray {
        var std = s.replace("-", "+").replace("_", "/")
        val pad = 4 - (std.length % 4)
        if (pad != 4) std += "=".repeat(pad)
        return Base64.decode(std, Base64.DEFAULT)
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var idx = 2
        val rLen = der[idx + 1].toInt()
        val rOff = idx + 2
        idx += 2 + rLen
        val sLen = der[idx + 1].toInt()
        val sOff = idx + 2
        val rStr = der.copyOfRange(rOff, rOff + rLen).dropWhile { it == 0.toByte() }.toByteArray()
        val sStr = der.copyOfRange(sOff, sOff + sLen).dropWhile { it == 0.toByte() }.toByteArray()
        val raw = ByteArray(64) { 0 }
        System.arraycopy(rStr, 0, raw, 32 - rStr.size, rStr.size)
        System.arraycopy(sStr, 0, raw, 64 - sStr.size, sStr.size)
        return raw
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerUri = try {
            URI(url)
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "extractor=Cast invalidPlayerUrl url=$url", e)
            return
        }
        val playerOrigin = if (
            !playerUri.scheme.isNullOrBlank() && !playerUri.host.isNullOrBlank()
        ) {
            "${playerUri.scheme}://${playerUri.host}" +
                if (playerUri.port > 0) ":${playerUri.port}" else ""
        } else {
            Log.e(DEBUG_TAG, "extractor=Cast missingPlayerOrigin url=$url")
            return
        }
        val videoId = playerUri.path?.trimEnd('/')?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() } ?: return
        val isCurrentGn1r5n = playerUri.host.equals("gn1r5n.org", ignoreCase = true) ||
            playerUri.host?.endsWith(".gn1r5n.org", ignoreCase = true) == true

        val commonHeaders = mapOf(
            "User-Agent"       to CAST_UA,
            "Origin"           to playerOrigin,
            "Referer"          to url,
            "X-Embed-Origin"   to if (isCurrentGn1r5n) "videonode.de" else "playeriframe.sbs",
            "X-Embed-Parent"   to url,
            "X-Embed-Referer"  to if (isCurrentGn1r5n) "https://videonode.de/" else "https://playeriframe.sbs/"
        )

        try {
            Log.d(DEBUG_TAG, "extractor=Cast called player=$url origin=$playerOrigin videoId=$videoId")
            val detailsRes = app.get("$playerOrigin/api/videos/$videoId/embed/details", headers = commonHeaders)
            val settingsRes = app.get("$playerOrigin/api/videos/$videoId/embed/settings", headers = commonHeaders)
            Log.d(DEBUG_TAG, "extractor=Cast detailsStatus=${detailsRes.code} settingsStatus=${settingsRes.code}")

            val chalRes  = app.post("$playerOrigin/api/videos/access/challenge", headers = commonHeaders)
            Log.d(DEBUG_TAG, "extractor=Cast challengeStatus=${chalRes.code}")
            val chalJson = jsonMapper.readValue(chalRes.text, CastChalResp::class.java)

            val nonce = chalJson.nonce ?: return
            val cid   = chalJson.challenge_id ?: return

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
                "viewer_id"   to "",
                "device_id"   to "",
                "challenge_id" to cid,
                "nonce"       to nonce,
                "signature"   to b64url(rawSignature),
                "public_key"  to mapOf(
                    "crv" to "P-256", "ext" to true, "key_ops" to listOf("verify"), "kty" to "EC",
                    "x" to b64url(xBytes), "y" to b64url(yBytes)
                ),
                "client"     to mapOf("user_agent" to commonHeaders["User-Agent"]!!),
                "attributes" to mapOf("entropy" to "high")
            )

            val attestRes  = app.post("$playerOrigin/api/videos/access/attest", headers = commonHeaders, json = attestPayload)
            Log.d(DEBUG_TAG, "extractor=Cast attestStatus=${attestRes.code}")
            val attestJson = jsonMapper.readValue(attestRes.text, CastAttestResp::class.java)
            val sViewerId  = attestJson.viewer_id ?: return
            val sDeviceId  = attestJson.device_id ?: return
            val token      = attestJson.token ?: return

            val fingerprintObj = mapOf(
                "token"      to token,
                "viewer_id"  to sViewerId,
                "device_id"  to sDeviceId,
                "confidence" to 0.9
            )

            val captchaPayload = mapOf("fingerprint" to fingerprintObj)
            val captchaRes     = app.post("$playerOrigin/api/videos/$videoId/embed/captcha", headers = commonHeaders, json = captchaPayload)
            Log.d(DEBUG_TAG, "extractor=Cast captchaStatus=${captchaRes.code}")
            val captchaJson    = jsonMapper.readValue(captchaRes.text, CastCaptchaResp::class.java)

            val powToken   = captchaJson.pow_token ?: return
            val powNonce   = captchaJson.pow_nonce
            val difficulty = captchaJson.pow_difficulty ?: 8

            val solution = solvePow(powToken, powNonce, difficulty) ?: return
            Log.d(DEBUG_TAG, "extractor=Cast powSolved=true difficulty=$difficulty")

            val verifyPayload = mapOf(
                "pow_token"   to powToken,
                "solution"    to solution,
                "fingerprint" to fingerprintObj
            )
            val verifyRes = app.post("$playerOrigin/api/videos/$videoId/embed/captcha/verify", headers = commonHeaders, json = verifyPayload)
            Log.d(DEBUG_TAG, "extractor=Cast captchaVerifyStatus=${verifyRes.code}")
            val verifyJson = jsonMapper.readValue(verifyRes.text, CastVerifyResp::class.java)
            val finalCaptchaToken = verifyJson.captcha_token ?: verifyJson.token ?: return

            val pbPayload = mapOf("fingerprint" to fingerprintObj)
            val playbackHeaders = commonHeaders.toMutableMap().apply {
                put("x-captcha-token", finalCaptchaToken)
                put("Cookie", "byse_viewer_id=$sViewerId; byse_device_id=$sDeviceId")
            }

            val pbRes     = app.post("$playerOrigin/api/videos/$videoId/embed/playback", headers = playbackHeaders, json = pbPayload)
            Log.d(DEBUG_TAG, "extractor=Cast playbackStatus=${pbRes.code}")
            val pbResp   = jsonMapper.readValue(pbRes.text, CastPbResp::class.java)?.playback ?: return
            val iv       = b64urlDecode(pbResp.iv ?: return)
            val payload  = b64urlDecode(pbResp.payload ?: return)
            val keyParts = pbResp.key_parts ?: return

            val keysToTest = mutableListOf<ByteArray>()
            val chunks16   = mutableListOf<ByteArray>()

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
                    val aes     = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec    = GCMParameterSpec(128, iv)
                    val secKey  = SecretKeySpec(keyBytes, "AES")
                    aes.init(Cipher.DECRYPT_MODE, secKey, spec)
                    val decrypted  = aes.doFinal(payload)
                    val jsonString = String(decrypted, Charsets.UTF_8)
                    val parsedData = jsonMapper.readValue(jsonString, CastDecrypted::class.java)

                    realUrl      = parsedData?.sources?.firstOrNull()?.url
                    qualityLabel = parsedData?.sources?.firstOrNull()?.label ?: "HD"

                    if (realUrl != null) break
                } catch (e: Exception) {}
            }

            val finalMediaUrl = realUrl
            if (finalMediaUrl != null) {
                Log.d(
                    DEBUG_TAG,
                    "extractor=Cast aesDecryptSuccess=true mediaHost=" +
                        runCatching { URI(finalMediaUrl).host }.getOrNull()
                )

                val mediaHeaders = mapOf(
                    "Referer" to "$playerOrigin/",
                    "User-Agent" to CAST_MEDIA_UA
                )
                val normalVariants = mutableListOf<Pair<String, String>>()
                var hlsMasterStatus = "FETCH_FAILED"
                var iframeVariantCount = 0

                try {
                    val masterRes = app.get(finalMediaUrl, headers = mediaHeaders)
                    hlsMasterStatus = masterRes.code.toString()
                    val masterText = masterRes.text
                    val lines = masterText.lines().map { it.trim() }
                    val validMaster = masterRes.code in 200..299 &&
                        masterText.trimStart().startsWith("#EXTM3U")

                    iframeVariantCount = lines.count {
                        it.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    }

                    if (validMaster) {
                        lines.forEachIndexed { index, line ->
                            if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed

                            val childUri = lines.drop(index + 1).firstOrNull {
                                it.isNotBlank() && !it.startsWith("#")
                            } ?: return@forEachIndexed
                            val absoluteChild = runCatching {
                                URI(finalMediaUrl).resolve(childUri).toString()
                            }.getOrNull() ?: return@forEachIndexed
                            val height = Regex("RESOLUTION=\\d+x(\\d+)")
                                .find(line)?.groupValues?.getOrNull(1)
                            val variantLabel = height?.let { "${it}p" } ?: qualityLabel
                            normalVariants.add(absoluteChild to variantLabel)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(DEBUG_TAG, "extractor=Cast hlsMasterParseFallback reason=${e.javaClass.simpleName}")
                }

                Log.d(DEBUG_TAG, "extractor=Cast hlsMasterStatus=$hlsMasterStatus")
                Log.d(DEBUG_TAG, "extractor=Cast normalVariantCount=${normalVariants.size}")
                Log.d(DEBUG_TAG, "extractor=Cast iframeVariantCount=$iframeVariantCount")
                Log.d(DEBUG_TAG, "extractor=Cast iframeVariantIgnored=${iframeVariantCount > 0}")

                val playableVariants = normalVariants.ifEmpty {
                    mutableListOf(finalMediaUrl to qualityLabel)
                }

                playableVariants.forEach { (mediaUrl, variantLabel) ->
                    val selectedPath = runCatching {
                        URI(mediaUrl).let { "${it.host}${it.path}" }
                    }.getOrDefault("invalid")
                    Log.d(DEBUG_TAG, "extractor=Cast selectedNormalVariant=$selectedPath")
                    callback(
                        newExtractorLink(
                            source = "CAST HD",
                            name   = "CAST $variantLabel",
                            url    = mediaUrl,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$playerOrigin/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mediaHeaders
                        }
                    )
                    Log.d(
                        DEBUG_TAG,
                        "extractor=Cast callbackMedia=true referer=$playerOrigin/ " +
                            "fallback=${normalVariants.isEmpty()}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "extractor=Cast failed player=$url", e)
        }
    }
}
