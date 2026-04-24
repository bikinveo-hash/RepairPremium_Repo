package com.adixtream

import android.annotation.SuppressLint
import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object Adimoviebox2Helper {

    private const val apiUrlV2 = "https://api3.aoneroom.com"
    private val deviceId = generateDeviceId()

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomBrandModel(): Pair<String, String> {
        val brandModels = mapOf(
            "Samsung" to listOf("SM-S918B", "SM-A528B", "SM-M336B"),
            "Xiaomi" to listOf("2201117TI", "M2012K11AI", "Redmi Note 11"),
            "OnePlus" to listOf("LE2111", "CPH2449", "IN2023"),
            "Google" to listOf("Pixel 6", "Pixel 7", "Pixel 8"),
            "Realme" to listOf("RMX3085", "RMX3360", "RMX3551")
        )
        val brand = brandModels.keys.random()
        val model = brandModels[brand]!!.random()
        return brand to model
    }

    // 100% Menjiplak Kriptografi MovieBoxProvider.kt aslimu
    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) }
    }

    private fun generateXClientToken(hardcodedTimestamp: Long): String {
        val timestamp = hardcodedTimestamp.toString()
        val reversed = timestamp.reversed()
        val hash = md5(reversed.toByteArray())
        return "$timestamp,$hash"
    }

    @SuppressLint("UseKtx")
    private fun buildCanonicalString(
        method: String, accept: String?, contentType: String?, url: String, body: String?, timestamp: Long
    ): String {
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
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5(trimmed)
        } else ""

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
    }

    private fun generateXTrSignature(
        method: String, accept: String?, contentType: String?, url: String, body: String? = null, timestamp: Long
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secretBytes = base64DecodeArray("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==")
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signatureB64 = base64Encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
        return "$timestamp|2|$signatureB64"
    }

    // 100% Menggunakan Struktur Header MovieBoxProvider aslimu
    private fun getHeadersV2(
        url: String, body: String? = null, method: String = "POST", brand: String, model: String, token: String? = null
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val cTypeSignature = if (method == "POST") "application/json; charset=utf-8" else "application/json"
        
        val headers = mutableMapOf(
            "user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $model; Build/BP22.250325.006; Cronet/133.0.6876.3)",
            "accept" to "application/json",
            "content-type" to "application/json", // Wajib ada meski method GET
            "connection" to "keep-alive",
            "x-client-token" to generateXClientToken(timestamp),
            "x-tr-signature" to generateXTrSignature(method, "application/json", cTypeSignature, url, body, timestamp),
            "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$deviceId","install_store":"ps","gaid":"d7578036d13336cc","brand":"$brand","model":"$model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""",
            "x-client-status" to "0",
            "x-play-mode" to "2"
        )
        if (token != null) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    // Parsing Murni pakai JSONObject (Anti-Obfuscation Crash)
    suspend fun getLinks(title: String, year: Int?, isTvSeries: Boolean, season: Int, episode: Int, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val (brand, model) = randomBrandModel()
            
            // 1. Search
            val searchUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/search/v2"
            val jsonBody = """{"page": 1, "perPage": 20, "keyword": "$title"}"""
            val searchResponse = app.post(
                searchUrl, 
                headers = getHeadersV2(searchUrl, jsonBody, "POST", brand, model), 
                requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            )
            
            if (searchResponse.code != 200) return
            
            var mainSubjectId: String? = null
            val searchData = JSONObject(searchResponse.text).optJSONObject("data") ?: return
            val resultsArr = searchData.optJSONArray("results") ?: return
            
            for (i in 0 until resultsArr.length()) {
                val resultObj = resultsArr.optJSONObject(i) ?: continue
                val subjectsArr = resultObj.optJSONArray("subjects") ?: continue
                for (j in 0 until subjectsArr.length()) {
                    val sub = subjectsArr.optJSONObject(j) ?: continue
                    val subTitle = sub.optString("title", "")
                    val subType = sub.optInt("subjectType", 1)
                    
                    val isTypeMatch = if (isTvSeries) subType == 2 else (subType == 1 || subType == 3)
                    if (subTitle.contains(title, true) && isTypeMatch) {
                        mainSubjectId = sub.optString("subjectId")
                        break
                    }
                }
                if (mainSubjectId != null) break
            }

            if (mainSubjectId == null) return

            // 2. Fetch Detail & Dapatkan Token
            val detailUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/get?subjectId=$mainSubjectId"
            val detailResponse = app.get(detailUrl, headers = getHeadersV2(detailUrl, null, "GET", brand, model))
            
            var token: String? = null
            val xUserHeader = detailResponse.okhttpResponse.header("x-user") ?: detailResponse.headers["x-user"]
            if (!xUserHeader.isNullOrBlank()) {
                try { token = JSONObject(xUserHeader).optString("token") } catch (e: Exception) {}
            }

            val subjectList = mutableListOf<Pair<String, String>>()
            try {
                val data = JSONObject(detailResponse.text).optJSONObject("data")
                subjectList.add(mainSubjectId to "Original Audio")
                val dubs = data?.optJSONArray("dubs")
                if (dubs != null) {
                    for (i in 0 until dubs.length()) {
                        val dub = dubs.optJSONObject(i) ?: continue
                        val dubId = dub.optString("subjectId")
                        val dubName = dub.optString("lanName", "Dub")
                        if (dubId.isNotEmpty() && dubId != mainSubjectId) subjectList.add(dubId to dubName)
                    }
                }
            } catch (e: Exception) { subjectList.add(mainSubjectId to "Original Audio") }

            val s = if (season == 0) 1 else season
            val e = if (episode == 0) 1 else episode

            // 3. Tarik Video
            subjectList.forEach { (currentSubjectId, languageName) ->
                val playUrl = "$apiUrlV2/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$s&ep=$e"
                val playRes = app.get(playUrl, headers = getHeadersV2(playUrl, null, "GET", brand, model, token))
                
                if (playRes.code != 200) return@forEach
                
                val streams = JSONObject(playRes.text).optJSONObject("data")?.optJSONArray("streams") ?: return@forEach
                
                for (i in 0 until streams.length()) {
                    val stream = streams.optJSONObject(i) ?: continue
                    val streamUrl = stream.optString("url")
                    if (streamUrl.isEmpty()) continue
                    
                    val resolutions = stream.optString("resolutions", "")
                    val signCookie = stream.optString("signCookie", "")
                    val streamId = stream.optString("id", "")
                    
                    val headerStream = mutableMapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                    if (signCookie.isNotEmpty()) headerStream["Cookie"] = signCookie

                    callback.invoke(newExtractorLink("Adimoviebox2", "Adimoviebox2 ($languageName)", streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                        this.quality = getQualityFromName(resolutions)
                        this.headers = headerStream
                    })

                    // 4. Tarik Subtitle
                    if (streamId.isNotEmpty()) {
                        val subInternal = "$apiUrlV2/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$currentSubjectId&streamId=$streamId"
                        val resSubInt = app.get(subInternal, headers = getHeadersV2(subInternal, null, "GET", brand, model, token))
                        if (resSubInt.code == 200) {
                            val extCapInt = JSONObject(resSubInt.text).optJSONObject("data")?.optJSONArray("extCaptions")
                            if (extCapInt != null) {
                                for (j in 0 until extCapInt.length()) {
                                    val cap = extCapInt.optJSONObject(j) ?: continue
                                    val capUrl = cap.optString("url")
                                    if (capUrl.isNotEmpty()) {
                                        val lang = cap.optString("language").takeIf { it.isNotEmpty() } ?: cap.optString("lanName").takeIf { it.isNotEmpty() } ?: "Unknown"
                                        subtitleCallback.invoke(SubtitleFile("$lang ($languageName)", capUrl))
                                    }
                                }
                            }
                        }
                        
                        val subExternal = "$apiUrlV2/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$currentSubjectId&resourceId=$streamId&episode=0"
                        val resSubExt = app.get(subExternal, headers = getHeadersV2(subExternal, null, "GET", brand, model, token))
                        if (resSubExt.code == 200) {
                            val extCapExt = JSONObject(resSubExt.text).optJSONObject("data")?.optJSONArray("extCaptions")
                            if (extCapExt != null) {
                                for (j in 0 until extCapExt.length()) {
                                    val cap = extCapExt.optJSONObject(j) ?: continue
                                    val capUrl = cap.optString("url")
                                    if (capUrl.isNotEmpty()) {
                                        val lang = cap.optString("lan").takeIf { it.isNotEmpty() } ?: cap.optString("lanName").takeIf { it.isNotEmpty() } ?: "Unknown"
                                        subtitleCallback.invoke(SubtitleFile("$lang ($languageName) [Ext]", capUrl))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
