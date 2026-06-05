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

// FIX: media bisa berisi mp4 DAN/ATAU hls
data class HydraxMedia(
    @JsonProperty("mp4") val mp4: HydraxMp4? = null,
    @JsonProperty("hls") val hls: HydraxHls? = null
)

// FIX: mp4 sources sekarang punya res_id dan sub untuk domain selection
data class HydraxMp4(
    @JsonProperty("sources") val sources: List<HydraxMp4Source>? = null,
    @JsonProperty("domains") val domains: List<String>? = null,
    @JsonProperty("fristDatas") val fristDatas: List<HydraxFristData>? = null
)

data class HydraxMp4Source(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("codec") val codec: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("res_id") val res_id: String? = null,
    @JsonProperty("sub") val sub: String? = null,
    @JsonProperty("path") val path: String? = null,
    @JsonProperty("url") val url: String? = null
)

data class HydraxFristData(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("label") val label: String? = null
)

// FIX: HLS data classes baru
data class HydraxHls(
    @JsonProperty("sources") val sources: List<List<Any>>? = null,
    @JsonProperty("domains") val domains: List<String>? = null,
    @JsonProperty("streams") val streams: List<HydraxHlsStream>? = null,
    @JsonProperty("media") val media: List<HydraxHlsMedia>? = null
)

data class HydraxHlsStream(
    @JsonProperty("URI") val uri: String? = null,
    @JsonProperty("BANDWIDTH") val bandwidth: Long? = null,
    @JsonProperty("CODECS") val codecs: String? = null,
    @JsonProperty("RESOLUTION") val resolution: String? = null,
    @JsonProperty("AUDIO") val audio: String? = null
)

data class HydraxHlsMedia(
    @JsonProperty("TYPE") val type: String? = null,
    @JsonProperty("GROUP-ID") val groupId: String? = null,
    @JsonProperty("NAME") val name: String? = null,
    @JsonProperty("LANGUAGE") val language: String? = null,
    @JsonProperty("URI") val uri: String? = null,
    @JsonProperty("AUTOSELECT") val autoselect: String? = null
)

