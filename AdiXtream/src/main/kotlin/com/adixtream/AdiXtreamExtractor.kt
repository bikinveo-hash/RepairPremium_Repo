package com.adixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

import android.net.Uri
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.annotation.SuppressLint

object AdiXtreamExtractor : AdiXtream() {

    // ================== EKSTRAKTOR MOVIEBOX (Smart Search) ==================
    suspend fun invokeMovieBox(
        tmdbId: String,
        title: String,
        originalTitle: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
        isTvSeries: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Smart Search MovieBox — temukan subjectId + detailPath
            val subject = smartMovieBoxSearch(title, originalTitle, year, isTvSeries) ?: return
            val subjectId = subject.subjectId ?: return
            val detailPath = subject.detailPath ?: return

            val mainUrl = "https://netfilm.world"
            val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"

            val commonHeaders = mapOf(
                "Accept" to "application/json",
                "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
                "x-request-lang" to "en",
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val s = season ?: 0
            val e = episode ?: 0
            val playUrl = "$mainUrl/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$s&ep=$e&detailPath=$detailPath"
            val specificReferer = "$mainUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en"
            val reqHeaders = commonHeaders + mapOf("referer" to specificReferer)

            val playResText = app.get(playUrl, headers = reqHeaders).text
            val streams = tryParseJson<MovieBoxPlayResponse>(playResText)?.data?.streams ?: return

            // Emit streams
            streams.forEach { stream ->
                val streamUrl = stream.url ?: return@forEach
                val streamType = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(this.name, "Moviebox ${stream.resolutions ?: "?"}p", streamUrl, streamType) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(stream.resolutions)
                    }
                )
            }

