package com.Adicinemax21

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import org.json.JSONObject 
import java.net.URLDecoder
import com.Adicinemax21.Adicinemax21.Companion.cinemaOSApi
import com.Adicinemax21.Adicinemax21.Companion.Player4uApi
import com.Adicinemax21.Adicinemax21.Companion.idlixAPI
import com.Adicinemax21.Adicinemax21.Companion.RiveStreamAPI
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import android.net.Uri
import android.annotation.SuppressLint
import java.util.UUID

object Adicinemax21Extractor : Adicinemax21() {

    // ================== IDLIX SOURCE (UPDATED) ==================
    suspend fun invokeIdlix(
        title: String? = null,
        altTitle: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val idlixApi = "https://z1.idlixku.com" 
            
            suspend fun searchWithQuery(query: String): List<IdlixContentData>? {
                val encoded = URLEncoder.encode(query, "utf-8")
                val searchResText = app.get("$idlixApi/api/search?q=$encoded").text
                val searchRes = tryParseJson<IdlixSearchResponse>(searchResText)
                return searchRes?.data ?: searchRes?.results
            }

            var items = title?.let { searchWithQuery(it) }
            if ((items == null || items.isEmpty()) && altTitle != null) {
                items = searchWithQuery(altTitle)
            }
            if (items == null || items.isEmpty()) return
            
            val isSeries = season != null

            // 1. Cari film/series yang cocok
            val matchedItem = items.find { 
                val titleMatch = it.title.equals(title, true) || it.originalTitle.equals(title, true) ||
                                 (altTitle != null && (it.title.equals(altTitle, true) || it.originalTitle.equals(altTitle, true)))
                val typeMatch = if (isSeries) it.contentType?.contains("series", true) == true else it.contentType?.contains("movie", true) == true
                titleMatch && typeMatch
            } ?: items.firstOrNull() ?: return

            val slug = matchedItem.slug ?: return

            var contentType = "movie"
            var contentId = ""

            // 2. Ambil ID unik konten dari API
            if (!isSeries) {
                val detailResText = app.get("$idlixApi/api/movies/$slug").text
                val detailRes = tryParseJson<IdlixDetailResponse>(detailResText)
                contentId = detailRes?.id ?: slug
            } else {
                contentType = "episode"
                val seasonResText = app.get("$idlixApi/api/series/$slug/season/$season").text
                val seasonRes = tryParseJson<IdlixSeasonApiResponse>(seasonResText)
                val ep = seasonRes?.season?.episodes?.find { it.episodeNumber == episode }
                contentId = ep?.id ?: return
            }

            // 3. Setup Cookie Device ID Tiruan
            val randomDid = UUID.randomUUID().toString().replace("-", "")
            val refererUrl = "$idlixApi/"
            val headers = mapOf(
                "Referer" to refererUrl, 
                "Origin" to idlixApi, 
                "Cookie" to "did=$randomDid; NEXT_LOCALE=id",
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
            )

            // 4. Request Gate Token & Time-Lock Data
            val playInfoResText = app.get(
                url = "$idlixApi/api/watch/play-info/$contentType/$contentId",
                headers = headers
            ).text
            val playInfoRes = tryParseJson<PlayInfoResponse>(playInfoResText)

            val gateToken = playInfoRes?.gateToken ?: return
            
            // 5. Bypass Proteksi Waktu Iklan (Time-Lock)
            val serverNow = playInfoRes.serverNow ?: 0L
            val unlockAt = playInfoRes.unlockAt ?: 0L
            val countdownSec = playInfoRes.preroll?.countdownSec ?: 7L
            
            val diffTimeMs = unlockAt - serverNow
            val baseWaitMs = countdownSec * 1000L
            
            val finalWaitMs = maxOf(baseWaitMs, diffTimeMs) + 1000L
            delay(finalWaitMs)

            // 6. Klaim Sesi Token Streaming
            val jsonMediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
            val requestBodyData = mapOf("gateToken" to gateToken).toJson().toRequestBody(jsonMediaType)
            
            val claimResText = app.post(
                url = "$idlixApi/api/watch/session/claim",
                headers = headers,
                requestBody = requestBodyData
            ).text
            
            val claimParsed = tryParseJson<SessionClaimResponse>(claimResText)
            val claim = claimParsed?.claim ?: return
            
            // 7. Arahkan ke Ekstraktor Majorplay secara langsung
            val fakeUrl = "https://e2e.majorplay.net/play?claim=$claim"
            Majorplay().getUrl(fakeUrl, refererUrl, subtitleCallback, callback)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ... data class untuk Idlix tetap sama (IdlixSearchResponse, dll.)

    // ================== ADIDEWASA SOURCE (UPDATED) ==================
    @Suppress("UNCHECKED_CAST")
    suspend fun invokeAdiDewasa(
        title: String,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = "https://dramafull.cc"
        
        suspend fun attemptSearch(query: String): AdiDewasaItem? {
            val cleanQuery = AdiDewasaHelper.normalizeQuery(query)
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
            val searchUrl = "$baseUrl/api/live-search/$encodedQuery"
            try {
                val searchRes = app.get(searchUrl, headers = AdiDewasaHelper.headers).parsedSafe<AdiDewasaSearchResponse>()
                return searchRes?.data?.find { item ->
                    val itemTitle = item.title ?: item.name ?: ""
                    AdiDewasaHelper.isFuzzyMatch(query, itemTitle)
                } ?: searchRes?.data?.firstOrNull()
            } catch (e: Exception) {
                return null
            }
        }
        
        var matchedItem = attemptSearch(title)
        if (matchedItem == null && altTitle != null) {
            matchedItem = attemptSearch(altTitle)
        }
        if (matchedItem == null) return

        val slug = matchedItem.slug ?: return
        var targetUrl = "$baseUrl/film/$slug"
        val doc = app.get(targetUrl, headers = AdiDewasaHelper.headers).document

        if (season != null && episode != null) {
            val episodeHref = doc.select("div.episode-item a, .episode-list a").find { 
                val text = it.text().trim()
                val epNum = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                epNum == episode
            }?.attr("href")
            
            if (episodeHref == null) return
            targetUrl = fixUrl(episodeHref, baseUrl)
        } else {
            val selectors = listOf("a.btn-watch", "a.watch-now", ".watch-button a", "div.last-episode a", ".film-buttons a.btn-primary")
            for (selector in selectors) {
                val el = doc.selectFirst(selector)
                if (el != null) {
                    val href = el.attr("href")
                    if (href.isNotEmpty() && !href.contains("javascript") && href != "#") {
                        targetUrl = fixUrl(href, baseUrl)
                        break
                    }
                }
            }
        }

        val docPage = app.get(targetUrl, headers = AdiDewasaHelper.headers).document
        val allScripts = docPage.select("script").joinToString(" ") { it.data() }
        val signedUrl = Regex("""signedUrl\s*=\s*["']([^"']+)["']""").find(allScripts)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
        val jsonResponseText = app.get(signedUrl, referer = targetUrl, headers = AdiDewasaHelper.headers).text
    
        val jsonObject = tryParseJson<Map<String, Any>>(jsonResponseText) ?: return
        val videoSource = jsonObject["video_source"] as? Map<String, String> ?: return
        
        videoSource.forEach { (quality, url) ->
            if (url.isNotEmpty()) callback.invoke(newExtractorLink("AdiDewasa", "AdiDewasa ($quality)", url, INFER_TYPE))
        }
        
        val bestQualityKey = videoSource.keys.maxByOrNull { it.toIntOrNull() ?: 0 } ?: return
        val subJson = jsonObject["sub"] as? Map<String, Any>
        val subs = subJson?.get(bestQualityKey) as? List<String>
        subs?.forEach { subPath -> subtitleCallback.invoke(newSubtitleFile("English", fixUrl(subPath, baseUrl))) }
    }

    // ================== KISSKH SOURCE (UPDATED) ==================
    suspend fun invokeKisskh(
        title: String,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://kisskh.ovh"
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        suspend fun searchAndMatch(query: String): KisskhMedia? {
            try {
                val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$query&type=0").text
                val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes) ?: return null
                return searchList.find { it.title.equals(query, true) } 
                    ?: searchList.firstOrNull { it.title?.contains(query, true) == true }
            } catch (e: Exception) {
                return null
            }
        }

        var matched = searchAndMatch(title)
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

    // ================== ADIMOVIEBOX (OLD) SOURCE (UPDATED) ==================
    suspend fun invokeAdimoviebox(
        title: String,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://filmboom.top"
        suspend fun search(query: String): AdimovieboxItem? {
            val searchUrl = "$apiUrl/wefeed-h5-bff/web/subject/search"
            val searchBody = mapOf("keyword" to query, "page" to "1", "perPage" to "0", "subjectType" to "0").toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            val searchRes = app.post(searchUrl, requestBody = searchBody).text
            val items = tryParseJson<AdimovieboxResponse>(searchRes)?.data?.items ?: return null
            return items.find { item ->
                val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                (item.title.equals(query, true)) || (item.title?.contains(query, true) == true && itemYear == year)
            }
        }
        
        var matchedMedia = search(title)
        if (matchedMedia == null && altTitle != null) {
            matchedMedia = search(altTitle)
        }
        if (matchedMedia == null) return

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

    // ================== GOMOVIES SOURCE (UPDATED) ==================
    suspend fun invokeGomovies(
        title: String? = null,
        altTitle: String? = null,
        year: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit,
    ) {
        invokeGpress(title, altTitle, year, season, episode, callback, Adicinemax21.gomoviesAPI, "Gomovies", base64Decode("X3NtUWFtQlFzRVRi"), base64Decode("X3NCV2NxYlRCTWFU"))
    }

    private suspend fun invokeGpress(
        title: String? = null,
        altTitle: String? = null,
        year: Int? = null, season: Int? = null, episode: Int? = null,
        callback: (ExtractorLink) -> Unit, api: String, name: String, mediaSelector: String, episodeSelector: String,
    ) {
        fun String.decrypt(key: String): List<GpressSources>? { return tryParseJson<List<GpressSources>>(base64Decode(this).xorDecrypt(key)) }
        val slug = getEpisodeSlug(season, episode)
        
        suspend fun attemptSearch(query: String): Triple<String, String, String>? {
            var cookies = mapOf("_identitygomovies7" to """5a436499900c81529e3740fd01c275b29d7e2fdbded7d760806877edb1f473e0a%3A2%3A%7Bi%3A0%3Bs%3A18%3A%22_identitygomovies7%22%3Bi%3A1%3Bs%3A52%3A%22%5B2800906%2C%22L2aGGTL9aqxksKR0pLvL66TunKNe1xXb%22%2C2592000%5D%22%3B%7D""")
            var res = app.get("$api/search/$query", cookies = cookies)
            cookies = gomoviesCookies ?: res.cookies.filter { it.key == "advanced-frontendgomovies7" }.also { gomoviesCookies = it }
            val doc = res.document
            val media = doc.select("div.$mediaSelector").map { Triple(it.attr("data-filmName"), it.attr("data-year"), it.select("a").attr("href")) }
                .let { el -> if (el.size == 1) el.firstOrNull() else el.find { if (season == null) (it.first.equals(query, true) || it.first.equals("$query ($year)", true)) && it.second.equals("$year") else it.first.equals("$query - Season $season", true) } ?: el.find { it.first.contains("$query", true) && it.second.equals("$year") } }
            return media
        }

        var media = title?.let { attemptSearch(it) }
        if (media == null && altTitle != null) {
            media = attemptSearch(altTitle)
        }
        if (media == null) return

        val iframe = if (season == null) media.third else app.get(fixUrl(media.third, api)).document.selectFirst("div#$episodeSelector a:contains(Episode ${slug.second})")?.attr("href") ?: return
        var res = app.get(fixUrl(iframe, api), cookies = gomoviesCookies ?: mapOf())
 
        val url = res.document.select("meta[property=og:url]").attr("content")
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val (serverId, episodeId) = if (season == null) url.substringAfterLast("/") to "0" else url.substringBeforeLast("/").substringAfterLast("/") to url.substringAfterLast("/").substringBefore("-")
        val serverRes = app.get("$api/user/servers/$serverId?ep=$episodeId", cookies = gomoviesCookies ?: mapOf(), headers = headers)
        val script = getAndUnpack(serverRes.text)
        val key = """key\s*="\s*(\d+)"""".toRegex().find(script)?.groupValues?.get(1) ?: return
        serverRes.document.select("ul li").amap { el ->
            val server = el.attr("data-value")
            val encryptedData = app.get("$url?server=$server&_=$unixTimeMS", cookies = gomoviesCookies ?: mapOf(), referer = url, headers = headers).text
            encryptedData.decrypt(key)?.forEach { video ->
                intArrayOf(2160, 1440, 1080, 720, 480, 360).filter { it <= video.max.toInt() }.forEach {
                    callback.invoke(newExtractorLink(name, name, video.src.split("360", limit = 3).joinToString(it.toString()), ExtractorLinkType.VIDEO) { this.referer = "$api/"; this.quality = it })
                }
            }
        }
    }

    // ================== ADIMOVIEBOX 2 SOURCE (NEW UPDATED) ==================
    suspend fun invokeAdimoviebox2(
        title: String,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://api3.aoneroom.com"
        val (brand, model) = Adimoviebox2Helper.randomBrandModel()

        suspend fun searchSubject(query: String): Adimoviebox2Subject? {
            val searchUrl = "$apiUrl/wefeed-mobile-bff/subject-api/search/v2"
            val jsonBody = """{"page": 1, "perPage": 10, "keyword": "$query"}"""
            val headersSearch = Adimoviebox2Helper.getHeaders(searchUrl, jsonBody, "POST", brand, model)
            val searchRes = app.post(searchUrl, headers = headersSearch, requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())).parsedSafe<Adimoviebox2SearchResponse>()
            return searchRes?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.find { subject ->
                val subjectYear = subject.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                val isTitleMatch = subject.title?.contains(query, true) == true
                val isYearMatch = year == null || subjectYear == year
                val isTypeMatch = if (season != null) subject.subjectType == 2 else (subject.subjectType == 1 || subject.subjectType == 3)
                isTitleMatch && isYearMatch && isTypeMatch
            }
        }

        var matchedSubject = searchSubject(title)
        if (matchedSubject == null && altTitle != null) {
            matchedSubject = searchSubject(altTitle)
        }
        if (matchedSubject == null) return

        val mainSubjectId = matchedSubject.subjectId ?: return
        
        // 2. Fetch Detail to get Languages/Dubs
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
            subjectList.add(mainSubjectId to "Original Audio")
        }

        val s = season ?: 0
        val e = episode ?: 0

        // 3. Loop through all language versions
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

    // ================== PLAYER4U SOURCE (UPDATED) ==================
    suspend fun invokePlayer4U(
        title: String? = null,
        altTitle: String? = null,
        season: Int? = null, episode: Int? = null, year: Int? = null, callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        suspend fun tryExtract(queryTitle: String): Set<Player4uLinkData> {
            val queryWithEpisode = season?.let { "$queryTitle S${"%02d".format(it)}E${"%02d".format(episode)}" }
            val baseQuery = queryWithEpisode ?: queryTitle.orEmpty()
            val encodedQuery = baseQuery.replace(" ", "+")
            val pageRange = 0..4
            val deferredPages = pageRange.map { page -> async {
                val url = "$Player4uApi/embed?key=$encodedQuery" + if (page > 0) "&page=$page" else ""
                runCatching { app.get(url, timeout = 20).document }.getOrNull()?.let { doc -> extractPlayer4uLinks(doc, season, episode, queryTitle, year) } ?: emptyList()
            } }
            val allLinks = deferredPages.awaitAll().flatten().toMutableSet()
            if (allLinks.isEmpty() && season == null) {
                val fallbackUrl = "$Player4uApi/embed?key=${queryTitle.replace(" ", "+")}"
                val fallbackDoc = runCatching { app.get(fallbackUrl, timeout = 20).document }.getOrNull()
                if (fallbackDoc != null) allLinks += extractPlayer4uLinks(fallbackDoc, season, episode, queryTitle, year)
            }
            return allLinks
        }

        var allLinks = title?.let { tryExtract(it) } ?: emptySet()
        if (allLinks.isEmpty() && altTitle != null) {
            allLinks = tryExtract(altTitle)
        }

        allLinks.distinctBy { it.name }.map { link -> async {
            try {
                val namePart = link.name.split("|").lastOrNull()?.trim().orEmpty()
                val displayName = buildString { append("Player4U"); if (namePart.isNotEmpty()) append(" {$namePart}") }
                val qualityMatch = Regex("""(\d{3,4}p|4K|CAM|HQ|HD|SD|WEBRip|DVDRip|BluRay|HDRip|TVRip|HDTC|PREDVD)""", RegexOption.IGNORE_CASE).find(displayName)?.value?.uppercase() ?: "UNKNOWN"
                val quality = getPlayer4UQuality(qualityMatch)
                val subPath = Regex("""go\('(.*?)'\)""").find(link.url)?.groupValues?.get(1) ?: return@async null
                val iframeSrc = runCatching { app.get("$Player4uApi$subPath", timeout = 10, referer = Player4uApi).document.selectFirst("iframe")?.attr("src") }.getOrNull() ?: return@async null
                getPlayer4uUrl(displayName, quality, "https://uqloads.xyz/e/$iframeSrc", Player4uApi, callback)
            } catch (_: Exception) { null }
        } }.awaitAll()
    }

    // Fungsi extractPlayer4uLinks tidak perlu diubah karena tidak menggunakan title secara langsung

    // ... fungsi lainnya yang tidak memerlukan altTitle tetap seperti semula (invokeRiveStream, invokeCinemaOS, dll.)
}
