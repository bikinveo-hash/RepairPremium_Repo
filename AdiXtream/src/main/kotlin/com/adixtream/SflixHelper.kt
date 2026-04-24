package com.adixtream

import android.annotation.SuppressLint
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SflixHelper {
    private const val apiUrl = "https://api3.aoneroom.com"
    private val secretKeyDefault = android.util.Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", android.util.Base64.DEFAULT)
    private val deviceId = (1..16).map { "0123456789abcdef".random() }.joinToString("")

    // Menyamar menjadi HP Android sungguhan
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
        return MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }
    }

    private fun generateXClientToken(timestamp: Long): String {
        val reversed = timestamp.toString().reversed()
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    // Sistem Tanda Tangan Enkripsi (HMAC-MD5)
    @SuppressLint("UseKtx")
    private fun generateXTrSignature(method: String, accept: String?, contentType: String?, url: String, body: String?, timestamp: Long): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""
        val query = if (parsed.queryParameterNames.isNotEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { "$key=$it" }
            }
        } else ""
        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes) else ""
        val bodyLength = bodyBytes?.size?.toString() ?: ""
        val canonical = "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretKeyDefault, "HmacMD5"))
        val signature = android.util.Base64.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
        return "$timestamp|2|$signature"
    }

    private fun getHeaders(url: String, body: String? = null, method: String = "POST", brand: String, model: String): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val xClientToken = generateXClientToken(timestamp)
        val xTrSignature = generateXTrSignature(method, "application/json", if(method=="POST") "application/json; charset=utf-8" else "application/json", url, body, timestamp)
        return mapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $model; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSignature,
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"$brand","model":"$model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0"
        )
    }

    // Fungsi Utama Penarik Video
    suspend fun getLinks(
        title: String,
        isTvSeries: Boolean,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val (brand, model) = randomBrandModel()

            // 1. Pencarian Film
            val searchUrl = "$apiUrl/wefeed-mobile-bff/subject-api/search/v2"
            val jsonBody = """{"page": 1, "perPage": 10, "keyword": "$title"}"""
            val headersSearch = getHeaders(searchUrl, jsonBody, "POST", brand, model)
            val searchRes = app.post(searchUrl, headers = headersSearch, requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).parsedSafe<SflixSearchResponse>()

            val matchedSubject = searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.find { subject ->
                val isTitleMatch = subject.title?.contains(title, true) == true
                val isTypeMatch = if (isTvSeries) subject.subjectType == 2 else (subject.subjectType == 1 || subject.subjectType == 3)
                isTitleMatch && isTypeMatch
            } ?: return

            val mainSubjectId = matchedSubject.subjectId ?: return

            // 2. Ambil Detail (Termasuk cek Dubbing Bahasa)
            val detailUrl = "$apiUrl/wefeed-mobile-bff/subject-api/get?subjectId=$mainSubjectId"
            val detailHeaders = getHeaders(detailUrl, null, "GET", brand, model)
            val detailResText = app.get(detailUrl, headers = detailHeaders).text
            
            val subjectList = mutableListOf<Pair<String, String>>()
            try {
                val json = JSONObject(detailResText)
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
                subjectList.add(mainSubjectId to "Original Audio")
            }

            val s = season ?: 0
            val e = episode ?: 0

            // 3. Tarik Video (Mendukung Multi-Bahasa)
            subjectList.forEach { (currentSubjectId, languageName) ->
                val playUrl = "$apiUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$s&ep=$e"
                val headersPlay = getHeaders(playUrl, null, "GET", brand, model)
                val playRes = app.get(playUrl, headers = headersPlay).parsedSafe<SflixPlayResponse>()
                val streams = playRes?.data?.streams ?: return@forEach

                streams.forEach { stream ->
                    val streamUrl = stream.url ?: return@forEach
                    val quality = getQualityFromName(stream.resolutions)
                    val signCookie = stream.signCookie
                    
                    // Wajib set cookie keamanan supaya video tidak error 2004
                    val baseHeaders = getHeaders(streamUrl, null, "GET", brand, model).toMutableMap()
                    if (!signCookie.isNullOrEmpty()) baseHeaders["Cookie"] = signCookie

                    val sourceName = "Sflix ($languageName)"
                    callback.invoke(newExtractorLink(sourceName, sourceName, streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                        this.quality = quality
                        this.headers = baseHeaders
                    })

                    // 4. Tarik Subtitle
                    if (stream.id != null) {
                        // Subtitle Internal
                        val subUrlInternal = "$apiUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$currentSubjectId&streamId=${stream.id}"
                        val headersSubInternal = getHeaders(subUrlInternal, null, "GET", brand, model)
                        app.get(subUrlInternal, headers = headersSubInternal).parsedSafe<SflixSubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                            val lang = cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"
                            subtitleCallback.invoke(newSubtitleFile("$lang ($languageName)", cap.url ?: return@forEach))
                        }
                        
                        // Subtitle Eksternal
                        val subUrlExternal = "$apiUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$currentSubjectId&resourceId=${stream.id}&episode=0"
                        val subHeaders = getHeaders(subUrlExternal, null, "GET", brand, model)
                        app.get(subUrlExternal, headers = subHeaders).parsedSafe<SflixSubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                            val lang = cap.lan ?: cap.lanName ?: cap.language ?: "Unknown"
                            subtitleCallback.invoke(newSubtitleFile("$lang ($languageName) [Ext]", cap.url ?: return@forEach))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Data Classes
    data class SflixSearchResponse(@JsonProperty("data") val data: SflixSearchData? = null)
    data class SflixSearchData(@JsonProperty("results") val results: ArrayList<SflixSearchResult>? = arrayListOf())
    data class SflixSearchResult(@JsonProperty("subjects") val subjects: ArrayList<SflixSubject>? = arrayListOf())
    data class SflixSubject(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null
    )
    data class SflixPlayResponse(@JsonProperty("data") val data: SflixPlayData? = null)
    data class SflixPlayData(@JsonProperty("streams") val streams: ArrayList<SflixStream>? = arrayListOf())
    data class SflixStream(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("resolutions") val resolutions: String? = null,
        @JsonProperty("signCookie") val signCookie: String? = null
    )
    data class SflixSubtitleResponse(@JsonProperty("data") val data: SflixSubtitleData? = null)
    data class SflixSubtitleData(@JsonProperty("extCaptions") val extCaptions: ArrayList<SflixCaption>? = arrayListOf())
    data class SflixCaption(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("lanName") val lanName: String? = null,
        @JsonProperty("lan") val lan: String? = null
    )
}
