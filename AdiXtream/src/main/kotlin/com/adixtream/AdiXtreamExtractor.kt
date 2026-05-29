package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.RequestBodyTypes
import org.jsoup.Jsoup
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

import android.net.Uri
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.annotation.SuppressLint

object AdiXtreamExtractor : AdiXtream() {

    // ================== EKSTRAKTOR VIDSRC ==================
    suspend fun invokeVidSrc(
        tmdbId: String,
        season: Int?,
        episode: Int?,
        isTvSeries: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = if (isTvSeries) {
            "https://vidsrcme.ru/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode"
        } else {
            "https://vidsrcme.ru/embed/movie?tmdb=$tmdbId"
        }

        try {
            val embedHtml = app.get(embedUrl).text.replace("\n", "").replace("\r", "")
            val rcpB64 = Regex("""cloudnestra\.com/rcp/([A-Za-z0-9+/=_-]+)""")
                .find(embedHtml)?.groupValues?.get(1) ?: return
            val rcpUrl = "https://cloudnestra.com/rcp/$rcpB64"

            val rcpHtml = app.get(rcpUrl, referer = embedUrl).text.replace("\n", "").replace("\r", "")
            val prorcpB64 = Regex("""/prorcp/([A-Za-z0-9+/=_-]+)""")
                .find(rcpHtml)?.groupValues?.get(1) ?: return
            val prorcpUrl = "https://cloudnestra.com/prorcp/$prorcpB64"

            val prorcpHtml = app.get(prorcpUrl, referer = rcpUrl).text

            val testDomsRegex = Regex("""var test_doms\s*=\s*\[(.*?)\];""")
            val testDomsMatch = testDomsRegex.find(prorcpHtml)
            val domains = if (testDomsMatch != null) {
                testDomsMatch.groupValues[1]
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
                    .map { it.removePrefix("https://").removeSuffix("/") }
            } else {
                listOf("tmstr1.neonhorizonworkshops.com")
            }

            val fileRegex = Regex("""file:\s*"([^"]+)"""")
            val fileMatch = fileRegex.find(prorcpHtml) ?: return
            val m3u8Template = fileMatch.groupValues[1].substringBefore(" or ")

            val m3u8Url = m3u8Template
                .replace(Regex("""tmstr[0-9]+\.\{v\d+\}"""), domains.first())
                .replace(Regex("""app[0-9]+\.\{v\d+\}"""), domains.first())

            callback.invoke(
                newExtractorLink(this.name, "VidSrc HD", m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://cloudnestra.com/"
                }
            )
        } catch (e: Exception) {
            logError(e)
        }

        try {
            val mediaTypePath = if (isTvSeries) "tv" else "movie"
            val extRes = app.get(
                "https://api.themoviedb.org/3/$mediaTypePath/$tmdbId/external_ids?api_key=$tmdbApiKey"
            ).text
            val imdbId = Regex(""""imdb_id"\s*:\s*"([^"]+)"""").find(extRes)?.groupValues?.get(1)?.removePrefix("tt")
            if (imdbId != null) {
                val opsRes = app.get(
                    "https://rest.opensubtitles.org/search/imdbid-$imdbId/sublanguageid-ind",
                    headers = mapOf("X-User-Agent" to "trailers.to-UA")
                ).text
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
                        if (extractRes.startsWith("/sub/"))
                            subtitleCallback.invoke(newSubtitleFile("Indonesia", "https://cloudnestra.com$extractRes"))
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    // ================== EKSTRAKTOR ADIMOVIEBOX (V1) ==================
    suspend fun invokeAdimoviebox(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
        originalTitle: String? = null
    ) {
        val apiUrl = "https://lok-lok.cc"

        val commonHeaders = mapOf(
            "origin" to apiUrl,
            "referer" to "$apiUrl/",
            "x-client-info" to "{\"timezone\":\"Asia/Jakarta\"}",
            "accept-language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        val searchUrl = "$apiUrl/wefeed-h5api-bff/subject/search"
        
        val searchKeyword = title.substringBefore(":").trim()
        val searchBody = mapOf("keyword" to searchKeyword, "page" to "1", "perPage" to "0", "subjectType" to "0").toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        
        val searchRes = app.post(searchUrl, headers = commonHeaders, requestBody = searchBody).text
        val items = tryParseJson<AdimovieboxResponse>(searchRes)?.data?.items ?: return
        
        val cleanSearchTitle = title.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
        val cleanOrigTitle = originalTitle?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase()
        
        val matchedMedia = items.find { item ->
            val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
            val cleanItemTitle = item.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""
            
            val isYearMatch = year == null || itemYear == year

            val isTitleMatch = cleanItemTitle.isNotEmpty() && cleanSearchTitle.isNotEmpty() &&
                    (cleanItemTitle == cleanSearchTitle || 
                    ((cleanItemTitle.contains(cleanSearchTitle) || cleanSearchTitle.contains(cleanItemTitle)) && isYearMatch))

            val isOrigMatch = cleanOrigTitle != null && cleanItemTitle.isNotEmpty() && cleanOrigTitle.isNotEmpty() &&
                    (cleanItemTitle == cleanOrigTitle || 
                    ((cleanItemTitle.contains(cleanOrigTitle) || cleanOrigTitle.contains(cleanItemTitle)) && isYearMatch))

            val isTypeMatch = if (season != null) item.subjectType == 2 else (item.subjectType == 1 || item.subjectType == 3)

            (isTitleMatch || isOrigMatch) && isTypeMatch
        } ?: return
        
        val subjectId = matchedMedia.subjectId ?: return
        val detailPath = matchedMedia.detailPath ?: subjectId 
        val se = season ?: 0
        val ep = episode ?: 0
        
        val playUrl = "$apiUrl/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath"
        val validReferer = "$apiUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
        val specificHeaders = commonHeaders + mapOf("referer" to validReferer)
        
        val playRes = app.get(playUrl, headers = specificHeaders).text
        val streams = tryParseJson<AdimovieboxResponse>(playRes)?.data?.streams ?: return
        
        streams.reversed().distinctBy { it.url }.forEach { source ->
            val streamUrl = source.url ?: return@forEach
            callback.invoke(newExtractorLink(this.name, "Adimoviebox", streamUrl, 
                if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.referer = "$apiUrl/"
                this.quality = getQualityFromName(source.resolutions)
            })
        }
        
        val id = streams.firstOrNull()?.id
        val format = streams.firstOrNull()?.format
        if (id != null && format != null) {
            val subUrl = "$apiUrl/wefeed-h5api-bff/subject/caption?format=$format&id=$id&subjectId=$subjectId"
            app.get(subUrl, headers = specificHeaders).parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub ->
                 subtitleCallback.invoke(newSubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach))
            }
        }
    }

    // ================== EKSTRAKTOR ADIMOVIEBOX 2 (V2) ==================
    suspend fun invokeAdimoviebox2(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
        originalTitle: String? = null
    ) {
        val apiUrl = "https://api3.aoneroom.com"
        val (brand, model) = Adimoviebox2Helper.randomBrandModel()

        val searchUrl = "$apiUrl/wefeed-mobile-bff/subject-api/search/v2"
        
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
                callback.invoke(newExtractorLink(this.name, sourceName, streamUrl, if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
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

    // ================== EKSTRAKTOR KISSKH (SUPPLEMENTARY) ==================
    suspend fun invokeKisskh(
        title: String, year: Int?, season: Int?, episode: Int?, isTvSeries: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
        originalTitle: String? = null
    ) {
        // ATURAN 1: Query Sanitization (Buang subjudul TMDB)
        val searchKeyword = title.substringBefore(":").trim()
        val searchUrl = "https://kisskh.ovh/api/DramaList/Search?q=$searchKeyword&type=0"
        
        val searchRes = app.get(searchUrl).parsedSafe<Array<KisskhMedia>>() ?: return
        
        // ATURAN 2: Alphanumeric Cleanup untuk pencocokan
        val cleanSearchTitle = title.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
        val cleanOrigTitle = originalTitle?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase()
        
        var matchedId: Int? = null

        for (item in searchRes) {
            val cleanApiTitle = item.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""

            // Cek Exact Match terlebih dahulu
            val isTitleExact = cleanSearchTitle.isNotEmpty() && cleanApiTitle == cleanSearchTitle
            val isOrigExact = cleanOrigTitle != null && cleanOrigTitle.isNotEmpty() && cleanApiTitle == cleanOrigTitle

            if (isTitleExact || isOrigExact) {
                matchedId = item.id
                break
            }

            // ATURAN 3: Two-Way Partial Match
            val isTitlePartial = cleanApiTitle.isNotEmpty() && cleanSearchTitle.isNotEmpty() &&
                    (cleanApiTitle.contains(cleanSearchTitle) || cleanSearchTitle.contains(cleanApiTitle))
            val isOrigPartial = cleanApiTitle.isNotEmpty() && cleanOrigTitle != null && cleanOrigTitle.isNotEmpty() &&
                    (cleanApiTitle.contains(cleanOrigTitle) || cleanOrigTitle.contains(cleanApiTitle))

            if (isTitlePartial || isOrigPartial) {
                // ATURAN 4: Year-Gated Validation (Jika partial match, tahun WAJIB sama)
                val detailUrl = "https://kisskh.ovh/api/DramaList/Drama/${item.id}?isq=false"
                val detailRes = app.get(detailUrl).parsedSafe<KisskhMediaDetail>()
                val apiYear = detailRes?.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()

                if (year == null || apiYear == year) {
                    matchedId = item.id
                    break // Lolos filter, break loop
                }
            }
        }

        if (matchedId == null) return // Tidak ada yang lolos 4 filter ketat

        // Mengambil Detail Episode
        val detailUrl = "https://kisskh.ovh/api/DramaList/Drama/$matchedId?isq=false"
        val detailRes = app.get(detailUrl).parsedSafe<KisskhMediaDetail>() ?: return
        
        val targetEpNum = if (isTvSeries) episode ?: 1 else 1
        val epObj = detailRes.episodes?.find { it.number?.toInt() == targetEpNum } ?: return
        val epsId = epObj.id ?: return

        // Mengambil Stream Links
        val kisskhApiUrl = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val kkey = app.get("$kisskhApiUrl$epsId&version=2.8.10", timeout = 10000).parsedSafe<KisskhKey>()?.key ?: ""
        
        val sourceRes = app.get("https://kisskh.ovh/api/DramaList/Episode/$epsId.png?err=false&ts=null&time=null&kkey=$kkey").parsedSafe<KisskhSources>()
        
        listOf(sourceRes?.video, sourceRes?.thirdParty).forEach { link ->
            if (link.isNullOrBlank()) return@forEach
            if (link.contains(".m3u8")) {
                M3u8Helper.generateM3u8(this.name, link, "https://kisskh.ovh/").forEach(callback)
            } else if (link.contains("mp4")) {
                callback.invoke(newExtractorLink(this.name, "Kisskh", link, INFER_TYPE) {
                    this.referer = "https://kisskh.ovh/"
                    this.quality = Qualities.P720.value
                })
            } else {
                loadExtractor(link.substringBefore("=http"), "https://kisskh.ovh/", subtitleCallback, callback)
            }
        }

        // Mengambil dan Melempar Subtitle Terenkripsi
        val kisskhSubUrl = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="
        val kkey1 = app.get("$kisskhSubUrl$epsId&version=2.8.10", timeout = 10000).parsedSafe<KisskhKey>()?.key ?: ""
        val subResText = app.get("https://kisskh.ovh/api/Sub/$epsId?kkey=$kkey1").text
        
        tryParseJson<List<KisskhSubtitle>>(subResText)?.forEach { sub ->
            val lang = if (sub.label == "Indonesia") "Indonesian" else sub.label ?: "Unknown"
            subtitleCallback.invoke(newSubtitleFile("$lang (Kisskh)", sub.src ?: return@forEach))
        }
    }

    // ================== MESIN DEKRIPSI SUBTITLE KISSKH ==================
    fun decryptKisskhSub(encryptedB64: String): String {
        val keys = listOf("AmSmZVcH93UQUezi", "8056483646328763", "sWODXX04QRTkHdlZ")
        val ivs = listOf(
            intArrayOf(1382367819, 1465333859, 1902406224, 1164854838),
            intArrayOf(909653298, 909193779, 925905208, 892483379),
            intArrayOf(946894696, 1634749029, 1127508082, 1396271183)
        )
        
        fun IntArray.toByteArray(): ByteArray = ByteArray(size * 4).also { bytes ->
            forEachIndexed { index, value ->
                bytes[index * 4] = (value shr 24).toByte()
                bytes[index * 4 + 1] = (value shr 16).toByte()
                bytes[index * 4 + 2] = (value shr 8).toByte()
                bytes[index * 4 + 3] = value.toByte()
            }
        }

        val encryptedBytes = android.util.Base64.decode(encryptedB64, android.util.Base64.DEFAULT)
        for (i in keys.indices) {
            try {
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keys[i].toByteArray(Charsets.UTF_8), "AES"), javax.crypto.spec.IvParameterSpec(ivs[i].toByteArray()))
                return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
            } catch (e: Exception) { continue }
        }
        return "DECRYPT_FAILED"
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

// ================== DATA CLASSES UNTUK KISSKH ==================
data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
data class KisskhMediaDetail(@JsonProperty("releaseDate") val releaseDate: String?, @JsonProperty("episodes") val episodes: ArrayList<KisskhEpisodes>? = arrayListOf())
data class KisskhEpisodes(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)
data class KisskhKey(val key: String)
