package com.RiveStream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * PrimeSrc Helper - Mengoordinasikan komunikasi dengan layanan PrimeSrc Embed
 */
class PrimeSrcHelper { //

    companion object { //
        private const val PRIMESRC_BASE = "https://primesrc.me" //
        private const val PROXY_BASE = "https://proxy.valhallastream.com" //
        private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36" //

        private val SALT_ARRAY = arrayOf( //
            "4Z7lUo", "gwIVSMD", "kP9xL1", "mR2vB5", "aQ8wX0", "zT4nY7", "uV1oI3",
            "pE6xM9", "bC2vF4", "hG8kL0", "jK3mN5", "rP9sT1", "uW4vY7", "xA1bC3",
            "dE6fH9", "iJ2kM4", "lN8oP0", "qR3sT5", "uV9wX1", "yZ4aB7", "cD1eF3",
            "gH6iJ9", "kL2mN4", "oP8qR0", "sT3uV5", "wX9yZ1", "aB4cC7", "dE1fG3",
            "hI6jK9", "lN2mO4", "pQ8rS0", "tU3vW5", "xY9zA1", "bC4dE7", "fF1gH3",
            "jI6kL9", "mN2nP4", "oP8qR0", "sT3uV5", "wX9yZ1", "aB4cC7", "dE1fG3",
            "hI6jK9", "lN2mO4", "pQ8rS0", "tU3vW5", "xY9zA1", "bC4dE7", "fG1hI3",
            "jK6lN9", "mN2oP4", "qR8sT0", "uV3wX5", "yZ9aB1", "cC4dE7", "fG1hI3"
        )

        // TERKALIBRASI: Pembetulan huruf besar/kecil mengikut respons JSON API terkini
        private val KNOWN_SERVERS = setOf(
            "Voe", "Filelions", "Streamtape", "Dood", "Luluvdoo",
            "Streamplay", "Vidnest", "Filemoon", "Streamwish",
            "Vidmoly", "Mixdrop", "Upzur", "Savefiles", "Vidara", "VidsST"
        )

        // TERKALIBRASI: Pemetaan tepat mengikut penamaan string dari pelayan
        private val SERVER_EXTRACTOR_MAP = mapOf(
            "Voe"        to "Voe",
            "Filelions"  to "Filelions",
            "Streamtape" to "Streamtape",
            "Dood"       to "Dood",
            "Luluvdoo"   to "Luluvdoo",    
            "Streamplay" to "Streamplay", 
            "Vidnest"    to "Vidnest",
            "Filemoon"   to "Filemoon",
            "Streamwish" to "Streamwish",
            "Vidmoly"    to "Vidmoly",
            "Mixdrop"    to "Mixdrop",
            "Upzur"      to "Upzur",       
            "Savefiles"  to "Savefiles"    
        )

        private fun encodeWebSafeBase64(byteArray: ByteArray): String { //
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP) //
            return base64.replace("+", "-") //
                .replace("/", "_") //
                .replace("=", "") //
        }

        fun generateSecretKey(query: String): String { //
            var hash: Long = 3735928559L //
            val bytes = query.toByteArray(StandardCharsets.UTF_8) //

            for (i in bytes.indices) { //
                val b = bytes[i].toInt() and 0xFF //
                val saltIndex = (b + i) % SALT_ARRAY.size //
                val salt = SALT_ARRAY[saltIndex] //
                
                hash = ((hash xor b.toLong()) * 60205) and 0xFFFFFFFFL //
                
                for (j in 0 until salt.length) { //
                    val ch = salt[j].code.toLong() //
                    hash = ((hash xor ch) * 49842) and 0xFFFFFFFFL //
                }
                hash = (((hash shl 5) or (hash ushr 27)) and 0xFFFFFFFFL) //
            }

            val finalModifier = ((hash xor (bytes.size.toLong() * 10196)) * 40503) and 0xFFFFFFFFL //
            val resultString = "${finalModifier}_RiveStreamCore" //
            return encodeWebSafeBase64(resultString.toByteArray(StandardCharsets.UTF_8)) //
        }

        private fun decodeROT13(input: String): String { //
            return input.map { char -> //
                when (char) { //
                    in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar() //
                    in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar() //
                    else -> char //
                }
            }.joinToString("") //
        }

        private fun stripNoiseTokens(input: String): String { //
            val noiseTokens = arrayOf("@$", "^^", "~@", "%?", "*~", "!!", "#&") //
            var sanitized = input //
            noiseTokens.forEach { token -> //
                sanitized = sanitized.replace(token, "") //
            }
            return sanitized //
        }

