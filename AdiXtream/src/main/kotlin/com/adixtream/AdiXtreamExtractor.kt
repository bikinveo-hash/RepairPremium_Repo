package com.adixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object AdiXtreamExtractor : AdiXtream() {

    // ================== EKSTRAKTOR VIDSRC ==================
    suspend fun invokeVidSrc(
        tmdbId: String, season: Int?, episode: Int?, isTvSeries: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = if (isTvSeries) "$mainUrl/embed/tv/$tmdbId/$season/$episode" else "$mainUrl/embed/movie/$tmdbId"

        try {
            val mediaTypePath = if (isTvSeries) "tv" else "movie"
            val extRes = app.get("https://api.themoviedb.org/3/$mediaTypePath/$tmdbId/external_ids?api_key=$tmdbApiKey").text
            val imdbId = Regex(""""imdb_id"\s*:\s*"([^"]+)"""").find(extRes)?.groupValues?.get(1)?.removePrefix("tt")
            if (imdbId != null) {
                val opsRes = app.get("https://rest.opensubtitles.org/search/imdbid-$imdbId/sublanguageid-ind", headers = mapOf("X-User-Agent" to "trailers.to-UA")).text
                val subUrl = Regex(""""SubDownloadLink"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)?.replace("\\/", "/")
                val subId = Regex(""""IDSubtitleFile"\s*:\s*"([^"]+)"""").find(opsRes)?.groupValues?.get(1)
                if (subUrl != null && subId != null) {
                    val gzBytes = app.get(subUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).okhttpResponse.body?.bytes()
                    if (gzBytes != null) {
                        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("sub_data", "blob", gzBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                            .addFormDataPart("sub_id", subId).addFormDataPart("sub_enc", "UTF-8")
                            .addFormDataPart("sub_src", "ops").addFormDataPart("subformat", "srt").build()
                        val extractRes = app.post("https://cloudnestra.com/get_sub_url", requestBody = requestBody).text
                        if (extractRes.startsWith("/sub/")) subtitleCallback.invoke(newSubtitleFile("Indonesia", "https://cloudnestra.com$extractRes"))
                    }
                }
            }
        } catch (e: Exception) { }

        val universalBypass = WebViewResolver(Regex("""vidsrc|cloudnestra|vsembed|rcp"""))
        val response1 = app.get(baseUrl, interceptor = universalBypass).text
        var iframeUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response1)?.groupValues?.get(1)
            ?: Jsoup.parse(response1).selectFirst("iframe")?.attr("src") ?: return
        
        if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"

        var finalHtml = app.get(iframeUrl, referer = baseUrl, interceptor = universalBypass).text
        var finalReferer = iframeUrl

        if (!finalHtml.contains("H4sI")) {
            val hiddenMatch = Regex("""['"](/prorcp/[^'"]+|/rcp/[^'"]+)['"]""").find(finalHtml)
            if (hiddenMatch != null) {
                val domain = "https://" + (if (finalReferer.contains("vsembed.ru")) "cloudnestra.com" else finalReferer.substringAfter("://").substringBefore("/"))
                val hiddenUrl = domain + hiddenMatch.groupValues[1]
                finalHtml = app.get(hiddenUrl, referer = finalReferer, interceptor = universalBypass).text
                finalReferer = hiddenUrl
            }
        }

        val hashMatch = Regex("""(H4sI[a-zA-Z0-9+/=_\.\-\\]+m3u8)""").find(finalHtml)
        if (hashMatch != null) {
            val m3u8Url = "https://tmstr2.neonhorizonworkshops.com/pl/${hashMatch.groupValues[1].replace("\\/", "/")}"
            callback.invoke(newExtractorLink(this.name, "VidSrc HD", m3u8Url, ExtractorLinkType.M3U8) { this.referer = finalReferer })
        }
    }

    // ================== EKSTRAKTOR ADIMOVIEBOX ==================
    suspend fun invokeAdimoviebox(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://filmboom.top"
        val searchUrl = "$apiUrl/wefeed-h5-bff/web/subject/search"
        val searchBody = mapOf("keyword" to title, "page" to "1", "perPage" to "0", "subjectType" to "0").toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        val searchRes = app.post(searchUrl, requestBody = searchBody).text
        val items = tryParseJson<AdimovieboxResponse>(searchRes)?.data?.items ?: return
        
        val matchedMedia = items.find { item ->
            val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            (item.title.equals(title, true)) || (item.title?.contains(title, true) == true && itemYear == year)
        } ?: return
        
        val subjectId = matchedMedia.subjectId ?: return
        val detailPath = matchedMedia.detailPath
        val se = season ?: 0
        val ep = episode ?: 0
        val playUrl = "$apiUrl/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=$se&ep=$ep"
        val validReferer = "$apiUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
        val playRes = app.get(playUrl, referer = validReferer).text
        val streams = tryParseJson<AdimovieboxResponse>(playRes)?.data?.streams ?: return
        
        streams.reversed().distinctBy { it.url }.forEach { source ->
             callback.invoke(newExtractorLink("Adimoviebox", "Adimoviebox", source.url ?: return@forEach, INFER_TYPE) {
                    this.referer = "$apiUrl/"; this.quality = getQualityFromName(source.resolutions)
             })
        }
        
        val id = streams.firstOrNull()?.id
        val format = streams.firstOrNull()?.format
        if (id != null) {
            val subUrl = "$apiUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=$subjectId"
            app.get(subUrl, referer = validReferer).parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub ->
                subtitleCallback.invoke(newSubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach))
            }
        }
    }

    // ================== EKSTRAKTOR ADIMOVIEBOX 2 ==================
    suspend fun invokeAdimoviebox2(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://api3.aoneroom.com"
        val (brand, model) = Adimoviebox2Helper.randomBrandModel()

        val searchUrl = "$apiUrl/wefeed-mobile-bff/subject-api/search/v2"
        val jsonBody = """{"page": 1, "perPage": 10, "keyword": "$title"}"""
        val headersSearch = Adimoviebox2Helper.getHeaders(searchUrl, jsonBody, "POST", brand, model)
        val searchRes = app.post(searchUrl, headers = headersSearch, requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).parsedSafe<Adimoviebox2SearchResponse>()

        val matchedSubject = searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.find { subject ->
            val subjectYear = subject.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            val isTitleMatch = subject.title?.contains(title, true) == true
            val isYearMatch = year == null || subjectYear == year
            val isTypeMatch = if (season != null) subject.subjectType == 2 else (subject.subjectType == 1 || subject.subjectType == 3)
            isTitleMatch && isYearMatch && isTypeMatch
        } ?: return

        val mainSubjectId = matchedSubject.subjectId ?: return
        
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
                callback.invoke(newExtractorLink(sourceName, sourceName, streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                    this.quality = quality; this.headers = baseHeaders
                })

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
        private val secretKeyDefault = android.util.Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", android.util.Base64.DEFAULT)
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
        
        private fun md5(input: ByteArray): String { return java.security.MessageDigest.getInstance("MD5").digest(input).joinToString("") { "%02x".format(it) } }
        
        private fun generateXClientToken(timestamp: Long): String { val reversed = timestamp.toString().reversed(); val hash = md5(reversed.toByteArray()); return "$timestamp,$hash" }
        
        @android.annotation.SuppressLint("UseKtx")
        private fun generateXTrSignature(method: String, accept: String?, contentType: String?, url: String, body: String?, timestamp: Long): String {
            val parsed = android.net.Uri.parse(url); val path = parsed.path ?: ""; 
            val query = if (parsed.queryParameterNames.isNotEmpty()) { parsed.queryParameterNames.sorted().joinToString("&") { key -> parsed.getQueryParameters(key).joinToString("&") { "$key=$it" } } } else ""
            val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path; val bodyBytes = body?.toByteArray(Charsets.UTF_8); 
            val bodyHash = if (bodyBytes != null) md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes) else ""; val bodyLength = bodyBytes?.size?.toString() ?: ""
            val canonical = "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
            val mac = javax.crypto.Mac.getInstance("HmacMD5"); mac.init(javax.crypto.spec.SecretKeySpec(secretKeyDefault, "HmacMD5")); 
            val signature = android.util.Base64.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
            return "$timestamp|2|$signature"
        }
    }
}
