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

    // ================== IDLIX SOURCE (UPDATED SESSION CLAIM) ==================
    suspend fun invokeIdlix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val idlixApi = "https://z1.idlixku.com" 
            val encodedQuery = URLEncoder.encode(title ?: return, "utf-8")
            val searchResText = app.get("$idlixApi/api/search?q=$encodedQuery").text
            val searchRes = tryParseJson<IdlixSearchResponse>(searchResText)
            val items = searchRes?.data ?: searchRes?.results ?: return
            
            val isSeries = season != null

            val matchedItem = items.find { 
                val titleMatch = it.title.equals(title, true) || it.originalTitle.equals(title, true)
                val typeMatch = if (isSeries) it.contentType?.contains("series", true) == true else it.contentType?.contains("movie", true) == true
                titleMatch && typeMatch
            } ?: items.firstOrNull() ?: return

            val slug = matchedItem.slug ?: return
            var contentType = "movie"
            var contentId = ""

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

            val randomDid = UUID.randomUUID().toString().replace("-", "")
            val refererUrl = "$idlixApi/${if (isSeries) "series" else "movie"}/$slug"
            val headers = mapOf(
                "Referer" to refererUrl, 
                "Origin" to idlixApi, 
                "Cookie" to "did=$randomDid; NEXT_LOCALE=id",
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
            )

            val playInfoResText = app.get(url = "$idlixApi/api/watch/play-info/$contentType/$contentId", headers = headers).text
            val playInfoRes = tryParseJson<PlayInfoResponse>(playInfoResText) ?: return

            val gateToken = playInfoRes.gateToken ?: return
            val serverNow = playInfoRes.serverNow ?: 0L
            val unlockAt = playInfoRes.unlockAt ?: 0L
            val countdownSec = playInfoRes.preroll?.countdownSec ?: 7L
            
            val diffTimeMs = unlockAt - serverNow
            val finalWaitMs = maxOf(countdownSec * 1000L, diffTimeMs) + 1000L
            
            delay(finalWaitMs)

            val jsonMediaType = "application/json".toMediaTypeOrNull()
            val requestBodyData = mapOf("gateToken" to gateToken).toJson().toRequestBody(jsonMediaType)
            
            val claimResText = app.post(url = "$idlixApi/api/watch/session/claim", headers = headers, requestBody = requestBodyData).text
            val claim = tryParseJson<SessionClaimResponse>(claimResText)?.claim ?: return

            val fakeUrl = "https://e2e.majorplay.net/play?claim=$claim"
            Majorplay().getUrl(fakeUrl, refererUrl, subtitleCallback, callback)

        } catch (e: Exception) { e.printStackTrace() }
    }

    // --- IDLIX DATA CLASSES (RESTORED & UPDATED) ---
    private data class PlayInfoResponse(
        @JsonProperty("gateToken") val gateToken: String? = null,
        @JsonProperty("serverNow") val serverNow: Long? = null,
        @JsonProperty("unlockAt") val unlockAt: Long? = null,
        @JsonProperty("preroll") val preroll: PrerollData? = null
    )
    private data class PrerollData(@JsonProperty("countdownSec") val countdownSec: Long? = null)
    private data class SessionClaimResponse(@JsonProperty("claim") val claim: String? = null)
    private data class IdlixSearchResponse(@JsonProperty("data") val data: List<IdlixContentData>? = null, @JsonProperty("results") val results: List<IdlixContentData>? = null)
    private data class IdlixContentData(@JsonProperty("slug") val slug: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("originalTitle") val originalTitle: String? = null, @JsonProperty("contentType") val contentType: String? = null)
    private data class IdlixDetailResponse(@JsonProperty("id") val id: String? = null)
    private data class IdlixSeasonApiResponse(@JsonProperty("season") val season: SeasonDetail? = null)
    private data class SeasonDetail(@JsonProperty("episodes") val episodes: List<EpisodeDetail>? = null)
    private data class EpisodeDetail(@JsonProperty("id") val id: String? = null, @JsonProperty("episodeNumber") val episodeNumber: Int? = null)

    // ================== ADIDEWASA SOURCE ==================
    @Suppress("UNCHECKED_CAST")
    suspend fun invokeAdiDewasa(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = "https://dramafull.cc"
        val cleanQuery = AdiDewasaHelper.normalizeQuery(title)
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
        val searchUrl = "$baseUrl/api/live-search/$encodedQuery"
        try {
            val searchRes = app.get(searchUrl, headers = AdiDewasaHelper.headers).parsedSafe<AdiDewasaSearchResponse>()
            val matchedItem = searchRes?.data?.find { item -> val itTitle = item.title ?: item.name ?: ""; AdiDewasaHelper.isFuzzyMatch(title, itTitle) } ?: searchRes?.data?.firstOrNull()
            if (matchedItem == null) return 
            val slug = matchedItem.slug ?: return
            var targetUrl = "$baseUrl/film/$slug"
            val doc = app.get(targetUrl, headers = AdiDewasaHelper.headers).document
            if (season != null && episode != null) {
                val epHref = doc.select("div.episode-item a, .episode-list a").find { val t = it.text().trim(); val n = Regex("""(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull(); n == episode }?.attr("href")
                if (epHref == null) return
                targetUrl = fixUrl(epHref, baseUrl)
            } else {
                doc.select("a.btn-watch, a.watch-now, .watch-button a, div.last-episode a, .film-buttons a.btn-primary").firstOrNull { it.attr("href").isNotEmpty() && !it.attr("href").contains("javascript") }?.let { targetUrl = fixUrl(it.attr("href"), baseUrl) }
            }
            val docPage = app.get(targetUrl, headers = AdiDewasaHelper.headers).document
            val allScripts = docPage.select("script").joinToString(" ") { it.data() }
            val signedUrl = Regex("""signedUrl\s*=\s*["']([^"']+)["']""").find(allScripts)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val jsonObject = tryParseJson<Map<String, Any>>(app.get(signedUrl, referer = targetUrl, headers = AdiDewasaHelper.headers).text) ?: return
            val videoSource = jsonObject["video_source"] as? Map<String, String> ?: return
            videoSource.forEach { (quality, url) -> if (url.isNotEmpty()) callback.invoke(newExtractorLink("AdiDewasa", "AdiDewasa ($quality)", url, INFER_TYPE)) }
            val bestKey = videoSource.keys.maxByOrNull { it.toIntOrNull() ?: 0 } ?: return
            ((jsonObject["sub"] as? Map<String, Any>)?.get(bestKey) as? List<String>)?.forEach { subtitleCallback.invoke(newSubtitleFile("English", fixUrl(it, baseUrl))) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ================== KISSKH SOURCE ==================
    suspend fun invokeKisskh(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://kisskh.ovh"
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="
        try {
            val searchResText = app.get("$mainUrl/api/DramaList/Search?q=$title&type=0").text
            val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchResText) ?: return
            val matched = searchList.find { it.title.equals(title, true) } ?: searchList.firstOrNull { it.title?.contains(title, true) == true } ?: return
            val dramaId = matched.id ?: return
            val detailRes = app.get("$mainUrl/api/DramaList/Drama/$dramaId?isq=false").parsedSafe<KisskhDetail>() ?: return
            val episodes = detailRes.episodes ?: return
            val targetEp = if (season == null) episodes.lastOrNull() else episodes.find { it.number?.toInt() == episode }
            val epsId = targetEp?.id ?: return
            val kkeyVideo = app.get("$KISSKH_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            val sources = app.get("$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=null&time=null&kkey=$kkeyVideo").parsedSafe<KisskhSources>()
            listOfNotNull(sources?.video, sources?.thirdParty).forEach { link ->
                if (link.contains(".m3u8")) M3u8Helper.generateM3u8("Kisskh", link, referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl)).forEach(callback)
                else if (link.contains(".mp4")) callback.invoke(newExtractorLink("Kisskh", "Kisskh", link, ExtractorLinkType.VIDEO) { this.referer = mainUrl })
            }
            val kkeySub = app.get("$KISSKH_SUB_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            tryParseJson<List<KisskhSubtitle>>(app.get("$mainUrl/api/Sub/$epsId?kkey=$kkeySub").text)?.forEach { sub -> subtitleCallback.invoke(newSubtitleFile(sub.label ?: "Unknown", sub.src ?: return@forEach)) }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    // --- KISSKH DATA CLASSES (RESTORED) ---
    private data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    private data class KisskhDetail(@JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    private data class KisskhKey(@JsonProperty("key") val key: String?)
    private data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    private data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)

    // ================== ADIMOVIEBOX (OLD) SOURCE ==================
    suspend fun invokeAdimoviebox(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://filmboom.top"
        val searchBody = mapOf("keyword" to title, "page" to "1", "perPage" to "0", "subjectType" to "0").toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val searchRes = app.post("$apiUrl/wefeed-h5-bff/web/subject/search", requestBody = searchBody).text
        val items = tryParseJson<AdimovieboxResponse>(searchRes)?.data?.items ?: return
        val matched = items.find { item -> val itYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull(); (item.title.equals(title, true)) || (item.title?.contains(title, true) == true && itYear == year) } ?: return
        val playRes = app.get("$apiUrl/wefeed-h5-bff/web/subject/play?subjectId=${matched.subjectId}&se=${season ?: 0}&ep=${episode ?: 0}", referer = "$apiUrl/spa/videoPlayPage/movies/${matched.detailPath}?id=${matched.subjectId}&type=/movie/detail&lang=en").text
        val streams = tryParseJson<AdimovieboxResponse>(playRes)?.data?.streams ?: return
        streams.reversed().distinctBy { it.url }.forEach { source -> callback.invoke(newExtractorLink("Adimoviebox", "Adimoviebox", source.url ?: return@forEach, INFER_TYPE) { this.referer = "$apiUrl/"; this.quality = getQualityFromName(source.resolutions) }) }
        streams.firstOrNull()?.let { first -> if (first.id != null) app.get("$apiUrl/wefeed-h5-bff/web/subject/caption?format=${first.format}&id=${first.id}&subjectId=${matched.subjectId}").parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub -> subtitleCallback.invoke(newSubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach)) } }
    }

    // ================== GOMOVIES SOURCE ==================
    suspend fun invokeGomovies(title: String? = null, year: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit) {
        invokeGpress(title, year, season, episode, callback, Adicinemax21.gomoviesAPI, "Gomovies", base64Decode("X3NtUWFtQlFzRVRi"), base64Decode("X3NCV2NxYlRCTWFU"))
    }
    private suspend fun invokeGpress(title: String? = null, year: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit, api: String, name: String, mediaSelector: String, episodeSelector: String) {
        fun String.decrypt(key: String): List<GpressSources>? { return tryParseJson<List<GpressSources>>(base64Decode(this).xorDecrypt(key)) }
        val slug = getEpisodeSlug(season, episode); val query = if (season == null) title else "$title Season $season"
        var res = app.get("$api/search/$query", cookies = mapOf("_identitygomovies7" to "5a436499900c81529e3740fd01c275b29d7e2fdbded7d760806877edb1f473e0a%3A2%3A%7Bi%3A0%3Bs%3A18%3A%22_identitygomovies7%22%3Bi%3A1%3Bs%3A52%3A%22%5B2800906%2C%22L2aGGTL9aqxksKR0pLvL66TunKNe1xXb%22%2C2592000%5D%22%3B%7D"))
        val cookies = gomoviesCookies ?: res.cookies.filter { it.key == "advanced-frontendgomovies7" }.also { gomoviesCookies = it }
        val media = res.document.select("div.$mediaSelector").map { Triple(it.attr("data-filmName"), it.attr("data-year"), it.select("a").attr("href")) }.let { el -> if (el.size == 1) el.firstOrNull() else el.find { if (season == null) (it.first.equals(title, true) || it.first.equals("$title ($year)", true)) && it.second.equals("$year") else it.first.equals("$title - Season $season", true) } ?: el.find { it.first.contains("$title", true) && it.second.equals("$year") } } ?: return
        val iframe = if (season == null) media.third else app.get(fixUrl(media.third, api)).document.selectFirst("div#$episodeSelector a:contains(Episode ${slug.second})")?.attr("href") ?: return
        res = app.get(fixUrl(iframe, api), cookies = cookies)
        val url = res.document.select("meta[property=og:url]").attr("content"); val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val (serverId, episodeId) = if (season == null) url.substringAfterLast("/") to "0" else url.substringBeforeLast("/").substringAfterLast("/") to url.substringAfterLast("/").substringBefore("-")
        val script = getAndUnpack(app.get("$api/user/servers/$serverId?ep=$episodeId", cookies = cookies, headers = headers).text); val key = """key\s*="\s*(\d+)"""".toRegex().find(script)?.groupValues?.get(1) ?: return
        intArrayOf(1).forEach { _ -> val encrypted = app.get("$url?server=1&_=$unixTimeMS", cookies = cookies, referer = url, headers = headers).text; encrypted.decrypt(key)?.forEach { video -> intArrayOf(2160, 1440, 1080, 720, 480, 360).filter { it <= video.max.toInt() }.forEach { callback.invoke(newExtractorLink(name, name, video.src.split("360", limit = 3).joinToString(it.toString()), ExtractorLinkType.VIDEO) { this.referer = "$api/"; this.quality = it }) } } }
    }

    // --- SISANYA TETAPKAN PERSIS SEPERTI ASLINYA (RESTORED ALL) ---
    suspend fun invokeVidsrccc(tmdbId: Int?, imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = if (season == null) "${Adicinemax21.vidsrcccAPI}/v2/embed/movie/$tmdbId" else "${Adicinemax21.vidsrcccAPI}/v2/embed/tv/$tmdbId/$season/$episode"
        val script = app.get(url).document.selectFirst("script:containsData(userId)")?.data() ?: return
        val userId = script.substringAfter("userId = \"").substringBefore("\";"); val v = script.substringAfter("v = \"").substringBefore("\";"); val vrf = VidsrcHelper.encryptAesCbc("$tmdbId", "secret_$userId")
        app.get(if (season == null) "${Adicinemax21.vidsrcccAPI}/api/$tmdbId/servers?id=$tmdbId&type=movie&v=$v&vrf=$vrf&imdbId=$imdbId" else "${Adicinemax21.vidsrcccAPI}/api/$tmdbId/servers?id=$tmdbId&type=tv&v=$v&vrf=$vrf&imdbId=$imdbId&season=$season&episode=$episode").parsedSafe<VidsrcccResponse>()?.data?.amap {
            val src = app.get("${Adicinemax21.vidsrcccAPI}/api/source/${it.hash}").parsedSafe<VidsrcccResult>()?.data ?: return@amap
            if (it.name.equals("VidPlay")) { callback.invoke(newExtractorLink("VidPlay", "VidPlay", src.source ?: return@amap, ExtractorLinkType.M3U8) { this.referer = "${Adicinemax21.vidsrcccAPI}/" }); src.subtitles?.map { sub -> subtitleCallback.invoke(newSubtitleFile(sub.label ?: return@map, sub.file ?: return@map)) } }
            else if (it.name.equals("UpCloud")) {
                val iframe = Regex("source\\s*=\\s*\"([^\"]+)").find(app.get(src.source ?: return@amap, referer = "${Adicinemax21.vidsrcccAPI}/").document.selectFirst("script:containsData(source =)")?.data() ?: return@amap)?.groupValues?.get(1)?.fixUrlBloat()
                val key = Regex("\\w{48}").find(app.get(iframe ?: return@amap, referer = "https://lucky.vidbox.site/").text)?.groupValues?.get(0) ?: return@amap
                app.get("${iframe.substringBeforeLast("/")}/getSources?id=${iframe.substringAfterLast("/").substringBefore("?")}&_k=$key", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), referer = iframe).parsedSafe<UpcloudResult>()?.sources?.amap { s -> callback.invoke(newExtractorLink("UpCloud", "UpCloud", s.file ?: return@amap, ExtractorLinkType.M3U8) { this.referer = "${Adicinemax21.vidsrcccAPI}/" }) }
            }
        }
    }

    suspend fun invokeVidsrc(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val api = "https://cloudnestra.com"; val url = if (season == null) "${Adicinemax21.vidSrcAPI}/embed/movie?imdb=$imdbId" else "${Adicinemax21.vidSrcAPI}/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
        app.get(url).document.select(".serversList .server").amap { srv -> if (srv.text().equals("CloudStream Pro", true)) { val hash = app.get("$api/rcp/${srv.attr("data-hash")}").text.substringAfter("/prorcp/").substringBefore("'"); Regex("https:.*\\.m3u8").find(app.get("$api/prorcp/$hash").text)?.value?.let { callback.invoke(newExtractorLink("Vidsrc", "Vidsrc", it, ExtractorLinkType.M3U8)) } } }
    }

    suspend fun invokeAdimoviebox2(title: String, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val api = "https://api3.aoneroom.com"; val (brand, model) = Adimoviebox2Helper.randomBrandModel()
        val searchBody = """{"page": 1, "perPage": 10, "keyword": "$title"}"""
        val search = app.post("$api/wefeed-mobile-bff/subject-api/search/v2", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/search/v2", searchBody, "POST", brand, model), requestBody = searchBody.toRequestBody("application/json".toMediaTypeOrNull())).parsedSafe<Adimoviebox2SearchResponse>()
        val mainId = search?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.find { val y = it.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull(); it.title?.contains(title, true) == true && (year == null || y == year) && (if (season != null) it.subjectType == 2 else it.subjectType == 1 || it.subjectType == 3) }?.subjectId ?: return
        val list = mutableListOf<Pair<String, String>>(); try { val data = JSONObject(app.get("$api/wefeed-mobile-bff/subject-api/get?subjectId=$mainId", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/get?subjectId=$mainId", null, "GET", brand, model)).text).optJSONObject("data"); list.add(mainId to "Original Audio"); val dubs = data?.optJSONArray("dubs"); if (dubs != null) for (i in 0 until dubs.length()) { val dId = dubs.optJSONObject(i)?.optString("subjectId"); if (!dId.isNullOrEmpty() && dId != mainId) list.add(dId to (dubs.optJSONObject(i)?.optString("lanName") ?: "Dub")) } } catch (e: Exception) { list.add(mainId to "Original Audio") }
        list.forEach { (cId, lName) -> val play = app.get("$api/wefeed-mobile-bff/subject-api/play-info?subjectId=$cId&se=${season ?: 0}&ep=${episode ?: 0}", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/play-info?subjectId=$cId&se=${season ?: 0}&ep=${episode ?: 0}", null, "GET", brand, model)).parsedSafe<Adimoviebox2PlayResponse>(); play?.data?.streams?.forEach { s -> callback.invoke(newExtractorLink("Adimoviebox2 ($lName)", "Adimoviebox2 ($lName)", s.url ?: return@forEach, if (s.url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = getQualityFromName(s.resolutions); this.headers = Adimoviebox2Helper.getHeaders(s.url, null, "GET", brand, model).toMutableMap().apply { if (!s.signCookie.isNullOrEmpty()) put("Cookie", s.signCookie) } }); if (s.id != null) { app.get("$api/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$cId&streamId=${s.id}", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$cId&streamId=${s.id}", null, "GET", brand, model)).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap -> subtitleCallback.invoke(newSubtitleFile("${cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"} ($lName)", cap.url ?: return@forEach)) } } } }
    }

    private object Adimoviebox2Helper {
        private val secret = android.util.Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", android.util.Base64.DEFAULT); private val dId = (1..16).map { "0123456789abcdef".random() }.joinToString("")
        fun randomBrandModel() = mapOf("Samsung" to listOf("SM-S918B", "SM-A528B"), "Xiaomi" to listOf("2201117TI", "M2012K11AI"), "Google" to listOf("Pixel 7", "Pixel 8")).let { m -> val b = m.keys.random(); b to m[b]!!.random() }
        fun getHeaders(url: String, body: String?, method: String, brand: String, model: String): Map<String, String> {
            val t = System.currentTimeMillis(); val xTok = "$t,${MessageDigest.getInstance("MD5").digest(t.toString().reversed().toByteArray()).joinToString("") { "%02x".format(it) }}"; val p = Uri.parse(url); val q = if (p.queryParameterNames.isNotEmpty()) p.queryParameterNames.sorted().joinToString("&") { k -> p.getQueryParameters(k).joinToString("&") { "$k=$it" } } else ""; val bBytes = body?.toByteArray(Charsets.UTF_8); val bHash = if (bBytes != null) MessageDigest.getInstance("MD5").digest(if (bBytes.size > 102400) bBytes.copyOfRange(0, 102400) else bBytes).joinToString("") { "%02x".format(it) } else ""; val sig = Mac.getInstance("HmacMD5").apply { init(SecretKeySpec(secret, "HmacMD5")) }.doFinal("${method.uppercase()}\napplication/json\n${if(method=="POST") "application/json; charset=utf-8" else "application/json"}\n${bBytes?.size?.toString() ?: ""}\n$t\n$bHash\n${if (q.isNotEmpty()) "${p.path}?$q" else p.path ?: ""}".toByteArray(Charsets.UTF_8)).let { android.util.Base64.encodeToString(it, android.util.Base64.DEFAULT).trim() }
            return mapOf("user-agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; $model; Build/BP22.250325.006; Cronet/133.0.6876.3)", "accept" to "application/json", "content-type" to "application/json", "x-client-token" to xTok, "x-tr-signature" to "$t|2|$sig", "x-client-info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"$dId","install_store":"ps","gaid":"d7578036d13336cc","brand":"$brand","model":"$model","system_language":"en","net":"NETWORK_WIFI","region":"IN","timezone":"Asia/Calcutta","sp_code":""}""", "x-client-status" to "0")
        }
    }
}
