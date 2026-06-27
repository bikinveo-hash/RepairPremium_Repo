package com.AdiDrakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.net.Uri
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.annotation.SuppressLint

// Catatan refactor:
// - Tidak lagi mewarisi `AdiDrakor()`. Object ini hanyalah kumpulan helper "invoke" yang
//   dipanggil manual dari MainAPI.loadLinks(), BUKAN implementasi ExtractorApi() resmi.
//   Konstanta `vidlinkAPI` diakses langsung lewat `AdiDrakor.vidlinkAPI` (companion object publik),
//   sehingga inheritance ke MainAPI sama sekali tidak diperlukan.
// - Helper base64 lokal (yang sebelumnya duplikat 3x di file ini) dihapus. Sekarang memakai
//   base64Decode/base64Encode/base64DecodeArray bawaan dari com.lagradost.cloudstream3
//   (sudah tersedia lewat wildcard import di atas), sesuai standar MainAPI.kt terbaru.
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
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        var matched = searchAndMatch(title)
        if (matched == null && orgTitle != null) {
            matched = searchAndMatch(orgTitle)
        }
        if (matched == null && altTitle != null) {
            matched = searchAndMatch(altTitle)
        }
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

    // Data class Kisskh dideklarasikan private di dalam object extractor
    private data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    private data class KisskhDetail(@JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    private data class KisskhKey(@JsonProperty("key") val key: String?)
    private data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    private data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)

    // ================== ADIMOVIEBOX (OLD) SOURCE ==================
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
            val searchBody = mapOf("keyword" to query, "page" to 1, "perPage" to 10, "subjectType" to 0).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val searchRes = app.post(searchUrl, headers = commonHeaders, requestBody = searchBody).text
            val items = tryParseJson<AdimovieboxResponse>(searchRes)?.data?.items ?: return null

            val cleanQuery = query.replace(Regex("[^A-Za-z0-9]"), "").lowercase()

            return items.find { item ->
                val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                val cleanItemTitle = item.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""

                cleanItemTitle.isNotEmpty() && cleanQuery.isNotEmpty() &&
                (cleanItemTitle == cleanQuery || (cleanItemTitle.contains(cleanQuery) && (year == null || itemYear == null || Math.abs(itemYear - year) <= 1)))
            }
        }

        var matchedMedia = search(title.substringBefore(":").trim())
        if (matchedMedia == null && orgTitle != null) {
            matchedMedia = search(orgTitle.substringBefore(":").trim())
        }
        if (matchedMedia == null && altTitle != null) {
            matchedMedia = search(altTitle.substringBefore(":").trim())
        }
        if (matchedMedia == null) return

        val subjectId = matchedMedia.subjectId ?: return
        val detailPath = matchedMedia.detailPath ?: subjectId
        val se = season ?: 0
        val ep = episode ?: 0

        val playUrl = "$apiBaseUrl/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath"
        val validReferer = "$mainUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
        val reqHeaders = commonHeaders + mapOf("referer" to validReferer)

        val playRes = app.get(playUrl, headers = reqHeaders).text
        val streams = tryParseJson<AdimovieboxResponse>(playRes)?.data?.streams ?: return

        streams.reversed().distinctBy { it.url }.forEach { source ->
            callback.invoke(newExtractorLink("Adimoviebox", "Adimoviebox ${source.resolutions ?: "?"}p", source.url ?: return@forEach, INFER_TYPE) {
                this.referer = mainUrl
                this.quality = getQualityFromName(source.resolutions)
            })
        }

        val firstStream = streams.firstOrNull()
        val id = firstStream?.id
        val format = firstStream?.format
        if (id != null) {
            val subUrl = "$apiBaseUrl/subject/caption?format=$format&id=$id&subjectId=$subjectId&detailPath=$detailPath"
            app.get(subUrl, headers = reqHeaders).parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub ->
                subtitleCallback.invoke(newSubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach))
            }
        }
    }

    // ================== VIDLINK SOURCE ==================
    suspend fun invokeVidlink(
        tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) "${AdiDrakor.vidlinkAPI}/$type/$tmdbId" else "${AdiDrakor.vidlinkAPI}/$type/$tmdbId/$season/$episode"
        val videoLink = app.get(url, interceptor = WebViewResolver(Regex("""${AdiDrakor.vidlinkAPI}/api/b/$type/A{32}"""), timeout = 15_000L)).parsedSafe<VidlinkSources>()?.stream?.playlist
        callback.invoke(newExtractorLink("Vidlink", "Vidlink", videoLink ?: return, ExtractorLinkType.M3U8) { this.referer = "${AdiDrakor.vidlinkAPI}/" })
    }

    // ================== ADIMOVIEBOX 2 SOURCE ==================
    suspend fun invokeAdimoviebox2(
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://api3.aoneroom.com"
        val (brand, model) = Adimoviebox2Helper.randomBrandModel()

        suspend fun searchSubject(query: String): Adimoviebox2Subject? {
            val searchUrl = "$apiUrl/wefeed-mobile-bff/subject-api/search/v2"

            val jsonBody = mapOf("page" to 1, "perPage" to 10, "keyword" to query).toJson()
            val headersSearch = Adimoviebox2Helper.getHeaders(searchUrl, jsonBody, "POST", brand, model)
            val searchRes = app.post(searchUrl, headers = headersSearch, requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).parsedSafe<Adimoviebox2SearchResponse>()

            val cleanQuery = query.replace(Regex("[^A-Za-z0-9]"), "").lowercase()

            return searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.find { subject ->
                val subjectYear = subject.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                val cleanSubjectTitle = subject.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""

                val isTitleMatch = cleanSubjectTitle.isNotEmpty() && cleanQuery.isNotEmpty() && cleanSubjectTitle.contains(cleanQuery)
                val isYearMatch = year == null || subjectYear == year
                val isTypeMatch = if (season != null) subject.subjectType == 2 else (subject.subjectType == 1 || subject.subjectType == 3)

                isTitleMatch && isYearMatch && isTypeMatch
            }
        }

        var matchedSubject = searchSubject(title)
        if (matchedSubject == null && orgTitle != null) {
            matchedSubject = searchSubject(orgTitle)
        }
        if (matchedSubject == null && altTitle != null) {
            matchedSubject = searchSubject(altTitle)
        }
        if (matchedSubject == null) return

        val mainSubjectId = matchedSubject.subjectId ?: return

        val detailUrl = "$apiUrl/wefeed-mobile-bff/subject-api/get?subjectId=$mainSubjectId"
        val detailHeaders = Adimoviebox2Helper.getHeaders(detailUrl, null, "GET", brand, model)
        val detailRes = app.get(detailUrl, headers = detailHeaders).text

        val subjectList = mutableListOf<Pair<String, String>>()
        try {
            val json = JSONObject(detailRes)
            val data = json.optJSONObject("data")
            subjectList.add(mainSubjectId to "Original Audio")
            val dubs = data?.optJSONArray("dubs")
            if (dubs != null) {
                for (i in 0 until dubs.length()) {
                    val dub = dubs.optJSONObject(i)
                    val dubId = dub?.optString("subjectId")
                    val dubName = dub?.optString("lanName") ?: "Dub"
                    if (!dubId.isNullOrEmpty() && dubId != mainSubjectId) {
                        subjectList.add(dubId to dubName)
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
            subjectList.add(mainSubjectId to "Original Audio")
        }

        val s = season ?: 0
        val e = episode ?: 0

        subjectList.forEach { (currentSubjectId, languageName) ->
            val playUrl = "$apiUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$s&ep=$e"
            val headersPlay = Adimoviebox2Helper.getHeaders(playUrl, null, "GET", brand, model)
            val playRes = app.get(playUrl, headers = headersPlay).parsedSafe<Adimoviebox2PlayResponse>()
            val streams = playRes?.data?.streams ?: return@forEach

            streams.forEach { stream ->
                val streamUrl = stream.url ?: return@forEach
                val quality = getQualityFromName(stream.resolutions)
                val signCookie = stream.signCookie

                val videoHeaders = mutableMapOf<String, String>()
                if (!signCookie.isNullOrEmpty()) {
                    videoHeaders["Cookie"] = signCookie
                }
                videoHeaders["User-Agent"] = USER_AGENT

                val sourceName = "Adimoviebox2 ($languageName)"
                callback.invoke(
                    newExtractorLink(
                        sourceName,
                        sourceName,
                        streamUrl,
                        if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.quality = quality
                        this.headers = videoHeaders
                    }
                )

                if (stream.id != null) {
                    val subUrlInternal = "$apiUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$currentSubjectId&streamId=${stream.id}"
                    val headersSubInternal = Adimoviebox2Helper.getHeaders(subUrlInternal, null, "GET", brand, model)
                    app.get(subUrlInternal, headers = headersSubInternal).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                        val lang = cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"
                        subtitleCallback.invoke(newSubtitleFile("$lang ($languageName)", cap.url ?: return@forEach))
                    }

                    val subUrlExternal = "$apiUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$currentSubjectId&resourceId=${stream.id}&episode=0"
                    val subHeaders = Adimoviebox2Helper.getHeaders(subUrlExternal, null, "GET", brand, model)
                    app.get(subUrlExternal, headers = subHeaders).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                        val lang = cap.lan ?: cap.lanName ?: cap.language ?: "Unknown"
                        subtitleCallback.invoke(newSubtitleFile("$lang ($languageName) [Ext]", cap.url ?: return@forEach))
                    }
                }
            }
        }
    }

    private object Adimoviebox2Helper {
        // Memakai base64Decode/base64DecodeArray bawaan com.lagradost.cloudstream3
        // (lihat MainAPI.kt) — tidak ada lagi implementasi lokal yang duplikat.
        private val secretKeyDefault = base64Decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
        private val deviceId = (1..16).map { "0123456789abcdef".random() }.joinToString("")

        fun randomBrandModel(): Pair<String, String> {
            val brandModels = mapOf(
                "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
                "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
                "Google" to listOf("Pixel 7", "Pixel 8")
            )
            val brand = brandModels.keys.random()
            val model = brandModels[brand]!!.random()
            return brand to model
        }

        fun getHeaders(url: String, body: String? = null, method: String = "POST", brand: String, model: String): Map<String, String> {
            val timestamp = System.currentTimeMillis()
            val xClientToken = generateXClientToken(timestamp)
            val xTrSignature = generateXTrSignature(method, "application/json", if (method == "POST") "application/json; charset=utf-8" else "application/json", url, body, timestamp)
            return mapOf(
                "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $model; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                "accept" to "application/json", "content-type" to "application/json", "x-client-token" to xClientToken, "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"$brand","model":"$model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
                "x-client-status" to "0"
            )
        }

        private fun md5(input: ByteArray): String { return MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) } }

        private fun generateXClientToken(timestamp: Long): String { val reversed = timestamp.toString().reversed(); val hash = md5(reversed.toByteArray()); return "$timestamp,$hash" }

        @SuppressLint("UseKtx")
        private fun generateXTrSignature(method: String, accept: String?, contentType: String?, url: String, body: String?, timestamp: Long): String {
            val parsed = Uri.parse(url); val path = parsed.path ?: ""
            val query = if (parsed.queryParameterNames.isNotEmpty()) { parsed.queryParameterNames.sorted().joinToString("&") { key -> parsed.getQueryParameters(key).joinToString("&") { "$key=$it" } } } else ""
            val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path; val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            val bodyHash = if (bodyBytes != null) md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes) else ""; val bodyLength = bodyBytes?.size?.toString() ?: ""
            val canonical = "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
            val secretBytes = base64DecodeArray(secretKeyDefault); val mac = Mac.getInstance("HmacMD5"); mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
            val signature = base64Encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
            return "$timestamp|2|$signature"
        }
    }
}
