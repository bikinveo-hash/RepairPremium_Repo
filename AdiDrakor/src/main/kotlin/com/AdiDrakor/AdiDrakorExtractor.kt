package com.AdiDrakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.annotation.SuppressLint

object AdiDrakorExtractor {

    // ================== KISSKH SOURCE ==================
    suspend fun invokeKisskh(
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://kisskh.ovh"
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        suspend fun searchAndMatch(query: String): KisskhMedia? {
            return try {
                val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$query&type=0").text
                val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes) ?: return null
                val cleanQuery = query.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
                searchList.find {
                    val cleanItemTitle = it.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""
                    cleanItemTitle.contains(cleanQuery)
                } ?: searchList.firstOrNull {
                    val cleanItemTitle = it.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""
                    cleanItemTitle.contains(cleanQuery)
                }
            } catch (e: Exception) { null }
        }

        var matched = searchAndMatch(title)
        if (matched == null && orgTitle != null) matched = searchAndMatch(orgTitle)
        if (matched == null && altTitle != null) matched = searchAndMatch(altTitle)
        if (matched == null) return

        val dramaId = matched.id ?: return
        val detailRes = app.get("$mainUrl/api/DramaList/Drama/$dramaId?isq=false").parsedSafe<KisskhDetail>() ?: return
        val episodes = detailRes.episodes ?: return
        val targetEp = if (season == null) episodes.lastOrNull() else episodes.find { it.number?.toInt() == episode }
        val epsId = targetEp?.id ?: return

        val kkeyVideo = app.get("$KISSKH_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
        val videoUrl = "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=null&time=null&kkey=$kkeyVideo"
        val sources = app.get(videoUrl).parsedSafe<KisskhSources>()

        listOfNotNull(sources?.video, sources?.thirdParty).forEach { link ->
            if (link.contains(".m3u8")) M3u8Helper.generateM3u8("Kisskh", link, referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl)).forEach(callback)
            else if (link.contains(".mp4")) callback.invoke(newExtractorLink("Kisskh", "Kisskh", link, ExtractorLinkType.VIDEO) { this.referer = mainUrl })
        }
        val kkeySub = app.get("$KISSKH_SUB_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
        val subJson = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkeySub").text
        tryParseJson<List<KisskhSubtitle>>(subJson)?.forEach { sub ->
            subtitleCallback.invoke(newSubtitleFile(sub.label ?: "Unknown", sub.src ?: return@forEach))
        }
    }

    private data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    private data class KisskhDetail(@JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    private data class KisskhKey(@JsonProperty("key") val key: String?)
    private data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    private data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)

