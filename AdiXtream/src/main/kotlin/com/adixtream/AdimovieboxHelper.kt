package com.adixtream

import android.annotation.SuppressLint
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AdimovieboxHelper {

    // ==================================================
    // BAGIAN ADIMOVIEBOX 2 (MOBILE API - MULTI BAHASA)
    // ==================================================
    private const val apiUrlV2 = "https://api3.aoneroom.com"
    private val secretKeyDefault = android.util.Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", android.util.Base64.DEFAULT)
    private val deviceId = (1..16).map { "0123456789abcdef".random() }.joinToString("")

    private fun randomBrandModel(): Pair<String, String> {
        val brandModels = mapOf(
            "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
            "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
            "Google" to listOf("Pixel 7", "Pixel 8")
        )
        val brand = brandModels.keys.random()
        val model = brandModels[brand]!!.random()
        return brand to model
    }

    private fun md5(input: ByteArray): String = MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }

    private fun generateXClientToken(timestamp: Long): String {
        val hash = md5(timestamp.toString().reversed().toByteArray())
        return "$timestamp,$hash"
    }

    // PERBAIKAN: Enkripsi Signature dibuat lebih adaptif untuk GET tanpa body
    @SuppressLint("UseKtx")
    private fun generateXTrSignature(method: String, accept: String?, contentType: String?, url: String, body: String?, timestamp: Long): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key -> parsed.getQueryParameters(key).joinToString("&") { "$key=$it" } }
        } else ""
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null && bodyBytes.isNotEmpty()) md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes) else ""
        val bodyLength = bodyBytes?.size?.toString() ?: ""
        val canonical = "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretKeyDefault, "HmacMD5"))
        val signature = android.util.Base64.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
        return "$timestamp|2|$signature"
    }

    // PERBAIKAN: Header GET dan POST dipisahkan dengan sangat rapi
    private fun getHeadersV2(url: String, body: String? = null, method: String = "POST", brand: String, model: String): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val cTypeSignature = if (method == "POST") "application/json; charset=utf-8" else ""
        
        val headers = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $model; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "x-client-token" to generateXClientToken(timestamp),
            "x-tr-signature" to generateXTrSignature(method, "application/json", cTypeSignature, url, body, timestamp),
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"$brand","model":"$model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0"
        )
        if (method == "POST") {
            headers["content-type"] = "application/json"
        }
        return headers
    }

    private suspend fun getAdimoviebox2(title: String, year: Int?, isTvSeries: Boolean, season: Int, episode: Int, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val (brand, model) = randomBrandModel()
        val searchUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": 1, "perPage": 10, "keyword": "$title"}"""
        
        val searchRes = app.post(searchUrl, headers = getHeadersV2(searchUrl, jsonBody, "POST", brand, model), requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())).parsedSafe<Adimoviebox2SearchResponse>()
        
        // PERBAIKAN: Filter dilonggarkan! Langsung ambil hasil pertama yang cocok jenisnya (Movie/Series)
        val matchedSubject = searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.firstOrNull { subject ->
            if (isTvSeries) subject.subjectType == 2 else (subject.subjectType == 1 || subject.subjectType == 3)
        } ?: return

        val mainSubjectId = matchedSubject.subjectId ?: return
        
        // Ambil Data Detail + Dubbing Audio
        val detailUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/get?subjectId=$mainSubjectId"
        val detailRes = app.get(detailUrl, headers = getHeadersV2(detailUrl, null, "GET", brand, model)).text
        
        val subjectList = mutableListOf<Pair<String, String>>()
        try {
            val data = JSONObject(detailRes).optJSONObject("data")
            subjectList.add(mainSubjectId to "Original Audio")
            val dubs = data?.optJSONArray("dubs")
            if (dubs != null) {
                for (i in 0 until dubs.length()) {
                    val dub = dubs.optJSONObject(i)
                    val dubId = dub?.optString("subjectId")
                    val dubName = dub?.optString("lanName") ?: "Dub"
                    if (!dubId.isNullOrEmpty() && dubId != mainSubjectId) subjectList.add(dubId to dubName)
                }
            }
        } catch (e: Exception) { subjectList.add(mainSubjectId to "Original Audio") }

        // Tarik Video Berdasarkan List Bahasa (Original + Dub)
        subjectList.forEach { (currentSubjectId, languageName) ->
            val playUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$season&ep=$episode"
            val playRes = app.get(playUrl, headers = getHeadersV2(playUrl, null, "GET", brand, model)).parsedSafe<Adimoviebox2PlayResponse>()
            
            playRes?.data?.streams?.forEach { stream ->
                val streamUrl = stream.url ?: return@forEach
                val baseHeaders = getHeadersV2(streamUrl, null, "GET", brand, model).toMutableMap()
                if (!stream.signCookie.isNullOrEmpty()) baseHeaders["Cookie"] = stream.signCookie

                callback.invoke(newExtractorLink("Adimoviebox2 API ($languageName)", "Adimoviebox2 ($languageName)", streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(stream.resolutions)
                    this.headers = baseHeaders
                })

                if (stream.id != null) {
                    val subInternal = "$apiUrlV2/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$currentSubjectId&streamId=${stream.id}"
                    app.get(subInternal, headers = getHeadersV2(subInternal, null, "GET", brand, model)).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                        subtitleCallback.invoke(newSubtitleFile("${cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"} ($languageName)", cap.url ?: return@forEach))
                    }
                    val subExternal = "$apiUrlV2/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$currentSubjectId&resourceId=${stream.id}&episode=0"
                    app.get(subExternal, headers = getHeadersV2(subExternal, null, "GET", brand, model)).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                        subtitleCallback.invoke(newSubtitleFile("${cap.lan ?: cap.lanName ?: cap.language ?: "Unknown"} ($languageName) [Ext]", cap.url ?: return@forEach))
                    }
                }
            }
        }
    }

    // ==================================================
    // BAGIAN ADIMOVIEBOX 1 (WEB API)
    // ==================================================
    private suspend fun getAdimoviebox1(title: String, year: Int?, isTvSeries: Boolean, season: Int, episode: Int, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val apiUrlV1 = "https://filmboom.top"
        val searchUrl = "$apiUrlV1/wefeed-h5-bff/web/subject/search"
        val searchBody = mapOf("keyword" to title, "page" to "1", "perPage" to "0", "subjectType" to "0").toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        
        val searchRes = app.post(searchUrl, requestBody = searchBody).parsedSafe<AdimovieboxResponse>()
        
        // Di V1 kita juga longgarkan, cukup cari yang judulnya mirip
        val matchedMedia = searchRes?.data?.items?.find { item ->
            item.title?.contains(title, true) == true || title.contains(item.title ?: "", true)
        } ?: return
        
        val subjectId = matchedMedia.subjectId ?: return
        val detailPath = matchedMedia.detailPath
        val playUrl = "$apiUrlV1/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=$season&ep=$episode"
        val validReferer = "$apiUrlV1/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
        
        val playRes = app.get(playUrl, referer = validReferer).parsedSafe<AdimovieboxResponse>()
        val streams = playRes?.data?.streams ?: return
        
        streams.reversed().distinctBy { it.url }.forEach { source ->
             callback.invoke(newExtractorLink("Adimoviebox1 API", "Adimoviebox1", source.url ?: return@forEach, if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "$apiUrlV1/"
                    this.quality = getQualityFromName(source.resolutions)
             })
        }
        
        val id = streams.firstOrNull()?.id
        val format = streams.firstOrNull()?.format
        if (id != null) {
            val subUrl = "$apiUrlV1/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=$subjectId"
            app.get(subUrl, referer = validReferer).parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub ->
                subtitleCallback.invoke(newSubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach))
            }
        }
    }

    // Fungsi Gabungan untuk dipanggil dari Provider
    suspend fun getLinks(title: String, year: Int?, isTvSeries: Boolean, season: Int, episode: Int, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        try { getAdimoviebox2(title, year, isTvSeries, season, episode, callback, subtitleCallback) } catch (e: Exception) { e.printStackTrace() }
        try { getAdimoviebox1(title, year, isTvSeries, season, episode, callback, subtitleCallback) } catch (e: Exception) { e.printStackTrace() }
    }

    // ==========================================
    // DATA CLASSES PELINDUNG (ANTI OBFUSCATION)
    // ==========================================
    data class AdimovieboxResponse(@JsonProperty("data") val data: AdimovieboxData? = null)
    data class AdimovieboxData(
        @JsonProperty("items") val items: ArrayList<AdimovieboxItem>? = arrayListOf(),
        @JsonProperty("streams") val streams: ArrayList<AdimovieboxStreamItem>? = arrayListOf(),
        @JsonProperty("captions") val captions: ArrayList<AdimovieboxCaptionItem>? = arrayListOf()
    )
    data class AdimovieboxItem(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null
    )
    data class AdimovieboxStreamItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolutions") val resolutions: String? = null
    )
    data class AdimovieboxCaptionItem(
        @JsonProperty("lanName") val lanName: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class Adimoviebox2SearchResponse(@JsonProperty("data") val data: Adimoviebox2SearchData? = null)
    data class Adimoviebox2SearchData(@JsonProperty("results") val results: ArrayList<Adimoviebox2SearchResult>? = arrayListOf())
    data class Adimoviebox2SearchResult(@JsonProperty("subjects") val subjects: ArrayList<Adimoviebox2Subject>? = arrayListOf())
    data class Adimoviebox2Subject(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null 
    )
    data class Adimoviebox2PlayResponse(@JsonProperty("data") val data: Adimoviebox2PlayData? = null)
    data class Adimoviebox2PlayData(@JsonProperty("streams") val streams: ArrayList<Adimoviebox2Stream>? = arrayListOf())
    data class Adimoviebox2Stream(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("resolutions") val resolutions: String? = null,
        @JsonProperty("signCookie") val signCookie: String? = null
    )
    data class Adimoviebox2SubtitleResponse(@JsonProperty("data") val data: Adimoviebox2SubtitleData? = null)
    data class Adimoviebox2SubtitleData(@JsonProperty("extCaptions") val extCaptions: ArrayList<Adimoviebox2Caption>? = arrayListOf())
    data class Adimoviebox2Caption(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("lanName") val lanName: String? = null,
        @JsonProperty("lan") val lan: String? = null
    )
}