        private fun decodeCaesarShift3(input: String): String { //
            return input.map { (it.code - 3).toChar() }.joinToString("") //
        }

        fun decryptVoePayload(htmlContent: String): String? { //
            return try { //
                val jsonRegex = """<script[^>]*type=["']application/json["'][^>]*>\s*(\[\s*".*?"\s*])\s*</script>""".toRegex() //
                val matchResult = jsonRegex.find(htmlContent) ?: return null //
                
                val jsonArray = JSONArray(matchResult.groupValues[1]) //
                val encryptedSeed = jsonArray.getString(0) //

                val layer1 = decodeROT13(encryptedSeed) //
                val layer3 = stripNoiseTokens(layer1) //
                val layer4 = String(Base64.decode(layer3, Base64.DEFAULT), StandardCharsets.ISO_8859_1) //
                val layer5 = decodeCaesarShift3(layer4) //
                val layer6 = layer5.reversed() //
                val layer7 = Base64.decode(layer6, Base64.DEFAULT) //
                
                val finalPlaintextJson = String(layer7, StandardCharsets.UTF_8) //
                val configObject = JSONObject(finalPlaintextJson) //
                
                configObject.optString("source").takeIf { it.isNotEmpty() } //
            } catch (e: Exception) { //
                null //
            }
        }

        private fun buildHeaders(referer: String = "$PRIMESRC_BASE/") = mapOf( //
            "User-Agent"              to USER_AGENT, //
            "Referer"                 to referer, //
            "Accept"                  to "application/json, text/plain, */*", //
            "x-nextjs-data"           to "1", //
            "sec-ch-ua"               to "\"Chromium\";v=\"139\", \"Not;A=Brand\";v=\"99\"", //
            "sec-ch-ua-mobile"        to "?1", //
            "sec-ch-ua-platform"      to "\"Android\"", //
            "sec-fetch-site"          to "same-origin", //
            "sec-fetch-mode"          to "cors", //
            "sec-fetch-dest"          to "empty", //
            "Accept-Language"         to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7", //
            "Origin"                  to PRIMESRC_BASE //
        )

        // TERKALIBRASI: Ekstraksi URL berasaskan struktur parameter kueri (query string) baharu
        private fun buildEmbedUrl(mainUrl: String, data: String): String? {
            val type: String
            val id: String

            if (data.contains("watch?")) {
                val query = data.substringAfter("?")
                val qp = query.split("&").associate {
                    it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
                }
                type = qp["type"] ?: return null
                id = qp["id"] ?: return null
            } else {
                val path = data.removePrefix("$mainUrl/").takeIf { it.isNotEmpty() } ?: return null //
                type = path.substringBefore("/") //
                id   = path.substringAfter("/").substringBefore("?") //
            }

            val params = mutableListOf<String>() //
            if (type == "tv") { //
                val query = data.substringAfter("?", "") //
                if (query.isNotEmpty() && query != data) {
                    val qp = query.split("&").associate {
                        it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
                    }
                    qp["season"]?.let  { params += "season=$it" } //
                    qp["episode"]?.let { params += "episode=$it" } //
                }
            }

            val qs = if (params.isNotEmpty()) "&" + params.joinToString("&") else "" //
            return "$PRIMESRC_BASE/embed/$type?tmdb=$id$qs" //
        }