    // ================== ADIMOVIEBOX (V1) SOURCE (UPDATED VERSION) ==================
    suspend fun invokeAdimoviebox(
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://moviebox.ph"
        val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
        val bearerToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjY1NDQ3MzA2NDM5NjQ1MTYyMzIsImF0cCI6MywiZXh0IjoiMTc4MjUzNTQwMiIsImV4cCI6MTc5MDMxMTQwMiwiaWF0IjoxNzgyNTM1MTAyfQ.d2WpLFeF0erMdSlaaM1RMgnpyB4j1R1s2xVcY6a2Ut8"

        val commonHeaders = mapOf(
            "origin" to mainUrl,
            "referer" to "$mainUrl/",
            "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
            "accept-language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "authorization" to "Bearer $bearerToken"
        )

        suspend fun search(query: String): AdimovieboxItem? {
            val searchUrl = "$apiBaseUrl/subject/search"
            val searchBody = mapOf("keyword" to query, "page" to 1, "perPage" to 10, "subjectType" to 0)
                .toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val searchRes = app.post(searchUrl, headers = commonHeaders, requestBody = searchBody).text
            val items = tryParseJson<AdimovieboxResponse>(searchRes)?.data?.items ?: return null
            val cleanQuery = query.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
            return items.find { item ->
                val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                val cleanItemTitle = item.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""
                cleanItemTitle.isNotEmpty() && cleanQuery.isNotEmpty() &&
                (cleanItemTitle == cleanQuery || (cleanItemTitle.contains(cleanQuery) &&
                    (year == null || itemYear == null || Math.abs(itemYear - year) <= 1)))
            }
        }

        var matchedMedia = search(title.substringBefore(":").trim())
        if (matchedMedia == null && orgTitle != null) matchedMedia = search(orgTitle.substringBefore(":").trim())
        if (matchedMedia == null && altTitle != null) matchedMedia = search(altTitle.substringBefore(":").trim())
        if (matchedMedia == null) return

        val subjectId = matchedMedia.subjectId ?: return
        val detailPath = matchedMedia.detailPath ?: subjectId
        val se = season ?: 0
        val ep = episode ?: 0
        
        // 1. Alur Pemutaran Video Baru (Beralih ke endpoint netfilm.world dengan otorisasi Cookie)
        val playUrl = "https://netfilm.world/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath"
        val playHeaders = mapOf(
            "authority" to "netfilm.world",
            "accept" to "application/json",
            "referer" to "https://netfilm.world/spa/videoPlayPage/movies/$detailPath?id=$subjectId&detailSe=&detailEp=&lang=en&type=/movie/detail",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
            "x-client-info" to "{\"timezone\":\"Asia/Jakarta\"}",
            "cookie" to "mb_token=\"$bearerToken\""
        )

        val playRes = app.get(playUrl, headers = playHeaders).text
        val streams = tryParseJson<AdimovieboxResponse>(playRes)?.data?.streams ?: return
        
        streams.reversed().distinctBy { it.url }.forEach { source ->
             callback.invoke(newExtractorLink("Adimoviebox", "Adimoviebox ${source.resolutions ?: "?"}p", source.url ?: return@forEach, INFER_TYPE) {
                    this.referer = "https://netfilm.world/"
                    this.quality = getQualityFromName(source.resolutions)
             })
        }
     
        // 2. Alur Pembuatan Caption / Subtitle Baru (Melengkapi parameter detailPath & captionHeaders terbaru)
        val firstStream = streams.firstOrNull()
        val id = firstStream?.id
        val format = firstStream?.format
        if (id != null && format != null) {
            val subUrl = "$apiBaseUrl/subject/caption?format=$format&id=$id&subjectId=$subjectId&detailPath=$detailPath"
            val captionHeaders = mapOf(
                "authority" to "h5-api.aoneroom.com",
                "accept" to "application/json",
                "referer" to "https://netfilm.world/",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
                "x-client-info" to "{\"timezone\":\"Asia/Jakarta\"}"
            )
            val subRes = app.get(subUrl, headers = captionHeaders).text
            tryParseJson<AdimovieboxResponse>(subRes)?.data?.captions?.forEach { sub ->
                subtitleCallback.invoke(newSubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach))
            }
        }
    }

    // ================== VIDLINK SOURCE ==================
    suspend fun invokeVidlink(
        tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null)
            "${AdiDrakor.vidlinkAPI}/$type/$tmdbId"
        else
            "${AdiDrakor.vidlinkAPI}/$type/$tmdbId/$season/$episode"
        val videoLink = app.get(
            url,
            interceptor = WebViewResolver(
                Regex("""${AdiDrakor.vidlinkAPI}/api/b/$type/[A-Za-z0-9]{32}"""),
                timeout = 15_000L
            )
        ).parsedSafe<VidlinkSources>()?.stream?.playlist
        callback.invoke(
            newExtractorLink("Vidlink", "Vidlink", videoLink ?: return, ExtractorLinkType.M3U8) {
                this.referer = "${AdiDrakor.vidlinkAPI}/"
            }
        )
    }

    // ================== ADIMOVIEBOX 2 SOURCE (multi-host + dynamic JWT + parallel) ==================
    suspend fun invokeAdimoviebox2(
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val (brand, model) = Adimoviebox2Helper.randomBrandModel()

            val host = Adimoviebox2Helper.findWorkingHost() ?: return
            val token = Adimoviebox2Helper.getCachedToken(host, brand, model)

            suspend fun searchSubject(query: String): Adimoviebox2Subject? {
                val searchUrl = "$host/wefeed-mobile-bff/subject-api/search/v2"
                val jsonBody = mapOf("page" to 1, "perPage" to 10, "keyword" to query).toJson()
                val headersSearch = Adimoviebox2Helper.getSignedHeaders(searchUrl, jsonBody, "POST", brand, model, token)
                val response = try {
                    app.post(searchUrl, headers = headersSearch,
                        requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                } catch (_: Exception) { return null }
                Adimoviebox2Helper.persistTokenFromXUser(response.headers["x-user"])
                val searchRes = response.parsedSafe<Adimoviebox2SearchResponse>()
                val cleanQuery = query.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
                return searchRes?.data?.results?.flatMap { it.subjects ?: emptyList() }?.find { subject ->
                    val subjectYear = subject.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                    val cleanSubjectTitle = subject.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""
                    val isTitleMatch = cleanSubjectTitle.isNotEmpty() && cleanQuery.isNotEmpty() && cleanSubjectTitle.contains(cleanQuery)
                    val isYearMatch = year == null || subjectYear == year
                    val isTypeMatch = if (season != null) subject.subjectType == 2 else (subject.subjectType == 1 || subject.subjectType == 3)
                    isTitleMatch && isYearMatch && isTypeMatch
                }
            }

            var matchedSubject = searchSubject(title.substringBefore(":").trim())
            if (matchedSubject == null && orgTitle != null) matchedSubject = searchSubject(orgTitle.substringBefore(":").trim())
            if (matchedSubject == null && altTitle != null) matchedSubject = searchSubject(altTitle.substringBefore(":").trim())
            if (matchedSubject == null) return

            val originalSubjectId = matchedSubject.subjectId ?: return
            val s = season ?: 0
            val e = episode ?: 0

            // Ambil daftar dub
            val subjectUrl = "$host/wefeed-mobile-bff/subject-api/get?subjectId=$originalSubjectId"
            val subjectHeaders = Adimoviebox2Helper.getSignedHeaders(subjectUrl, null, "GET", brand, model, token)
            val subjectResponse = try {
                app.get(subjectUrl, headers = subjectHeaders)
            } catch (_: Exception) { return }
            Adimoviebox2Helper.persistTokenFromXUser(subjectResponse.headers["x-user"])

            val subjectIds = mutableListOf<Pair<String, String>>()
            if (subjectResponse.code == 200) {
                try {
                    val data = JSONObject(subjectResponse.text).optJSONObject("data")
                    val dubs = data?.optJSONArray("dubs")
                    if (dubs != null) {
                        for (i in 0 until dubs.length()) {
                            val dub = dubs.optJSONObject(i) ?: continue
                            val dubId = dub.optString("subjectId")
                            val lanName = dub.optString("lanName").ifBlank { "Dub" }
                            if (dubId.isNotBlank() && dubId != originalSubjectId)
                                subjectIds.add(Pair(dubId, lanName))
                        }
                    }
                } catch (_: Exception) { }
            }
            subjectIds.add(0, Pair(originalSubjectId, "Original"))

            val finalToken = Adimoviebox2Helper.getCachedToken(host, brand, model)

            // Parallel: tiap subjectId → play-info + stream + caption
            subjectIds.amap { (subjectId, language) ->
                try {
                    val playUrl = "$host/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$s&ep=$e"
                    val playHeaders = Adimoviebox2Helper.getSignedHeaders(playUrl, null, "GET", brand, model, finalToken)
                    val response = app.get(playUrl, headers = playHeaders)
                    if (response.code != 200) return@amap
                    Adimoviebox2Helper.persistTokenFromXUser(response.headers["x-user"])

                    val playData = try { JSONObject(response.text).optJSONObject("data") } catch (_: Exception) { null }
                    val streams = playData?.optJSONArray("streams")

                    if (streams != null && streams.length() > 0) {
                        for (i in 0 until streams.length()) {
                            val stream = streams.optJSONObject(i) ?: continue
                            val streamUrl = stream.optString("url").takeIf { it.isNotBlank() } ?: continue
                            val format = stream.optString("format")
                            val resolutions = stream.optString("resolutions")
                            val signCookie = stream.optString("signCookie").takeIf { it.isNotBlank() }
                            val id = stream.optString("id").takeIf { it.isNotBlank() } ?: "$subjectId|$s|$e"
                            val quality = getHighestQuality(resolutions)
                            val languageLabel = language.replace("dub", "Audio")
                            val sourceLabel = "Adimoviebox2 ($languageLabel)"

                            val linkHeaders = mutableMapOf<String, String>("Referer" to host)
                            if (signCookie != null) linkHeaders["Cookie"] = signCookie

                            callback.invoke(
                                newExtractorLink(
                                    source = sourceLabel,
                                    name = sourceLabel,
                                    url = streamUrl,
                                    type = when {
                                        streamUrl.startsWith("magnet:", ignoreCase = true) -> ExtractorLinkType.MAGNET
                                        streamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                                        streamUrl.substringAfterLast('.', "").equals("torrent", ignoreCase = true) -> ExtractorLinkType.TORRENT
                                        format.equals("HLS", ignoreCase = true) ||
                                                streamUrl.substringAfterLast('.', "").equals("m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                                        streamUrl.contains(".mp4", ignoreCase = true) ||
                                                streamUrl.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
                                        else -> INFER_TYPE
                                    }
                                ) {
                                    this.headers = linkHeaders
                                    if (quality != null) this.quality = quality
                                }
                            )

                            // Subtitle stream-level (internal)
                            val subUrlInternal = "$host/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$id"
                            val subHeadersInternal = Adimoviebox2Helper.getSignedHeaders(subUrlInternal, null, "GET", brand, model, finalToken)
                            try {
                                val subRoot = JSONObject(app.get(subUrlInternal, headers = subHeadersInternal).text)
                                    .optJSONObject("data")?.optJSONArray("extCaptions")
                                if (subRoot != null) {
                                    for (j in 0 until subRoot.length()) {
                                        val cap = subRoot.optJSONObject(j) ?: continue
                                        val capUrl = cap.optString("url").takeIf { it.isNotBlank() } ?: continue
                                        val lang = cap.optString("language").takeIf { it.isNotBlank() }
                                            ?: cap.optString("lanName").takeIf { it.isNotBlank() }
                                            ?: cap.optString("lan").takeIf { it.isNotBlank() }
                                            ?: "Unknown"
                                        subtitleCallback.invoke(newSubtitleFile("$lang ($languageLabel)", capUrl))
                                    }
                                }
                            } catch (_: Exception) { }

                            // Subtitle resource-level (external)
                            val subUrlExternal = "$host/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$id&episode=0"
                            val subHeadersExternal = Adimoviebox2Helper.getSignedHeaders(subUrlExternal, null, "GET", brand, model, finalToken)
                            try {
                                val subRoot = JSONObject(app.get(subUrlExternal, headers = subHeadersExternal).text)
                                    .optJSONObject("data")?.optJSONArray("extCaptions")
                                if (subRoot != null) {
                                    for (j in 0 until subRoot.length()) {
                                        val cap = subRoot.optJSONObject(j) ?: continue
                                        val capUrl = cap.optString("url").takeIf { it.isNotBlank() } ?: continue
                                        val lang = cap.optString("lan").takeIf { it.isNotBlank() }
                                            ?: cap.optString("lanName").takeIf { it.isNotBlank() }
                                            ?: cap.optString("language").takeIf { it.isNotBlank() }
                                            ?: "Unknown"
                                        subtitleCallback.invoke(newSubtitleFile("$lang ($languageLabel) [Ext]", capUrl))
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    }

                    // Fallback: kalau streams kosong, coba resourceDetectors
                    if (streams == null || streams.length() == 0) {
                        val fallbackUrl = "$host/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
                        val fallbackHeaders = playHeaders.toMutableMap().apply {
                            put("x-tr-signature", Adimoviebox2Helper.buildSignature(
                                "GET", "application/json", "application/json", fallbackUrl
                            ))
                        }
                        try {
                            val fallbackRes = app.get(fallbackUrl, headers = fallbackHeaders)
                            if (fallbackRes.code == 200) {
                                val detectors = JSONObject(fallbackRes.text)
                                    .optJSONObject("data")?.optJSONArray("resourceDetectors")
                                if (detectors != null) {
                                    for (i in 0 until detectors.length()) {
                                        val resList = detectors.optJSONObject(i)?.optJSONArray("resolutionList") ?: continue
                                        for (j in 0 until resList.length()) {
                                            val video = resList.optJSONObject(j) ?: continue
                                            val link = video.optString("resourceLink").takeIf { it.isNotBlank() } ?: continue
                                            val q = video.optInt("resolution", 0)
                                            val se2 = video.optInt("se")
                                            val ep2 = video.optInt("ep")
                                            val languageLabel = language.replace("dub", "Audio")
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = "Adimoviebox2 ($languageLabel)",
                                                    name = "Adimoviebox2 S${se2}E${ep2} ${q}p ($languageLabel)",
                                                    url = link,
                                                    type = ExtractorLinkType.VIDEO
                                                ) {
                                                    this.headers = mapOf("Referer" to host)
                                                    this.quality = q
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                } catch (_: Exception) { return@amap }
            }
        } catch (_: Exception) { }
    }

    // ================== ADIMOVIEBOX2 HELPER ==================
    private fun b64Decode(string: String): String =
        Base64.decode(string, Base64.DEFAULT).toString(Charsets.ISO_8859_1)

    private fun b64Encode(array: ByteArray): String =
        Base64.encodeToString(array, Base64.DEFAULT)

    private fun b64DecodeArray(string: String): ByteArray =
        Base64.decode(string, Base64.DEFAULT)

    private object Adimoviebox2Helper {
        private val secretKeyDefault = b64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")

        private val HOST_POOL = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com",
        )

        private val random = SecureRandom()

        val deviceId: String = run {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }

        @Volatile private var bearerToken: String? = null

        private val brandModels = mapOf(
            "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
            "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
            "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
            "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
            "Realme" to listOf("RMX3085", "RMX3360", "RMX3551")
        )

        fun randomBrandModel(): Pair<String, String> {
            val brand = brandModels.keys.random()
            val model = brandModels[brand]!!.random()
            return brand to model
        }

        suspend fun findWorkingHost(): String? {
            for (host in HOST_POOL) {
                try {
                    val url = "$host/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
                    val res = app.get(url, timeout = 5)
                    if (res.code == 200) return host
                } catch (_: Exception) { }
            }
            return HOST_POOL.firstOrNull()
        }

        fun decodeJwtExpiry(token: String): Long {
            return try {
                val payload = token.split(".").getOrNull(1) ?: return 0L
                val padded = payload.replace("-", "+").replace("_", "/")
                    .let { it + "=".repeat((4 - it.length % 4) % 4) }
                val json = Base64.decode(padded, Base64.DEFAULT).toString(Charsets.UTF_8)
                JSONObject(json).getLong("exp")
            } catch (_: Exception) { 0L }
        }

        fun isTokenValid(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            val exp = decodeJwtExpiry(token)
            return exp > System.currentTimeMillis() / 1000 + 3600
        }

        fun saveToken(token: String?) {
            if (token.isNullOrBlank()) return
            if (!isTokenValid(token)) return
            bearerToken = token
        }

        fun persistTokenFromXUser(xUserHeader: String?) {
            if (xUserHeader.isNullOrBlank()) return
            try {
                val token = JSONObject(xUserHeader).optString("token").takeIf { it.isNotBlank() } ?: return
                saveToken(token)
            } catch (_: Exception) { }
        }

        suspend fun getCachedToken(host: String, brand: String, model: String): String {
            if (isTokenValid(bearerToken)) return bearerToken!!
            val url = "$host/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
            val xClientToken = generateXClientToken()
            val xTrSignature = generateXTrSignature("GET", "application/json", "application/json", url)

            val headers = mapOf(
                "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $model; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"$brand","model":"$model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "x-client-status" to "0"
            )

            try {
                val response = app.get(url, headers = headers)
                val xUser = response.headers["x-user"]
                if (!xUser.isNullOrBlank()) {
                    val token = JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }
                    if (token != null) {
                        saveToken(token)
                        return token
                    }
                }
            } catch (_: Exception) { }
            return bearerToken ?: ""
        }

        fun getSignedHeaders(
            url: String,
            body: String? = null,
            method: String = "POST",
            brand: String,
            model: String,
            bearer: String = ""
        ): Map<String, String> {
            val accept = "application/json"
            val contentType = if (method == "POST") "application/json; charset=utf-8" else "application/json"
            val xClientToken = generateXClientToken()
            val xTrSignature = generateXTrSignature(method, accept, contentType, url, body)

            val headers = mutableMapOf(
                "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $model; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                "accept" to accept,
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"$brand","model":"$model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "x-client-status" to "0",
                "x-play-mode" to "2"
            )
            if (bearer.isNotBlank()) headers["Authorization"] = "Bearer $bearer"
            return headers
        }

        fun buildSignature(
            method: String, accept: String?, contentType: String?,
            url: String, body: String? = null
        ): String = generateXTrSignature(method, accept, contentType, url, body)

        private fun md5(input: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }

        private fun generateXClientToken(): String {
            val timestamp = System.currentTimeMillis().toString()
            return "${timestamp},${md5(timestamp.reversed().toByteArray())}"
        }

        @SuppressLint("UseKtx")
        private fun generateXTrSignature(
            method: String, accept: String?, contentType: String?,
            url: String, body: String? = null
        ): String {
            val timestamp = System.currentTimeMillis()
            val parsed = Uri.parse(url)
            val path = parsed.path ?: ""
            val query = if (parsed.queryParameterNames.isNotEmpty()) {
                parsed.queryParameterNames.sorted().joinToString("&") { key ->
                    parsed.getQueryParameters(key).joinToString("&") { value -> "$key=$value" }
                }
            } else ""
            val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            val bodyHash = if (bodyBytes != null) {
                md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes)
            } else ""
            val bodyLength = bodyBytes?.size?.toString() ?: ""
            val canonical = "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
            val secretBytes = b64DecodeArray(secretKeyDefault)
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
            val signature = b64Encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
            return "$timestamp|2|$signature"
        }
    }
}

private fun getHighestQuality(input: String): Int? {
    val qualities = listOf(
        "2160" to Qualities.P2160.value,
        "1440" to Qualities.P1440.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "360"  to Qualities.P360.value,
        "240"  to Qualities.P240.value
    )
    for ((label, mappedValue) in qualities) {
        if (input.contains(label, ignoreCase = true)) return mappedValue
    }
    return null
}
