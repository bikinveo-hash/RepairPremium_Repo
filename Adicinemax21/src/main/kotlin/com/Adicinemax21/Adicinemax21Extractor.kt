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
            val matchedItem = searchRes?.data?.find { item ->
                val itemTitle = item.title ?: item.name ?: ""
                AdiDewasaHelper.isFuzzyMatch(title, itemTitle)
            } ?: searchRes?.data?.firstOrNull()

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
            videoSource.forEach { (quality, url) -> if (url.isNotEmpty()) callback.invoke(newExtractorLink("AdiDewasa", "AdiDewasa ($quality)", url, INFER_TYPE)) }
             
            val bestQualityKey = videoSource.keys.maxByOrNull { it.toIntOrNull() ?: 0 } ?: return
            val subs = (jsonObject["sub"] as? Map<String, Any>)?.get(bestQualityKey) as? List<String>
            subs?.forEach { subPath -> subtitleCallback.invoke(newSubtitleFile("English", fixUrl(subPath, baseUrl))) }
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
            val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$title&type=0").text
            val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes) ?: return
            val matched = searchList.find { it.title.equals(title, true) } ?: searchList.firstOrNull { it.title?.contains(title, true) == true } ?: return
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
            tryParseJson<List<KisskhSubtitle>>(subJson)?.forEach { sub -> subtitleCallback.invoke(newSubtitleFile(sub.label ?: "Unknown", sub.src ?: return@forEach)) }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    // ================== ADIMOVIEBOX SOURCE ==================
    suspend fun invokeAdimoviebox(
        title: String, year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val apiUrl = "https://filmboom.top"
        val searchBody = mapOf("keyword" to title, "page" to "1", "perPage" to "0", "subjectType" to "0").toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val searchRes = app.post("$apiUrl/wefeed-h5-bff/web/subject/search", requestBody = searchBody).text
        val items = tryParseJson<AdimovieboxResponse>(searchRes)?.data?.items ?: return
        val matchedMedia = items.find { item -> val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull(); (item.title.equals(title, true)) || (item.title?.contains(title, true) == true && itemYear == year) } ?: return
        val subjectId = matchedMedia.subjectId ?: return
        val se = season ?: 0; val ep = episode ?: 0
        val playRes = app.get("$apiUrl/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=$se&ep=$ep", referer = "$apiUrl/spa/videoPlayPage/movies/${matchedMedia.detailPath}?id=$subjectId&type=/movie/detail&lang=en").text
        val streams = tryParseJson<AdimovieboxResponse>(playRes)?.data?.streams ?: return
        streams.reversed().distinctBy { it.url }.forEach { source -> callback.invoke(newExtractorLink("Adimoviebox", "Adimoviebox", source.url ?: return@forEach, INFER_TYPE) { this.referer = "$apiUrl/"; this.quality = getQualityFromName(source.resolutions) }) }
        val first = streams.firstOrNull()
        if (first?.id != null) {
            app.get("$apiUrl/wefeed-h5-bff/web/subject/caption?format=${first.format}&id=${first.id}&subjectId=$subjectId").parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub -> subtitleCallback.invoke(newSubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach)) }
        }
    }

    // ================== GOMOVIES SOURCE ==================
    suspend fun invokeGomovies(title: String? = null, year: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit) {
        invokeGpress(title, year, season, episode, callback, Adicinemax21.gomoviesAPI, "Gomovies", base64Decode("X3NtUWFtQlFzRVRi"), base64Decode("X3NCV2NxYlRCTWFU"))
    }
    private suspend fun invokeGpress(title: String? = null, year: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit, api: String, name: String, mediaSelector: String, episodeSelector: String) {
        fun String.decrypt(key: String): List<GpressSources>? { return tryParseJson<List<GpressSources>>(base64Decode(this).xorDecrypt(key)) }
        val slug = getEpisodeSlug(season, episode); val query = if (season == null) title else "$title Season $season"
        var res = app.get("$api/search/$query", cookies = mapOf("_identitygomovies7" to """5a436499900c81529e3740fd01c275b29d7e2fdbded7d760806877edb1f473e0a%3A2%3A%7Bi%3A0%3Bs%3A18%3A%22_identitygomovies7%22%3Bi%3A1%3Bs%3A52%3A%22%5B2800906%2C%22L2aGGTL9aqxksKR0pLvL66TunKNe1xXb%22%2C2592000%5D%22%3B%7D"""))
        val cookies = gomoviesCookies ?: res.cookies.filter { it.key == "advanced-frontendgomovies7" }.also { gomoviesCookies = it }
        val media = res.document.select("div.$mediaSelector").map { Triple(it.attr("data-filmName"), it.attr("data-year"), it.select("a").attr("href")) }.let { el -> if (el.size == 1) el.firstOrNull() else el.find { if (season == null) (it.first.equals(title, true) || it.first.equals("$title ($year)", true)) && it.second.equals("$year") else it.first.equals("$title - Season $season", true) } ?: el.find { it.first.contains("$title", true) && it.second.equals("$year") } } ?: return
        val iframe = if (season == null) media.third else app.get(fixUrl(media.third, api)).document.selectFirst("div#$episodeSelector a:contains(Episode ${slug.second})")?.attr("href") ?: return
        res = app.get(fixUrl(iframe, api), cookies = cookies)
        val url = res.document.select("meta[property=og:url]").attr("content"); val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        val (serverId, episodeId) = if (season == null) url.substringAfterLast("/") to "0" else url.substringBeforeLast("/").substringAfterLast("/") to url.substringAfterLast("/").substringBefore("-")
        val serverRes = app.get("$api/user/servers/$serverId?ep=$episodeId", cookies = cookies, headers = headers)
        val script = getAndUnpack(serverRes.text); val key = """key\s*="\s*(\d+)"""".toRegex().find(script)?.groupValues?.get(1) ?: return
        serverRes.document.select("ul li").amap { el ->
            val server = el.attr("data-value"); val encryptedData = app.get("$url?server=$server&_=$unixTimeMS", cookies = cookies, referer = url, headers = headers).text
            encryptedData.decrypt(key)?.forEach { video -> intArrayOf(2160, 1440, 1080, 720, 480, 360).filter { it <= video.max.toInt() }.forEach { callback.invoke(newExtractorLink(name, name, video.src.split("360", limit = 3).joinToString(it.toString()), ExtractorLinkType.VIDEO) { this.referer = "$api/"; this.quality = it }) } }
        }
    }

    // ================== OTHER SOURCES (RESTORED EXACTLY) ==================
    suspend fun invokeVidsrccc(tmdbId: Int?, imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val url = if (season == null) "${Adicinemax21.vidsrcccAPI}/v2/embed/movie/$tmdbId" else "${Adicinemax21.vidsrcccAPI}/v2/embed/tv/$tmdbId/$season/$episode"
        val script = app.get(url).document.selectFirst("script:containsData(userId)")?.data() ?: return
        val userId = script.substringAfter("userId = \"").substringBefore("\";"); val v = script.substringAfter("v = \"").substringBefore("\";"); val vrf = VidsrcHelper.encryptAesCbc("$tmdbId", "secret_$userId")
        val serverUrl = if (season == null) "${Adicinemax21.vidsrcccAPI}/api/$tmdbId/servers?id=$tmdbId&type=movie&v=$v&vrf=$vrf&imdbId=$imdbId" else "${Adicinemax21.vidsrcccAPI}/api/$tmdbId/servers?id=$tmdbId&type=tv&v=$v&vrf=$vrf&imdbId=$imdbId&season=$season&episode=$episode"
        app.get(serverUrl).parsedSafe<VidsrcccResponse>()?.data?.amap {
            val sources = app.get("${Adicinemax21.vidsrcccAPI}/api/source/${it.hash}").parsedSafe<VidsrcccResult>()?.data ?: return@amap
            if (it.name.equals("VidPlay")) { callback.invoke(newExtractorLink("VidPlay", "VidPlay", sources.source ?: return@amap, ExtractorLinkType.M3U8) { this.referer = "${Adicinemax21.vidsrcccAPI}/" }); sources.subtitles?.map { subtitleCallback.invoke(newSubtitleFile(it.label ?: return@map, it.file ?: return@map)) } }
            else if (it.name.equals("UpCloud")) {
                val iframe = Regex("source\\s*=\\s*\"([^\"]+)").find(app.get(sources.source ?: return@amap, referer = "${Adicinemax21.vidsrcccAPI}/").document.selectFirst("script:containsData(source =)")?.data() ?: return@amap)?.groupValues?.get(1)?.fixUrlBloat()
                val key = Regex("\\w{48}").find(app.get(iframe ?: return@amap, referer = "https://lucky.vidbox.site/").text)?.groupValues?.get(0) ?: return@amap
                app.get("${iframe.substringBeforeLast("/")}/getSources?id=${iframe.substringAfterLast("/").substringBefore("?")}&_k=$key", headers = mapOf("X-Requested-With" to "XMLHttpRequest"), referer = iframe).parsedSafe<UpcloudResult>()?.sources?.amap { source -> callback.invoke(newExtractorLink("UpCloud", "UpCloud", source.file ?: return@amap, ExtractorLinkType.M3U8) { this.referer = "${Adicinemax21.vidsrcccAPI}/" }) }
            }
        }
    }

    suspend fun invokeVidsrc(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val api = "https://cloudnestra.com"; val url = if (season == null) "${Adicinemax21.vidSrcAPI}/embed/movie?imdb=$imdbId" else "${Adicinemax21.vidSrcAPI}/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
        app.get(url).document.select(".serversList .server").amap { server -> if (server.text().equals("CloudStream Pro", true)) { val hash = app.get("$api/rcp/${server.attr("data-hash")}").text.substringAfter("/prorcp/").substringBefore("'"); Regex("https:.*\\.m3u8").find(app.get("$api/prorcp/$hash").text)?.value?.let { callback.invoke(newExtractorLink("Vidsrc", "Vidsrc", it, ExtractorLinkType.M3U8)) } } }
    }

    suspend fun invokeXprime(tmdbId: Int?, title: String? = null, year: Int? = null, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val referer = "https://xprime.tv/"; runAllAsync(
            { val url = if (season == null) "${Adicinemax21.xprimeAPI}/rage?id=$tmdbId" else "${Adicinemax21.xprimeAPI}/rage?id=$tmdbId&season=$season&episode=$episode"; callback.invoke(newExtractorLink("Rage", "Rage", app.get(url).parsedSafe<RageSources>()?.url ?: return@runAllAsync, ExtractorLinkType.M3U8) { this.referer = referer }) },
            { val url = if (season == null) "${Adicinemax21.xprimeAPI}/primebox?name=$title&fallback_year=$year" else "${Adicinemax21.xprimeAPI}/primebox?name=$title&fallback_year=$year&season=$season&episode=$episode"
                val sources = app.get(url).parsedSafe<PrimeboxSources>(); sources?.streams?.map { source -> callback.invoke(newExtractorLink("Primebox", "Primebox", source.value, ExtractorLinkType.M3U8) { this.referer = referer; this.quality = getQualityFromName(source.key) }) }; sources?.subtitles?.map { subtitle -> subtitleCallback.invoke(newSubtitleFile(subtitle.label ?: "", subtitle.file ?: return@map)) } }
        )
    }

    suspend fun invokeWatchsomuch(imdbId: String? = null, season: Int? = null, episode: Int? = null, subtitleCallback: (SubtitleFile) -> Unit) {
        val id = imdbId?.removePrefix("tt"); val epsId = app.post("${Adicinemax21.watchSomuchAPI}/Watch/ajMovieTorrents.aspx", data = mapOf("index" to "0", "mid" to "$id", "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45", "lid" to "", "liu" to ""), headers = mapOf("X-Requested-With" to "XMLHttpRequest")).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps -> if (season == null) eps.firstOrNull()?.id else eps.find { it.episode == episode && it.season == season }?.id } ?: return
        val (sSlug, eSlug) = getEpisodeSlug(season, episode); app.get(if (season == null) "${Adicinemax21.watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=" else "${Adicinemax21.watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${sSlug}E${eSlug}").parsedSafe<WatchsomuchSubResponses>()?.subtitles?.map { sub -> subtitleCallback.invoke(newSubtitleFile(sub.label?.substringBefore("&nbsp")?.trim() ?: "", fixUrl(sub.url ?: return@map null, Adicinemax21.watchSomuchAPI))) }
    }

    suspend fun invokeMapple(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val type = if (season == null) "movie" else "tv"; val url = if (season == null) "${Adicinemax21.mappleAPI}/watch/$type/$tmdbId" else "${Adicinemax21.mappleAPI}/watch/$type/$season-$episode/$tmdbId"
        val video = tryParseJson<MappleSources>(app.post(url, requestBody = (if (season == null) """[{"mediaId":$tmdbId,"mediaType":"$type","tv_slug":"","source":"mapple","sessionId":"session_1760391974726_qym92bfxu"}]""" else """[{"mediaId":$tmdbId,"mediaType":"$type","tv_slug":"$season-$episode","source":"mapple","sessionId":"session_1760391974726_qym92bfxu"}]""").toRequestBody("text/plain".toMediaTypeOrNull()), headers = mapOf("Next-Action" to "403f7ef15810cd565978d2ac5b7815bb0ff20258a5")).text.substringAfter("1:").trim())?.data?.stream_url
        callback.invoke(newExtractorLink("Mapple", "Mapple", video ?: return, ExtractorLinkType.M3U8) { this.referer = "${Adicinemax21.mappleAPI}/"; this.headers = mapOf("Accept" to "*/*") })
        tryParseJson<ArrayList<MappleSubtitle>>(app.get("${Adicinemax21.mappleAPI}/api/subtitles?id=$tmdbId&mediaType=$type${if (season == null) "" else "&season=1&episode=1"}", referer = "${Adicinemax21.mappleAPI}/").text)?.map { sub -> subtitleCallback.invoke(newSubtitleFile(sub.display ?: "", fixUrl(sub.url ?: return@map, Adicinemax21.mappleAPI))) }
    }

    suspend fun invokeVidlink(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val type = if (season == null) "movie" else "tv"; val url = if (season == null) "${Adicinemax21.vidlinkAPI}/$type/$tmdbId" else "${Adicinemax21.vidlinkAPI}/$type/$tmdbId/$season/$episode"
        callback.invoke(newExtractorLink("Vidlink", "Vidlink", app.get(url, interceptor = WebViewResolver(Regex("""${Adicinemax21.vidlinkAPI}/api/b/$type/A{32}"""), timeout = 15_000L)).parsedSafe<VidlinkSources>()?.stream?.playlist ?: return, ExtractorLinkType.M3U8) { this.referer = "${Adicinemax21.vidlinkAPI}/" })
    }

    suspend fun invokeVidfast(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val type = if (season == null) "movie" else "tv"; val url = if (season == null) "${Adicinemax21.vidfastAPI}/$type/$tmdbId" else "${Adicinemax21.vidfastAPI}/$type/$tmdbId/$season/$episode"
        tryParseJson<ArrayList<VidFastServers>>(app.get(url, interceptor = WebViewResolver(Regex("""${Adicinemax21.vidfastAPI}/hezushon/1000076901076321/0b0ce221/cfe60245-021f-5d4d-bacb-0d469f83378f/uva/jeditawev/b0535941d898ebdb81f575b2cfd123f5d18c6464/y/APA91zAOxU2psY2_BvBqEmmjG6QvCoLjgoaI-xuoLxBYghvzgKAu-HtHNeQmwxNbHNpoVnCuX10eEes1lnTcI2l_lQApUiwfx2pza36CZB34X7VY0OCyNXtlq-bGVCkLslfNksi1k3B667BJycQ67wxc1OnfCc5PDPrF0BA8aZRyMXZ3-2yxVGp/JEwECseLZdY"""), timeout = 15_000L)).text)?.filter { it.description?.contains("Original audio") == true }?.amapIndexed { idx, server -> val source = app.get("${Adicinemax21.vidfastAPI}/hezushon/1000076901076321/0b0ce221/cfe60245-021f-5d4d-bacb-0d469f83378f/uva/jeditawev/b0535941d898ebdb81f575b2cfd123f5d18c6464/y/APA91zAOxU2psY2_BvBqEmmjG6QvCoLjgoaI-xuoLxBYghvzgKAu-HtHNeQmwxNbHNpoVnCuX10eEes1lnTcI2l_lQApUiwfx2pza36CZB34X7VY0OCyNXtlq-bGVCkLslfNksi1k3B667BJycQ67wxc1OnfCc5PDPrF0BA8aZRyMXZ3-2yxVGp/Sdoi/${server.data}", referer = "${Adicinemax21.vidfastAPI}/").parsedSafe<VidFastSources>() ?: return@amapIndexed; callback.invoke(newExtractorLink("Vidfast", "Vidfast [${server.name}]", source.url ?: return@amapIndexed, INFER_TYPE)); if (idx == 1) source.tracks?.map { sub -> subtitleCallback.invoke(newSubtitleFile(sub.label ?: return@map, sub.file ?: return@map)) } }
    }

    suspend fun invokeWyzie(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit) {
        tryParseJson<ArrayList<WyzieSubtitle>>(app.get(if (season == null) "${Adicinemax21.wyzieAPI}/search?id=$tmdbId" else "${Adicinemax21.wyzieAPI}/search?id=$tmdbId&season=$season&episode=$episode").text)?.map { sub -> subtitleCallback.invoke(newSubtitleFile(sub.display ?: return@map, sub.url ?: return@map)) }
    }

    suspend fun invokeVixsrc(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val proxy = "https://proxy.heistotron.uk"; val url = if (season == null) "${Adicinemax21.vixsrcAPI}/movie/$tmdbId" else "${Adicinemax21.vixsrcAPI}/tv/$tmdbId/$season/$episode"
        val script = app.get(url).document.selectFirst("script:containsData(window.masterPlaylist)")?.data() ?: return
        val video1 = Regex("""'token':\s*'(\w+)'[\S\s]+'expires':\s*'(\w+)'[\S\s]+url:\s*'(\S+)'""").find(script)?.let { val (token, exp, path) = it.destructured; "$path?token=$token&expires=$exp&h=1&lang=en" } ?: return
        listOf(VixsrcSource("Vixsrc [Alpha]", video1, url), VixsrcSource("Vixsrc [Beta]", "$proxy/p/${base64Encode("$proxy/api/proxy/m3u8?url=${encode(video1)}&source=sakura|ananananananananaBatman!".toByteArray())}", "${Adicinemax21.mappleAPI}/")).map { callback.invoke(newExtractorLink(it.name, it.name, it.url, ExtractorLinkType.M3U8) { this.referer = it.referer; this.headers = mapOf("Accept" to "*/*") }) }
    }

    suspend fun invokeSuperembed(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, api: String = "https://streamingnow.mov") {
        val token = app.get("${Adicinemax21.superembedAPI}/directstream.php?video_id=$tmdbId&tmdb=1${if (season == null) "" else "&s=$season&e=$episode"}").url.substringAfter("?play=")
        val li = app.post("$api/response.php", data = mapOf("token" to token), headers = mapOf("X-Requested-With" to "XMLHttpRequest")).document.select("ul.sources-list li:contains(vipstream-S)")
        val playUrl = "$api/playvideo.php?video_id=${li.attr("data-id")}&server_id=${li.attr("data-server")}&token=$token&init=1"
        val iframe = (app.get(playUrl).document.selectFirst("iframe.source-frame") ?: app.post(playUrl, requestBody = "captcha_id=TEduRVR6NmZ3Sk5Jc3JpZEJCSlhTM25GREs2RCswK0VQN2ZsclI5KzNKL2cyV3dIaFEwZzNRRHVwMzdqVmoxV0t2QlBrNjNTY04wY2NSaHlWYS9Jc09nb25wZTV2YmxDSXNRZVNuQUpuRW5nbkF2dURsQUdJWVpwOWxUZzU5Tnh0NXllQjdYUG83Y0ZVaG1XRGtPOTBudnZvN0RFK0wxdGZvYXpFKzVNM2U1a2lBMG40REJmQ042SA%3D%3D&captcha_answer%5B%5D=8yhbjraxqf3o&captcha_answer%5B%5D=10zxn5vi746w&captcha_answer%5B%5D=gxfpe17tdwub".toRequestBody("text/plain".toMediaTypeOrNull())).document.selectFirst("iframe.source-frame"))?.attr("src") ?: return
        val json = app.get(iframe).text.substringAfter("Playerjs(").substringBefore(");")
        callback.invoke(newExtractorLink("Superembed", "Superembed", """file:"([^"]+)""".toRegex().find(json)?.groupValues?.get(1) ?: return, INFER_TYPE) { this.headers = mapOf("Accept" to "*/*") })
        """subtitle:"([^"]+)""".toRegex().find(json)?.groupValues?.get(1)?.split(",")?.map { val match = Regex("""\[(\w+)](http\S+)""").find(it) ?: return@map; subtitleCallback.invoke(newSubtitleFile(match.groupValues[1].trim(), match.groupValues[2].trim())) }
    }

    suspend fun invokeVidrock(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, subAPI: String = "https://sub.vdrk.site") {
        val type = if (season == null) "movie" else "tv"; val url = "${Adicinemax21.vidrockAPI}/$type/$tmdbId${if (season == null) "" else "/$season/$episode"}"
        app.get("${Adicinemax21.vidrockAPI}/api/$type/${VidrockHelper.encrypt(tmdbId, type, season, episode)}", referer = url).parsedSafe<LinkedHashMap<String, HashMap<String, String>>>()?.map { source -> if (source.key == "source2") tryParseJson<ArrayList<VidrockSource>>(app.get(source.value["url"] ?: return@map, referer = "${Adicinemax21.vidrockAPI}/").text)?.reversed()?.map { callback.invoke(newExtractorLink("Vidrock", "Vidrock [Source2]", it.url ?: return@map, INFER_TYPE) { this.quality = it.resolution ?: Qualities.Unknown.value; this.headers = mapOf("Range" to "bytes=0-", "Referer" to "${Adicinemax21.vidrockAPI}/") }) } else callback.invoke(newExtractorLink("Vidrock", "Vidrock [${source.key.capitalize()}]", source.value["url"] ?: return@map, ExtractorLinkType.M3U8) { this.referer = "${Adicinemax21.vidrockAPI}/"; this.headers = mapOf("Origin" to Adicinemax21.vidrockAPI) }) }
        tryParseJson<ArrayList<VidrockSubtitle>>(app.get("$subAPI/$type/$tmdbId${if (season == null) "" else "/$season/$episode"}").text)?.map { sub -> subtitleCallback.invoke(newSubtitleFile(sub.label?.replace(Regex("\\d"), "")?.replace(Regex("\\s+Hi"), "")?.trim() ?: return@map, sub.file ?: return@map)) }
    }

    suspend fun invokeCinemaOS(imdbId: String? = null, tmdbId: Int? = null, title: String? = null, season: Int? = null, episode: Int? = null, year: Int? = null, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {
        val headers = mapOf("Accept" to "*/*", "Referer" to cinemaOSApi, "Origin" to cinemaOSApi, "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Content-Type" to "application/json")
        val secret = cinemaOSGenerateHash(CinemaOsSecretKeyRequest(tmdbId.toString(), season?.toString() ?: "", episode?.toString() ?: ""), season != null)
        val type = if (season == null) "movie" else "tv"; val url = if (season == null) "$cinemaOSApi/api/fuckit?type=$type&tmdbId=$tmdbId&imdbId=$imdbId&t=${title?.replace(" ","+")}&ry=$year&secret=$secret" else "$cinemaOSApi/api/fuckit?type=$type&tmdbId=$tmdbId&imdbId=$imdbId&seasonId=$season&episodeId=$episode&t=${title?.replace(" ","+")}&ry=$year&secret=$secret"
        try { parseCinemaOSSources(cinemaOSDecryptResponse(app.get(url, headers = headers, timeout = 60).parsedSafe<CinemaOSReponse>()?.data).toString()).filter { val srv = it["server"] ?: ""; !listOf("Maphisto", "Noah", "Bolt", "Zeus", "Nexus", "Apollo", "Kratos", "Flick", "Hollywood", "Flash", "Ophim", "Bollywood", "Apex", "Universe", "Hindi", "Bengali", "Tamil", "Telugu").filter { !it.equals("Rizz", true) }.any { srv.contains(it, true) } }.sortedByDescending { (it["server"] ?: "").contains("Rizz", true) }.forEach { val q = if (it["quality"]?.toIntOrNull() != null) getQualityFromName(it["quality"]) else Qualities.P1080.value; callback.invoke(newExtractorLink("CinemaOS [${it["server"]}]", "CinemaOS [${it["server"]}] ${it["bitrate"]}", it["url"].toString(), when { it["type"]?.contains("hls", true) == true -> ExtractorLinkType.M3U8; it["type"]?.contains("dash", true) == true -> ExtractorLinkType.DASH; else -> ExtractorLinkType.VIDEO }) { this.headers = mapOf("Referer" to cinemaOSApi); this.quality = q }) } } catch (e: Exception) {}
    }

    suspend fun invokePlayer4U(title: String? = null, season: Int? = null, episode: Int? = null, year: Int? = null, callback: (ExtractorLink) -> Unit) = coroutineScope {
        val query = season?.let { "$title S${"%02d".format(it)}E${"%02d".format(episode)}" } ?: title.orEmpty(); val encoded = query.replace(" ", "+")
        val links = (0..4).map { page -> async { runCatching { app.get("$Player4uApi/embed?key=$encoded${if (page > 0) "&page=$page" else ""}", timeout = 20).document }.getOrNull()?.let { extractPlayer4uLinks(it, season, episode, title.toString(), year) } ?: emptyList() } }.awaitAll().flatten().toMutableSet()
        if (links.isEmpty() && season == null) runCatching { app.get("$Player4uApi/embed?key=${title?.replace(" ", "+")}", timeout = 20).document }.getOrNull()?.let { links += extractPlayer4uLinks(it, season, episode, title.toString(), year) }
        links.distinctBy { it.name }.map { link -> async { try { val sub = Regex("""go\('(.*?)'\)""").find(link.url)?.groupValues?.get(1) ?: return@async null; val iframe = runCatching { app.get("$Player4uApi$sub", timeout = 10, referer = Player4uApi).document.selectFirst("iframe")?.attr("src") }.getOrNull() ?: return@async null; val display = if (link.name.split("|").last().trim().isNotEmpty()) "Player4U {${link.name.split("|").last().trim()}}" else "Player4U"; getPlayer4uUrl(display, getPlayer4UQuality(Regex("""(\d{3,4}p|4K|CAM|HQ|HD|SD|WEBRip|DVDRip|BluRay|HDRip|TVRip|HDTC|PREDVD)""", RegexOption.IGNORE_CASE).find(display)?.value?.uppercase() ?: "UNKNOWN"), "https://uqloads.xyz/e/$iframe", Player4uApi, callback) } catch (_: Exception) { null } } }.awaitAll()
    }

    private fun extractPlayer4uLinks(document: Document, season:Int?, episode:Int?, title:String, year:Int?): List<Player4uLinkData> {
        return document.select(".playbtnx").mapNotNull { el -> val txt = el.text()?.split(" | ")?.last() ?: return@mapNotNull null; if (season == null && episode == null) { if (year != null && (txt.startsWith("$title $year", true) || txt.startsWith("$title ($year)", true))) Player4uLinkData(txt, el.attr("onclick")) else null } else { if (season != null && episode != null && txt.startsWith("$title S${"%02d".format(season)}E${"%02d".format(episode)}", true)) Player4uLinkData(txt, el.attr("onclick")) else null } }
    }

    suspend fun invokeRiveStream(id: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit) {
        val h = mapOf("User-Agent" to USER_AGENT); suspend fun <T> retry(b: suspend () -> T): T? { repeat(2) { try { return b() } catch (_: Exception) {} }; return try { b() } catch (_: Exception) { null } }
        val sourceList = retry { app.get("$RiveStreamAPI/api/backendfetch?requestID=VideoProviderServices&secretKey=rive", h).parsedSafe<RiveStreamSource>() }
        val js = retry { app.get("$RiveStreamAPI${retry { app.get(RiveStreamAPI, h, 20).document }?.select("script")?.firstOrNull { it.attr("src").contains("_app") }?.attr("src") ?: return}").text } ?: return
        val kList = Regex("""let\s+c\s*=\s*(\[[^]]*])""").findAll(js).firstOrNull { it.groupValues[1].length > 2 }?.groupValues?.get(1)?.let { a -> Regex("\"([^\"]+)\"").findAll(a).map { it.groupValues[1] }.toList() } ?: emptyList()
        val sKey = retry { app.get("https://rivestream.supe2372.workers.dev/?input=$id&cList=${kList.joinToString(",")}").text } ?: return
        sourceList?.data?.forEach { s -> try { val res = JSONObject(retry { app.get(if (season == null) "$RiveStreamAPI/api/backendfetch?requestID=movieVideoProvider&id=$id&service=$s&secretKey=$sKey" else "$RiveStreamAPI/api/backendfetch?requestID=tvVideoProvider&id=$id&season=$season&episode=$episode&service=$s&secretKey=$sKey", h, 10).text } ?: return@forEach); val arr = res.optJSONObject("data")?.optJSONArray("sources") ?: return@forEach; for (i in 0 until arr.length()) { val src = arr.getJSONObject(i); val label = if(src.optString("source").contains("AsiaCloud",true)) "RiveStream ${src.optString("source")}[${src.optString("quality")}]" else "RiveStream ${src.optString("source")}"; val url = src.optString("url"); if (url.contains("proxy?url=")) { val dec = URLDecoder.decode(url, "UTF-8"); val eUrl = dec.substringAfter("proxy?url=").substringBefore("&headers="); val dUrl = URLDecoder.decode(eUrl, "UTF-8"); val hMap = try { val j = URLDecoder.decode(dec.substringAfter("&headers="), "UTF-8"); JSONObject(j).let { json -> json.keys().asSequence().associateWith { json.getString(it) } } } catch (e: Exception) { emptyMap() }; callback.invoke(newExtractorLink(label, label, dUrl, if (dUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = Qualities.P1080.value; this.referer = hMap["Referer"] ?: ""; this.headers = mapOf("Referer" to (hMap["Referer"] ?: ""), "Origin" to (hMap["Origin"] ?: "")) }) } else callback.invoke(newExtractorLink("$label (VLC)", "$label (VLC)", url, if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE) { this.referer = ""; this.quality = Qualities.P1080.value }) } } catch (_: Exception) {} }
    }

    suspend fun invokeAdimoviebox2(title: String, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val api = "https://api3.aoneroom.com"; val (brand, model) = Adimoviebox2Helper.randomBrandModel()
        val search = app.post("$api/wefeed-mobile-bff/subject-api/search/v2", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/search/v2", """{"page": 1, "perPage": 10, "keyword": "$title"}""", "POST", brand, model), requestBody = """{"page": 1, "perPage": 10, "keyword": "$title"}""".toRequestBody("application/json".toMediaTypeOrNull())).parsedSafe<Adimoviebox2SearchResponse>()
        val mainId = search?.data?.results?.flatMap { it.subjects ?: arrayListOf() }?.find { val y = it.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull(); it.title?.contains(title, true) == true && (year == null || y == year) && (if (season != null) it.subjectType == 2 else it.subjectType == 1 || it.subjectType == 3) }?.subjectId ?: return
        val list = mutableListOf<Pair<String, String>>(); try { val data = JSONObject(app.get("$api/wefeed-mobile-bff/subject-api/get?subjectId=$mainId", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/get?subjectId=$mainId", null, "GET", brand, model)).text).optJSONObject("data"); list.add(mainId to "Original Audio"); val dubs = data?.optJSONArray("dubs"); if (dubs != null) for (i in 0 until dubs.length()) { val dId = dubs.optJSONObject(i)?.optString("subjectId"); if (!dId.isNullOrEmpty() && dId != mainId) list.add(dId to (dubs.optJSONObject(i)?.optString("lanName") ?: "Dub")) } } catch (e: Exception) { list.add(mainId to "Original Audio") }
        list.forEach { (cId, lName) -> val play = app.get("$api/wefeed-mobile-bff/subject-api/play-info?subjectId=$cId&se=${season ?: 0}&ep=${episode ?: 0}", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/play-info?subjectId=$cId&se=${season ?: 0}&ep=${episode ?: 0}", null, "GET", brand, model)).parsedSafe<Adimoviebox2PlayResponse>(); play?.data?.streams?.forEach { s -> callback.invoke(newExtractorLink("Adimoviebox2 ($lName)", "Adimoviebox2 ($lName)", s.url ?: return@forEach, if (s.url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = getQualityFromName(s.resolutions); this.headers = Adimoviebox2Helper.getHeaders(s.url, null, "GET", brand, model).toMutableMap().apply { if (!s.signCookie.isNullOrEmpty()) put("Cookie", s.signCookie) } }); if (s.id != null) { app.get("$api/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$cId&streamId=${s.id}", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$cId&streamId=${s.id}", null, "GET", brand, model)).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap -> subtitleCallback.invoke(newSubtitleFile("${cap.language ?: cap.lanName ?: cap.lan ?: "Unknown"} ($lName)", cap.url ?: return@forEach)) }; app.get("$api/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$cId&resourceId=${s.id}&episode=0", headers = Adimoviebox2Helper.getHeaders("$api/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$cId&resourceId=${s.id}&episode=0", null, "GET", brand, model)).parsedSafe<Adimoviebox2SubtitleResponse>()?.data?.extCaptions?.forEach { cap -> subtitleCallback.invoke(newSubtitleFile("${cap.lan ?: cap.lanName ?: cap.language ?: "Unknown"} ($lName) [Ext]", cap.url ?: return@forEach)) } } } }
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
