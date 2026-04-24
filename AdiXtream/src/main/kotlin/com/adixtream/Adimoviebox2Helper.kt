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

object Adimoviebox2Helper {

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

    private fun getHeadersV2(url: String, body: String? = null, method: String = "POST", brand: String, model: String): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val contentTypeSig = if(method=="POST") "application/json; charset=utf-8" else "application/json"
        
        val headers = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $model; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "x-client-token" to generateXClientToken(timestamp),
            "x-tr-signature" to generateXTrSignature(method, "application/json", contentTypeSig, url, body, timestamp),
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"$brand","model":"$model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0"
        )
        if (method == "POST") {
            headers["content-type"] = "application/json; charset=utf-8"
        }
        return headers
    }

    suspend fun getLinks(title: String, year: Int?, isTvSeries: Boolean, season: Int, episode: Int, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val (brand, model) = randomBrandModel()
            
            val searchUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/search/v2"
            val searchBody = """{"page":1,"perPage":10,"keyword":"$title"}"""
            
            val searchRes = app.post(
                searchUrl, 
                headers = getHeadersV2(searchUrl, searchBody, "POST", brand, model), 
                requestBody = searchBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            ).parsedSafe<Adimoviebox2SearchResponse>()
            
            val matchedSubject = searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.firstOrNull { subject ->
                subject.title?.contains(title, true) == true
            } ?: return

            val mainSubjectId = matchedSubject.subjectId ?: return

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

            subjectList.forEach { (currentSubjectId, languageName) ->
                val playUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$season&ep=$episode"
                val playRes = app.get(playUrl, headers = getHeadersV2(playUrl, null, "GET", brand, model)).parsedSafe<Adimoviebox2PlayResponse>()
                
                playRes?.data?.streams?.forEach { stream ->
                    val streamUrl = stream.url ?: return@forEach
                    
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
                        app.get(subInternal, headers = getHeadersV2(subInternal, null, "GET", brand, model)).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                            subtitleCallback.invoke(SubtitleFile("${cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"} ($languageName)", cap.url ?: return@forEach))
                        }
                        val subExternal = "$apiUrlV2/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$currentSubjectId&resourceId=${stream.id}&episode=0"
                        app.get(subExternal, headers = getHeadersV2(subExternal, null, "GET", brand, model)).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                            subtitleCallback.invoke(SubtitleFile("${cap.lan ?: cap.lanName ?: cap.language ?: "Unknown"} ($languageName) [Ext]", cap.url ?: return@forEach))
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

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