        private fun fixStreamCastHubUrl(url: String): String { //
            return if (url.contains("streamcasthub.store")) { //
                url.replace(Regex("https://[^/]+\\.streamcasthub\\.store"), "https://rrr.streamcasthub.store") //
            } else {
                url //
            }
        }
    }

    suspend fun invokePrimeSrc( //
        data: String, //
        mainUrl: String, //
        providerName: String, //
        apiKey: String, //
        subtitleCallback: (SubtitleFile) -> Unit, //
        callback: (ExtractorLink) -> Unit //
    ): Boolean { //
        return try { //
            val embedUrl = buildEmbedUrl(mainUrl, data) ?: return false //
            val params = parseEmbedParams(embedUrl) ?: return false //
            val servers = fetchServerList(params) ?: return false //

            if (servers.isEmpty()) return false //

            val iframeUrls = mutableListOf<Pair<String, String>>()  //

            for (server in servers) { //
                if (server.name !in KNOWN_SERVERS) continue //
                val iframeUrl = fetchIframeUrl( //
                    serverKey = server.key, //
                    embedUrl = embedUrl //
                )

                if (iframeUrl != null) { //
                    iframeUrls += server.name to iframeUrl //
                }
            }

            if (iframeUrls.isEmpty()) return false //

            for ((serverName, iframeUrl) in iframeUrls) { //
                val fixedIframeUrl = fixStreamCastHubUrl(iframeUrl) //
                
                if (serverName == "Voe") { //
                    val playerHtml = app.get(fixedIframeUrl, headers = buildHeaders(embedUrl)).text //
                    val manifestUrl = decryptVoePayload(playerHtml) //
                    if (manifestUrl != null) { //
                        val nativeLink = newExtractorLink( //
                            source = "Voe Native", //
                            name = "Voe Native HLS", //
                            url = manifestUrl, //
                            type = ExtractorLinkType.M3U8 //
                        ) {
                            this.referer = fixedIframeUrl //
                            this.quality = Qualities.Unknown.value //
                        }
                        callback(nativeLink) //
                        continue //
                    }
                }

                // TERKALIBRASI: Fungsi interseptasi laman cermin streamta.site / streamtape untuk memproses elemen #botlink & parameter stream=1
                if (fixedIframeUrl.contains("streamta.site") || fixedIframeUrl.contains("streamtape")) {
                    try {
                        val playerHtml = app.get(fixedIframeUrl, headers = mapOf("Referer" to "https://primesrc.me/", "User-Agent" to USER_AGENT)).text
                        val document = org.jsoup.Jsoup.parse(playerHtml)
                        val botlink = document.selectFirst("#botlink")?.text()
                        if (!botlink.isNullOrBlank()) {
                            val finalStreamUrl = botlink + "&stream=1"
                            val nativeLink = newExtractorLink(
                                source = serverName,
                                name = "$serverName Video",
                                url = finalStreamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = fixedIframeUrl
                                this.quality = Qualities.Unknown.value
                            }
                            callback(nativeLink)
                            continue
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }

                invokeExtractor( //
                    serverName = serverName, //
                    iframeUrl  = fixedIframeUrl, //
                    embedUrl   = embedUrl, //
                    subtitleCallback = subtitleCallback, //
                    callback   = callback //
                )
            }

            true //
        } catch (e: Exception) { //
            com.lagradost.cloudstream3.mvvm.logError(e) //
            false //
        }
    }

    suspend fun invokeEmbedMode( //
        data: String, //
        mainUrl: String, //
        subtitleCallback: (SubtitleFile) -> Unit, //
        callback: (ExtractorLink) -> Unit //
    ): Boolean { //
        return try { //
            val embedUrl = buildEmbedUrl(mainUrl, data) ?: return false //
            val targetUrl = "$PROXY_BASE/?destination=${encodeWebSafeBase64(embedUrl.toByteArray(StandardCharsets.UTF_8))}" //
            val response = app.get(targetUrl, headers = buildHeaders(embedUrl)).text //
            var isExtractorInvoked = false //

            val streamCastRegex = Regex("""https://[a-zA-Z0-9.-]+\.streamcasthub\.store/[^\s"'`>]+""") //
            val matches = streamCastRegex.findAll(response) //
            for (match in matches) { //
                val url = match.value //
                val fixedUrl = fixStreamCastHubUrl(url) //
                
                if (fixedUrl.contains("streamta.site") || fixedUrl.contains("streamtape")) {
                    try {
                        val playerHtml = app.get(fixedUrl, headers = mapOf("Referer" to "https://primesrc.me/", "User-Agent" to USER_AGENT)).text
                        val document = org.jsoup.Jsoup.parse(playerHtml)
                        val botlink = document.selectFirst("#botlink")?.text()
                        if (!botlink.isNullOrBlank()) {
                            val finalStreamUrl = botlink + "&stream=1"
                            val nativeLink = newExtractorLink(
                                source = "Embed Streamtape",
                                name = "Embed Streamtape Video",
                                url = finalStreamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = fixedUrl
                                this.quality = Qualities.Unknown.value
                            }
                            callback(nativeLink)
                            isExtractorInvoked = true
                            continue
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }

                if (com.lagradost.cloudstream3.utils.loadExtractor(fixedUrl, embedUrl, subtitleCallback, callback)) { //
                    isExtractorInvoked = true //
                }
            }

            val document = org.jsoup.Jsoup.parse(response) //
            for (iframe in document.select("iframe")) { //
                var src = iframe.attr("src") //
                if (src.isNotEmpty()) { //
                    if (src.startsWith("//")) src = "https:$src" //
                    val fixedSrc = fixStreamCastHubUrl(src) //
                    
                    if (fixedSrc.contains("streamta.site") || fixedSrc.contains("streamtape")) {
                        try {
                            val playerHtml = app.get(fixedSrc, headers = mapOf("Referer" to "https://primesrc.me/", "User-Agent" to USER_AGENT)).text
                            val doc = org.jsoup.Jsoup.parse(playerHtml)
                            val botlink = doc.selectFirst("#botlink")?.text()
                            if (!botlink.isNullOrBlank()) {
                                val finalStreamUrl = botlink + "&stream=1"
                                val nativeLink = newExtractorLink(
                                    source = "Embed Iframe Streamtape",
                                    name = "Embed Iframe Streamtape Video",
                                    url = finalStreamUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = fixedSrc
                                    this.quality = Qualities.Unknown.value
                                }
                                callback(nativeLink)
                                isExtractorInvoked = true
                                continue
                            }
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }

                    if (com.lagradost.cloudstream3.utils.loadExtractor(fixedSrc, embedUrl, subtitleCallback, callback)) { //
                        isExtractorInvoked = true //
                    }
                }
            }

            isExtractorInvoked //
        } catch (e: Exception) { //
            com.lagradost.cloudstream3.mvvm.logError(e) //
            false //
        }
    }

    // TERKALIBRASI: Pemotongan parameter secretKey dan proxyMode untuk menyelaraskan API pelayan yang baharu
    private suspend fun fetchServerList(params: EmbedParams): List<PrimeSrcServer>? {
        val url = buildString {
            append("$PRIMESRC_BASE/api/v1/s?")
            append("tmdb=${params.tmdb}")
            append("&type=${params.type}")
            if (params.season != null)  append("&season=${params.season}")
            if (params.episode != null) append("&episode=${params.episode}")
        }

        val referer = buildEmbedUrlFromParams(params)
        val response = app.get(url, headers = buildHeaders(referer)).text //
        val parsed = tryParseJson<ServerListResponse>(response) ?: return null //
        return parsed.servers //
    }

    // TERKALIBRASI: Pembersihan kueri token rahasia pada penjemputan pautan iframe pelayan
    private suspend fun fetchIframeUrl(serverKey: String, embedUrl: String): String? {
        return try {
            val url = "$PRIMESRC_BASE/api/v1/l?key=$serverKey"
            val response = app.get(url, headers = buildHeaders(embedUrl)).text //
            val parsed = tryParseJson<LinkResponse>(response) //
            parsed?.link //
        } catch (e: Exception) {
            null //
        }
    }

    private suspend fun invokeExtractor( //
        serverName: String, //
        iframeUrl: String, //
        embedUrl: String, //
        subtitleCallback: (SubtitleFile) -> Unit, //
        callback: (ExtractorLink) -> Unit //
    ) {
        val extractorKey = SERVER_EXTRACTOR_MAP[serverName] ?: return //
        val fixedUrl = fixStreamCastHubUrl(iframeUrl) //

        try { //
            getExtractorApiFromName(extractorKey).getSafeUrl( //
                url = fixedUrl, //
                referer = embedUrl, //
                subtitleCallback = subtitleCallback, //
                callback = callback //
            )
        } catch (e: Exception) { //
            com.lagradost.cloudstream3.mvvm.logError(e) //
        }
    }

    private data class EmbedParams( //
        val type: String,      //
        val tmdb: Int, //
        val season: Int? = null, //
        val episode: Int? = null //
    )

    private fun parseEmbedParams(embedUrl: String): EmbedParams? { //
        val typePart = embedUrl.substringAfter("/embed/").substringBefore("?") //
        val query = embedUrl.substringAfter("?", "") //

        val qp = query.split("&").associate { //
            val key = it.substringBefore("=") //
            val value = it.substringAfter("=") //
            key to value //
        }

        val tmdb = qp["tmdb"]?.toIntOrNull() ?: return null //

        return EmbedParams( //
            type    = typePart, //
            tmdb    = tmdb, //
            season  = qp["season"]?.toIntOrNull(), //
            episode = qp["episode"]?.toIntOrNull() //
        )
    }

    private fun buildEmbedUrlFromParams(params: EmbedParams): String { //
        return buildString { //
            append("$PRIMESRC_BASE/embed/${params.type}?tmdb=${params.tmdb}") //
            if (params.season != null)  append("&season=${params.season}") //
            if (params.episode != null) append("&episode=${params.episode}") //
        }
    }

    private data class ServerListResponse( //
        @JsonProperty("servers") val servers: List<PrimeSrcServer>? //
    )

    private data class PrimeSrcServer( //
        @JsonProperty("name")           val name: String, //
        @JsonProperty("key")            val key: String //
    )

    private data class LinkResponse( //
        @JsonProperty("link")  val link: String? //
    )
}
