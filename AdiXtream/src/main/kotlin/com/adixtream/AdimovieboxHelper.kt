package com.adixtream

import android.annotation.SuppressLint
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
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

    // Fungsi MD5 Sakti ala Adicinemax21
    private fun md5(input: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input)).toString(16).padStart(32, '0')
    }

    private fun generateXClientToken(timestamp: Long): String {
        val reversed = timestamp.toString().reversed()
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

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

    private fun getHeadersV2(url: String, body: String? = null, method: String = "POST"): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val cTypeSignature = if (method == "POST") "application/json; charset=utf-8" else ""
        
        val headers = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; 2201117TI; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "x-client-token" to generateXClientToken(timestamp),
            "x-tr-signature" to generateXTrSignature(method, "application/json", cTypeSignature, url, body, timestamp),
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"Xiaomi","model":"2201117TI","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0"
        )
        if (method == "POST") {
            headers["content-type"] = "application/json; charset=utf-8"
        }
        return headers
    }

    private suspend fun getAdimoviebox2(title: String, year: Int?, isTvSeries: Boolean, season: Int, episode: Int, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        // 1. SEARCH MENGGUNAKAN RAW STRING JSON (Bebas Error)
        val searchUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/search/v2"
        val searchBody = """{"page":1,"perPage":10,"keyword":"$title"}"""
        
        val searchRes = app.post(
            searchUrl, 
            headers = getHeadersV2(searchUrl, searchBody, "POST"), 
            requestBody = searchBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        ).parsedSafe<Adimoviebox2SearchResponse>()
        
        val matchedSubject = searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.firstOrNull { subject ->
            subject.title?.contains(title, true) == true
        } ?: return

        val mainSubjectId = matchedSubject.subjectId ?: return

        // 2. DETAIL & DUBBING
        val detailUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/get?subjectId=$mainSubjectId"
        val detailRes = app.get(detailUrl, headers = getHeadersV2(detailUrl, null, "GET")).text
        
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

        // 3. PLAY
        subjectList.forEach { (currentSubjectId, languageName) ->
            val playUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$season&ep=$episode"
            val playRes = app.get(playUrl, headers = getHeadersV2(playUrl, null, "GET")).parsedSafe<Adimoviebox2PlayResponse>()
            
            playRes?.data?.streams?.forEach { stream ->
                val streamUrl = stream.url ?: return@forEach
                
                // HEADER VIDEO MURNI (Ala Adicinemax21)
                val headerStream = mutableMapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                )
                if (!stream.signCookie.isNullOrEmpty()) {
                    headerStream["Cookie"] = stream.signCookie
                }

                callback.invoke(newExtractorLink("Adimoviebox2", "Adimoviebox2 ($languageName)", streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(stream.resolutions)
                    this.headers = headerStream
                })

                if (stream.id != null) {
                    val subInternal = "$apiUrlV2/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$currentSubjectId&streamId=${stream.id}"
                    app.get(subInternal, headers = getHeadersV2(subInternal, null, "GET")).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                        subtitleCallback.invoke(SubtitleFile("${cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"} ($languageName)", cap.url ?: return@forEach))
                    }
                    val subExternal = "$apiUrlV2/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$currentSubjectId&resourceId=${stream.id}&episode=0"
                    app.get(subExternal, headers = getHeadersV2(subExternal, null, "GET")).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                        subtitleCallback.invoke(SubtitleFile("${cap.lan ?: cap.lanName ?: cap.language ?: "Unknown"} ($languageName) [Ext]", cap.url ?: return@forEach))
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
        // RAW STRING JSON (Bebas Error)
        val searchBody = """{"keyword":"$title","page":1,"perPage":0,"subjectType":0}"""
        
        val searchRes = app.post(
            searchUrl, 
            requestBody = searchBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        ).parsedSafe<AdimovieboxResponse>()
        
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
             callback.invoke(newExtractorLink("Adimoviebox1", "Adimoviebox1", source.url ?: return@forEach, if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "$apiUrlV1/"
                    this.quality = getQualityFromName(source.resolutions)
             })
        }
        
        val id = streams.firstOrNull()?.id
        val format = streams.firstOrNull()?.format
        if (id != null) {
            val subUrl = "$apiUrlV1/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=$subjectId"
            app.get(subUrl, referer = validReferer).parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub ->
                subtitleCallback.invoke(SubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach))
            }
        }
    }

    // Fungsi Gabungan
    suspend fun getLinks(title: String, year: Int?, isTvSeries: Boolean, season: Int, episode: Int, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        try { getAdimoviebox2(title, year, isTvSeries, season, episode, callback, subtitleCallback) } catch (e: Exception) { e.printStackTrace() }
        try { getAdimoviebox1(title, year, isTvSeries, season, episode, callback, subtitleCallback) } catch (e: Exception) { e.printStackTrace() }
    }

    // ==========================================
    // DATA CLASSES PELINDUNG
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