// Legacy alias — dipertahankan agar tidak ada bagian lain yang break
typealias HydraxSource = HydraxMp4Source

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
    var port: Int = 0
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    // FIX: Error URL cache — key=url, value=Pair(statusCode, timestampMs)
    // TTL 5 detik sesuai logic SW
    private val errorCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

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
            e.printStackTrace()
        }
    }

    // FIX: Fungsi domain selection dengan round-robin fallback (sesuai SW)
    fun selectDomain(domains: List<String>, sub: String?, fileSize: Long): String? {
        if (domains.isEmpty()) return null
        // Prioritas: domain yang mengandung 'sub' string
        if (!sub.isNullOrEmpty()) {
            domains.firstOrNull { it.contains(sub) }?.let { return it }
        }
        // Fallback: round-robin berdasarkan fileSize modulo
        return domains[(fileSize % domains.size).toInt()]
    }

    // FIX: Cek apakah URL sedang dalam error TTL
    fun isUrlErrored(url: String): Int? {
        val entry = errorCache[url] ?: return null
        val elapsedMs = System.currentTimeMillis() - entry.second
        if (elapsedMs < 5000L) return entry.first   // masih dalam TTL 5 detik
        errorCache.remove(url)                        // expired, hapus
        return null
    }

    // FIX: Catat URL yang error
    fun recordUrlError(url: String, statusCode: Int) {
        errorCache[url] = Pair(statusCode, System.currentTimeMillis())
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
            
            // FIX: Query parsing yang lebih robust untuk handle encoded '=' dalam value
            val query = path.substringAfter("?")
            val params = mutableMapOf<String, String>()
            query.split("&").forEach {
                val eqIdx = it.indexOf("=")
                if (eqIdx > 0) {
                    params[it.substring(0, eqIdx)] = it.substring(eqIdx + 1)
                }
            }
            
            val encodedUrl = params["url"] ?: return
            val keyHex    = params["key"]
            // FIX: parameter 'decrypt' opsional — kalau tidak ada atau "0", skip decrypt
            val doDecrypt  = params["decrypt"]?.equals("1") ?: (keyHex != null)
            val realUrl   = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP))

            // FIX: Cek error cache sebelum fetch
            val cachedStatus = isUrlErrored(realUrl)
            if (cachedStatus != null) {
                output.write("HTTP/1.1 $cachedStatus Error (cached)\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
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

            val reqStart = rangeHeader?.replace("bytes=", "")?.split("-")?.get(0)?.toLongOrNull() ?: 0L

            val request = Request.Builder()
                .url(realUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://abyssplayer.com/")
                .header("Origin", "https://abyssplayer.com")
                .header("Accept-Encoding", "identity")
                .apply {
                    if (rangeHeader != null) header("Range", rangeHeader)
                }
                .build()

            response = app.baseClient.newCall(request).execute()

            // FIX: Catat error ke cache kalau response tidak sukses
            if (!response.isSuccessful) {
                recordUrlError(realUrl, response.code)
                output.write("HTTP/1.1 ${response.code} ${response.message}\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val code = response.code
            output.write("HTTP/1.1 $code ${response.message}\r\n".toByteArray())
            for ((key, value) in response.headers) {
                if (key.equals("transfer-encoding", true) || 
                    key.equals("content-encoding", true) || 
                    key.equals("connection", true)) continue
                output.write("$key: $value\r\n".toByteArray())
            }
            output.write("Connection: close\r\n\r\n".toByteArray())

            val body = response.body
            val inputStream = body.byteStream()

            // FIX: Decrypt hanya kalau doDecrypt=true DAN keyHex tersedia
            if (doDecrypt && keyHex != null) {
                val keyBytes  = keyHex.toByteArray(Charsets.UTF_8)
                val secretKey = SecretKeySpec(keyBytes, "AES")
                val ivSpec    = IvParameterSpec(keyBytes.copyOfRange(0, 16))
                val cipher    = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                // FIX: Skip cipher state untuk seek position (reqStart dalam 64KB pertama)
                if (reqStart > 0 && reqStart < 65536) {
                    cipher.update(ByteArray(reqStart.toInt()))
                }

                var offset    = reqStart
                val buffer    = ByteArray(32768)
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
                    output.flush()
                }
            } else {
                // FIX: Pass-through tanpa decrypt (untuk HLS .m3u8 dan .ts plain, atau URL R2)
                val buffer    = ByteArray(32768)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
            }
        } catch (e: SocketException) {
            // Wajar saat user melakukan seek brutal
        } catch (e: Exception) {
            // Abaikan error streaming putus
        } finally {
            try { response?.close() } catch (e: Exception) {}
            try { client.close() } catch (e: Exception) {}
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
        val slug    = extractSlugFromUrl(url) ?: return
        val hdrs    = baseHeaders(pageRef)

        try {
            val html  = app.get("$mainUrl/?v=$slug", headers = hdrs).text
            val datas = Regex("""datas\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return

            val decodedDatas = String(android.util.Base64.decode(datas, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
            val dataJson     = mapper.readValue(decodedDatas, HydraxData::class.java)

            val infoSlug = dataJson.slug ?: slug
            val md5Id    = dataJson.md5_id ?: return
            val userId   = dataJson.user_id ?: return
            val mediaStr = dataJson.media ?: return

            // Key derivation: MD5(userId:slug:md5Id) — sesuai SW
            val hashInput    = "$userId:$infoSlug:$md5Id".toByteArray(Charsets.UTF_8)
            val md5Hash      = MessageDigest.getInstance("MD5").digest(hashInput)
            val keyHex       = md5Hash.joinToString("") { "%02x".format(it) }

            val keyBytes  = keyHex.toByteArray(Charsets.UTF_8)
            val ivBytes   = keyBytes.copyOfRange(0, 16)
            val cipher    = Cipher.getInstance("AES/CTR/NoPadding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec    = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1))
            val mediaJson      = mapper.readValue(String(decryptedBytes, Charsets.UTF_8), HydraxMedia::class.java)

            HydraxProxy.start()

            // ── MP4 Sources ──────────────────────────────────────────────
            val mp4Data = mediaJson.mp4
            if (mp4Data != null) {
                val domains = mp4Data.domains ?: emptyList()
                mp4Data.sources?.forEach { src ->
                    val label  = src.label ?: "Unknown"
                    val codec  = src.codec ?: "h264"
                    val resId  = src.res_id ?: ""
                    val sub    = src.sub ?: ""
                    val size   = src.size ?: 0L

                    // FIX: Cek error cache sebelum lanjut
                    // URL CDN format: https://{domain}/mp4/{slug}/{res_id}/{size}
                    // Domain selection: sesuai SW — find by sub, fallback round-robin
                    val domain = HydraxProxy.selectDomain(domains, sub, size)

                    if (domain != null && resId.isNotEmpty() && size > 0L) {
                        // FIX: URL CDN yang benar dengan res_id (sesuai SW)
                        val cdnUrl = "https://$domain/mp4/$infoSlug/$resId/$size"

                        // FIX: Cek error cache
                        val cachedErr = HydraxProxy.isUrlErrored(cdnUrl)
                        if (cachedErr != null) return@forEach

                        // Key untuk decrypt: MD5(filename) — last segment of URL
                        val filename  = cdnUrl.substringAfterLast("/")
                        val fnHash    = MessageDigest.getInstance("MD5").digest(filename.toByteArray(Charsets.UTF_8))
                        val fnKeyHex  = fnHash.joinToString("") { "%02x".format(it) }

                        val encodedUrl    = android.util.Base64.encodeToString(
                            cdnUrl.toByteArray(),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                        )
                        val localProxyUrl = "http://127.0.0.1:${HydraxProxy.port}/?url=$encodedUrl&key=$fnKeyHex&decrypt=1"

                        callback(
                            newExtractorLink(
                                source = name,
                                name   = "$name $label ($codec)",
                                url    = localProxyUrl,
                                type   = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageRef
                                this.quality = labelToQuality(label)
                            }
                        )
                    } else {
                        // FIX: Fallback ke src.url + src.path kalau tidak ada domain/res_id
                        // (untuk sumber R2 yang punya url dan path langsung)
                        val baseUrl = src.url ?: ""
                        val path    = src.path ?: ""
                        if (baseUrl.isNotEmpty() && path.isNotEmpty()) {
                            val srcUrl   = "$baseUrl/$path"
                            val filename = path.substringAfterLast("/")
                            val fnHash   = MessageDigest.getInstance("MD5").digest(filename.toByteArray(Charsets.UTF_8))
                            val fnKeyHex = fnHash.joinToString("") { "%02x".format(it) }

                            val cachedErr = HydraxProxy.isUrlErrored(srcUrl)
                            if (cachedErr != null) return@forEach

                            val encodedUrl    = android.util.Base64.encodeToString(
                                srcUrl.toByteArray(),
                                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                            )
                            val localProxyUrl = "http://127.0.0.1:${HydraxProxy.port}/?url=$encodedUrl&key=$fnKeyHex&decrypt=1"

                            callback(
                                newExtractorLink(
                                    source = name,
                                    name   = "$name $label ($codec)",
                                    url    = localProxyUrl,
                                    type   = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = pageRef
                                    this.quality = labelToQuality(label)
                                }
                            )
                        }
                    }
                }
            }

            // ── HLS Sources ──────────────────────────────────────────────
            val hlsData = mediaJson.hls
            if (hlsData != null) {
                val hlsDomains = hlsData.domains ?: emptyList()
                val streams    = hlsData.streams ?: emptyList()

                streams.forEach { stream ->
                    val uri = stream.uri ?: return@forEach
                    // URI format di SW: "segIdx/md5_id/sub.m3u8?maxSize=..."
                    // Kita perlu bangun URL ke sumber HLS lewat domain
                    val uriParts    = uri.substringBefore("?").split(".")
                    val uriBase     = uriParts.firstOrNull()?.split("/") ?: return@forEach
                    // uriBase: [segIdx, md5_idVal, sub] (reversed dalam SW)
                    // Ambil sub dari tail
                    val sub         = if (uriBase.size >= 3) uriBase.last() else ""
                    val hlsDomain   = HydraxProxy.selectDomain(hlsDomains, sub, md5Id.hashCode().toLong().and(0xFFFFFFFFL))

                    if (hlsDomain != null) {
                        // Construct HLS URL langsung ke CDN domain
                        // Format: https://{domain}/hls/{md5_id}/{uri}
                        val hlsUrl = "https://$hlsDomain/hls/$md5Id/$uri"

                        val cachedErr = HydraxProxy.isUrlErrored(hlsUrl)
                        if (cachedErr != null) return@forEach

                        // HLS tidak perlu decrypt di proxy — pass-through
                        val encodedUrl    = android.util.Base64.encodeToString(
                            hlsUrl.toByteArray(),
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                        )
                        val localProxyUrl = "http://127.0.0.1:${HydraxProxy.port}/?url=$encodedUrl&decrypt=0"

                        val bandwidth = stream.bandwidth ?: 0L
                        val resolution = stream.resolution ?: ""
                        val label = when {
                            resolution.contains("2160") -> "2160p"
                            resolution.contains("1440") -> "1440p"
                            resolution.contains("1080") -> "1080p"
                            resolution.contains("720")  -> "720p"
                            resolution.contains("480")  -> "480p"
                            resolution.contains("360")  -> "360p"
                            bandwidth >= 4000000L -> "1080p"
                            bandwidth >= 2000000L -> "720p"
                            bandwidth >= 1000000L -> "480p"
                            else -> "360p"
                        }

                        callback(
                            newExtractorLink(
                                source = name,
                                name   = "$name HLS $label",
                                url    = localProxyUrl,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.referer = pageRef
                                this.quality = labelToQuality(label)
                                this.headers = mapOf(
                                    "Origin"  to mainUrl,
                                    "Referer" to "$mainUrl/"
                                )
                            }
                        )
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractSlugFromUrl(url: String): String? {
        val vParam = url.substringAfter("?v=", "").substringBefore("&")
        if (vParam.isNotEmpty() && url.contains("?v=")) return vParam
        // FIX: slug pattern diperluas hingga 50 karakter (SW mendukung slug lebih panjang)
        Regex("""(?:e|embed|v|play)/([a-zA-Z0-9_\-]{6,50})""").find(url)?.groupValues?.get(1)?.let { return it }
        return url.split("?").first().trimEnd('/').split('/').lastOrNull()
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9_\\-]{6,50}")) }
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