            // Ambil subtitle dari stream pertama saja (avoid duplicate)
            val firstStream = streams.firstOrNull()
            if (firstStream != null) {
                val captionUrl = "$apiBaseUrl/subject/caption?format=${firstStream.format}&id=${firstStream.id}&subjectId=$subjectId&detailPath=$detailPath"
                app.get(captionUrl, headers = reqHeaders).parsedSafe<MovieBoxCaptionResponse>()?.data?.captions?.forEach { cap ->
                    subtitleCallback.invoke(
                        newSubtitleFile(cap.lanName ?: "Unknown", cap.url ?: "")
                    )
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    // ================== SMART SEARCH MOVIEBOX (4-pass) ==================
    private suspend fun smartMovieBoxSearch(
        title: String,
        originalTitle: String?,
        year: Int?,
        isTvSeries: Boolean
    ): MovieBoxSubject? {
        val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
        val headers = mapOf(
            "Accept" to "application/json",
            "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
            "x-request-lang" to "en",
            "Origin" to "https://netfilm.world",
            "Referer" to "https://netfilm.world/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        // Kumpulkan kandidat query: title (English display) + originalTitle (kalau beda)
        val candidatesList = mutableListOf<String>()
        val baseKeyword = title.substringBefore(":").trim()
        if (baseKeyword.isNotEmpty()) candidatesList.add(baseKeyword)
        val origKeyword = originalTitle?.substringBefore(":")?.trim().orEmpty()
        if (origKeyword.isNotEmpty() && origKeyword != baseKeyword) {
            candidatesList.add(origKeyword)
        }

        var allCandidates: List<MovieBoxSubject> = emptyList()

        // Pass 1-3: coba tiap kandidat, filter dengan smart matching
        for (keyword in candidatesList) {
            val payload = mapOf(
                "keyword" to keyword,
                "page" to "1",
                "perPage" to 28,
                "subjectType" to 0
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            val res = app.post("$apiBaseUrl/subject/search", headers = headers, requestBody = payload).text
            val parsed = tryParseJson<MovieBoxSearchResponse>(res)
            val items = parsed?.data?.items ?: parsed?.data?.subjectList ?: emptyList()

            if (items.isNotEmpty()) {
                allCandidates = items
                val accurate = items.find { matchesMovieBoxAccurately(it, keyword, year, isTvSeries) }
                if (accurate != null) return accurate
            }
        }

        // Pass 4: loose fallback — ambil top result yang type match
        return allCandidates.firstOrNull { subject ->
            val typeOk = isTvSeries && subject.subjectType == 2 ||
                         !isTvSeries && (subject.subjectType == 1 || subject.subjectType == 3)
            typeOk
        }
    }

    // ================== MOVIEBOX ACCURACY VALIDATOR ==================
    private fun matchesMovieBoxAccurately(
        subject: MovieBoxSubject,
        query: String,
        year: Int?,
        isTvSeries: Boolean
    ): Boolean {
        val cleanSubject = normalizeTitle(subject.title ?: return false)
        val cleanQuery = normalizeTitle(query)
        if (cleanSubject.isEmpty() || cleanQuery.isEmpty()) return false

        val subjectYear = subject.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
        val yearOk = year == null || subjectYear == year

        val typeOk = isTvSeries && subject.subjectType == 2 ||
                     !isTvSeries && (subject.subjectType == 1 || subject.subjectType == 3)
        if (!typeOk) return false

        val exactMatch = cleanSubject == cleanQuery
        val containsMatch = yearOk && (
            cleanSubject.contains(cleanQuery) ||
            cleanQuery.contains(cleanSubject)
        )

        return exactMatch || containsMatch
    }

    // ================== SMART SEARCH UTILITIES ==================
    private fun normalizeTitle(title: String): String =
        title.replace(Regex("[^A-Za-z0-9]"), "").lowercase()

    // ================== EKSTRAKTOR ADIMOVIEBOX 2 (V2) — BACKUP ==================
    suspend fun invokeAdimoviebox2(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
        originalTitle: String? = null
    ) {
        val apiUrl = "https://api3.aoneroom.com"
        val (brand, model) = Adimoviebox2Helper.randomBrandModel()

        // 1. Pencarian
        val searchUrl = "$apiUrl/wefeed-mobile-bff/subject-api/search/v2"
        
        // Memotong judul sebelum tanda titik dua agar pencarian ke API lebih aman dan akurat
        val searchKeyword = title.substringBefore(":").trim()
        val jsonBody = mapOf("page" to 1, "perPage" to 10, "keyword" to searchKeyword).toJson()
        val headersSearch = Adimoviebox2Helper.getHeaders(searchUrl, jsonBody, "POST", brand, model)
        val searchRes = app.post(searchUrl, headers = headersSearch, requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).parsedSafe<Adimoviebox2SearchResponse>()

        val cleanSearchTitle = title.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
        val cleanOrigTitle = originalTitle?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase()

        val matchedSubject = searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.find { subject ->
            val subjectYear = subject.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            val cleanSubjectTitle = subject.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""
            
            val isYearMatch = year == null || subjectYear == year
            
            // Pencocokan dua arah seperti di V1
            val isTitleMatch = cleanSubjectTitle.isNotEmpty() && cleanSearchTitle.isNotEmpty() && 
                    (cleanSubjectTitle == cleanSearchTitle || 
                    ((cleanSubjectTitle.contains(cleanSearchTitle) || cleanSearchTitle.contains(cleanSubjectTitle)) && isYearMatch))
            
            val isOriginalTitleMatch = cleanOrigTitle != null && cleanSubjectTitle.isNotEmpty() && cleanOrigTitle.isNotEmpty() && 
                    (cleanSubjectTitle == cleanOrigTitle || 
                    ((cleanSubjectTitle.contains(cleanOrigTitle) || cleanOrigTitle.contains(cleanSubjectTitle)) && isYearMatch))
            
            val isTypeMatch = if (season != null) subject.subjectType == 2 else (subject.subjectType == 1 || subject.subjectType == 3)
            
            (isTitleMatch || isOriginalTitleMatch) && isTypeMatch
        } ?: return

        val mainSubjectId = matchedSubject.subjectId ?: return
        
        // 2. Mengambil Detail dan Audio (Dubs)
        val detailUrl = "$apiUrl/wefeed-mobile-bff/subject-api/get?subjectId=$mainSubjectId"
        val detailHeaders = Adimoviebox2Helper.getHeaders(detailUrl, null, "GET", brand, model)
        val detailRes = app.get(detailUrl, headers = detailHeaders).text
        
        val subjectList = mutableListOf<Pair<String, String>>()
        try {
            val json = org.json.JSONObject(detailRes)
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

        // 3. Mengambil Link Video
        subjectList.forEach { (currentSubjectId, languageName) ->
            val playUrl = "$apiUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$currentSubjectId&se=$s&ep=$e"
            val headersPlay = Adimoviebox2Helper.getHeaders(playUrl, null, "GET", brand, model)
            val playRes = app.get(playUrl, headers = headersPlay).parsedSafe<Adimoviebox2PlayResponse>()
            val streams = playRes?.data?.streams ?: return@forEach

            streams.forEach { stream ->
                val streamUrl = stream.url ?: return@forEach
                val quality = getQualityFromName(stream.resolutions)
                val signCookie = stream.signCookie
                val baseHeaders = Adimoviebox2Helper.getHeaders(streamUrl, null, "GET", brand, model).toMutableMap()
                if (!signCookie.isNullOrEmpty()) baseHeaders["Cookie"] = signCookie

                val sourceName = "Adimoviebox2 ($languageName)"
                callback.invoke(newExtractorLink(this.name, sourceName, streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                    this.quality = quality; this.headers = baseHeaders
                })

                if (stream.id != null) {
                    // Internal Subtitles
                    val subUrlInternal = "$apiUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$currentSubjectId&streamId=${stream.id}"
                    val headersSubInternal = Adimoviebox2Helper.getHeaders(subUrlInternal, null, "GET", brand, model)
                    app.get(subUrlInternal, headers = headersSubInternal).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap ->
                        val lang = cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"
                        subtitleCallback.invoke(newSubtitleFile("$lang ($languageName)", cap.url ?: return@forEach))
                    }
                    
                    // External Subtitles
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
            val xTrSignature = generateXTrSignature(method, "application/json", if(method=="POST") "application/json; charset=utf-8" else "application/json", url, body, timestamp)
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
            val parsed = Uri.parse(url); val path = parsed.path ?: ""; 
            val query = if (parsed.queryParameterNames.isNotEmpty()) { parsed.queryParameterNames.sorted().joinToString("&") { key -> parsed.getQueryParameters(key).joinToString("&") { "$key=$it" } } } else ""
            val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path; val bodyBytes = body?.toByteArray(Charsets.UTF_8); 
            val bodyHash = if (bodyBytes != null) md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes) else ""; val bodyLength = bodyBytes?.size?.toString() ?: ""
            val canonical = "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
            val secretBytes = base64DecodeArray(secretKeyDefault); val mac = Mac.getInstance("HmacMD5"); mac.init(SecretKeySpec(secretBytes, "HmacMD5")); 
            val signature = base64Encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
            return "$timestamp|2|$signature"
        }
        private fun base64DecodeArray(str: String): ByteArray { return android.util.Base64.decode(str, android.util.Base64.DEFAULT) }
    }
}
