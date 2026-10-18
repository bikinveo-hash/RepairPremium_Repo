package com.Moviebox

import com.lagradost.cloudstream3.PlaybackApiFixture
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.sun.net.httpserver.HttpServer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/** Actual provider + actual CloudStream/NiceHttp/OkHttp APIs; synthetic API and local media server. */
fun main() = runBlocking {
    val video = byteArrayOf(0,0,0,24) + "ftypisom".toByteArray() + ByteArray(8192)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val mediaRequests = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Map<String,List<String>>>>()
    server.createContext("/") { exchange ->
        mediaRequests += exchange.requestURI.path to exchange.requestHeaders.toMap()
        val path = exchange.requestURI.path
        if (exchange.requestURI.rawQuery.orEmpty().contains("Signature=expired")) {
            exchange.sendResponseHeaders(403,-1);exchange.close();return@createContext
        }
        if (path.startsWith("/signed/") &&
                (exchange.requestHeaders.getFirst("Cookie") != "fixture-signed" ||
                 exchange.requestHeaders.getFirst("X-MB-Token") != "fixture-signed")) {
            exchange.sendResponseHeaders(403,-1);exchange.close();return@createContext
        }
        if (path == "/signed/redirect.mp4") {
            exchange.responseHeaders.add("Location","http://localhost:${server.address.port}/other/target.mp4")
            exchange.sendResponseHeaders(302,-1);exchange.close();return@createContext
        }
        val bytes = when {
            path.endsWith(".mpd") -> "<MPD><Period><AdaptationSet mimeType=\"video/mp4\"><Representation id=\"v\"><SegmentTemplate media=\"segment.mp4\"/></Representation></AdaptationSet></Period></MPD>".toByteArray()
            path.endsWith(".m3u8") -> "#EXTM3U\n#EXTINF:5,\nsegment.mp4\n".toByteArray()
            path.contains("notice") -> "<html>Upgrade</html>".toByteArray()
            else -> video
        }
        exchange.responseHeaders.add("Content-Type",if(path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp4")
        exchange.sendResponseHeaders(200,bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    val root = "http://127.0.0.1:${server.address.port}"
    var count = 0
    fun response(request: Request, code: Int = 200, body: String = "{}", token: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).apply {
                token?.let { header("x-user","{\"token\":\"$it\"}") }
            }.build()
    fun json(path: String, id: String = "s1") = """{"code":0,"data":{"streams":[{"url":"$root/$path","id":"$id","format":"MP4"}]}}"""
    suspend fun test(name: String, block: suspend () -> Unit) {
        PlaybackApiFixture.requests.clear();mediaRequests.clear();block()
        check(PlaybackApiFixture.requests.none { it.url.encodedPath.contains("play-info") })
        count++;println("PASS $name")
    }
    suspend fun load(se: Int = 0, ep: Int = 0, subject: String = "123"): Triple<Boolean,List<ExtractorLink>,List<SubtitleFile>> {
        val provider = MovieBoxProvider();val links=mutableListOf<ExtractorLink>();val subs=mutableListOf<SubtitleFile>()
        val data="""{"subjectId":"$subject","se":$se,"ep":$ep,"subjectType":${if(se==0) 1 else 2}}"""
        val ok=provider.loadLinks(data,false,subs::add,links::add)
        return Triple(ok,links,subs)
    }
    try {
        test("v36 DEX golden signature uses default key and decoded sorted query") {
            val method=MovieBoxProvider::class.java.getDeclaredMethod("modernPlaybackSignature",String::class.java,String::class.java)
            method.isAccessible=true
            val result=method.invoke(MovieBoxProvider(),"https://api3.aoneroom.com/wefeed-mobile-bff/subject-api/play-info?ep=4&subjectId=123&supportCodecs%5Bh264%5D=1&se=2","1700000000000")
            check(result=="1700000000000|2|iOVSaKxdFoaKM4B4ccLO9Q==")
        }
        test("CloudFront and Edge policy resources retain their media resource") {
            val method=MovieBoxProvider::class.java.getDeclaredMethod("extractPlaybackPolicyResource",String::class.java)
            method.isAccessible=true
            val encoded=java.util.Base64.getEncoder().encodeToString("{\"Statement\":[{\"Resource\":\"https://cdn.example.test/movie/index.mpd?Signature=a%2Bb\"}]}".toByteArray()).replace('+','-').replace('/','~').replace('=','_')
            check(method.invoke(MovieBoxProvider(),"CloudFront-Policy=$encoded; CloudFront-Signature=fixture")=="https://cdn.example.test/movie/index.mpd?Signature=a%2Bb")
            val edge=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://cdn.example.test/movie/".toByteArray())
            check(method.invoke(MovieBoxProvider(),"Edge-Cache-Cookie=urlprefix=$edge:expires=9999999999")=="https://cdn.example.test/movie/index.mpd")
        }
        test("only H5 is requested even when both mobile play-info fixtures would succeed") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture")
                r.url.encodedPath.contains("play-info") -> error("Removed mobile playback route called")
                r.url.encodedPath.endsWith("subject/play") -> response(r,body=json("web.mp4"))
                else -> response(r)
            } }
            val (ok,links,_)=load();check(ok && links.size==1)
            check(links.single().name=="MovieBox • Asli • Video")
        }
        test("no bearer or metadata available: guest H5 still works") {
            PlaybackApiFixture.responder = { r -> if(r.url.encodedPath.endsWith("subject/play"))
                response(r,body=json("guest.mp4")) else response(r,500) }
            val (ok,links,_)=load();check(ok && links.size==1)
        }
        test("independent ext captions survive primary failure without a native media request") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture-anonymous")
                r.url.encodedPath.endsWith("subject/play") -> response(r,body=json("h5.mp4"))
                r.url.encodedPath.endsWith("get-stream-captions") -> response(r,500)
                r.url.encodedPath.endsWith("get-ext-captions") -> response(r,body="""{"data":{"extCaptions":[{"url":"https://subs.example.test/a.srt","lanName":"Indonesia"}]}}""")
                else -> response(r)
            } }
            val (ok,links,subs)=load();check(ok && links.size==1)
            check(subs.single().lang=="Indonesia")
            check(PlaybackApiFixture.requests.any { it.url.encodedPath.endsWith("get-ext-captions") && it.url.queryParameter("resourceId")=="s1" })
        }
        test("serial 2/4 and 1/1 keep exact episode across H5 aliases") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture")
                r.url.encodedPath.endsWith("subject/play") && r.url.host=="h5-api.aoneroom.com" -> response(r,body="""{"data":{"se":9,"ep":9,"streams":[{"url":"$root/wrong.mp4"}]}}""")
                r.url.encodedPath.endsWith("subject/play") -> response(r,body=json("right.mp4"))
                else -> response(r)
            } }
            for ((se,ep) in listOf(2 to 4,1 to 1)) {
                PlaybackApiFixture.requests.clear()
                val (ok,links,_)=load(se,ep);check(ok && links.single().url.endsWith("right.mp4"))
                PlaybackApiFixture.requests.filter { it.url.queryParameter("se")!=null }.forEach {
                    check(it.url.queryParameter("se")==se.toString() && it.url.queryParameter("ep")==ep.toString())
                }
            }
        }
        test("invalid notice cannot suppress a valid later H5 alias") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("subject/play") && r.url.host=="h5-api.aoneroom.com" -> response(r,body=json("notice.html"))
                r.url.encodedPath.endsWith("subject/play") -> response(r,body=json("fallback.mp4"))
                else -> response(r)
            } }
            val (ok,links,_)=load();check(ok && links.size==1 && links.single().url.endsWith("fallback.mp4"))
            check(PlaybackApiFixture.requests.none { it.header("x-vip-restrict")!=null || it.header("x-no-high-risk-restrict")!=null })
            check(PlaybackApiFixture.requests.filter { it.url.host!="h5-api.aoneroom.com" && it.url.encodedPath.endsWith("subject/play") }.all { it.header("Authorization")==null })
        }
        test("H5 manifest and segment retain signed Cookie and token header") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("subject/play") -> response(r,body="""{"data":{"hls":[{"url":"$root/signed/index.m3u8","id":"signed","signCookie":"fixture-signed","signHeaderKey":"X-MB-Token"}]}}""")
                else -> response(r,500)
            } }
            val (ok,links,_)=load();check(ok && links.single().headers["X-MB-Token"]=="fixture-signed")
            check(mediaRequests.any { it.first == "/signed/segment.mp4" })
            val link=links.single()
            val client=OkHttpClient.Builder().addInterceptor(MovieBoxProvider().getVideoInterceptor(link)).build()
            client.newCall(Request.Builder().url("$root/signed/segment.mp4").apply {
                link.headers.forEach { (k,v) -> header(k,v) }
            }.build()).execute().use { check(it.code==200) }
            client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
        }
        test("actual media redirect strips cookie and custom token at a different host/path") {
            val stream=MovieboxPlayback.Stream("$root/signed/redirect.mp4","MP4","",null,"","fixture-signed","X-MB-Token","","H5")
            val headers=MovieboxPlayback.mediaHeaders(stream,"https://web.example.test","https://web.example.test/",MovieboxPlayback.WEB_UA)
            check(MovieboxPlayback.sample(stream.url,headers,stream.url)?.code==200)
            val target=mediaRequests.last { it.first=="/other/target.mp4" }.second
            check(target.keys.none { it.equals("Cookie",true) || it.equals("X-MB-Token",true) })
        }
        test("Range ignored by media server still reads at most 4096 bytes; never HEAD") {
            val u="$root/big.mp4";val r=MovieboxPlayback.sample(u,emptyMap(),u)!!
            check(r.bytes.size==4096 && r.truncated)
            check(mediaRequests.single().second.keys.any { it.equals("Range",true) })
        }
        test("H5 routing alias receives no mobile bearer and keeps selected episode") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture")
                r.url.encodedPath.endsWith("subject/play") && r.url.host=="123moviesfree.club" -> response(r,body=json("alias.mp4"))
                else -> response(r,503)
            } }
            val (ok,links,_)=load(2,4);check(ok && links.single().name.startsWith("MovieBox • Asli"))
            val alias=PlaybackApiFixture.requests.single { it.url.host=="123moviesfree.club" }
            check(alias.header("Authorization")==null && alias.url.queryParameter("se")=="2" && alias.url.queryParameter("ep")=="4")
        }
        test("all endpoints fail: false and zero callbacks") {
            PlaybackApiFixture.responder = { response(it,503) }
            val (ok,links,_)=load();check(!ok && links.isEmpty())
        }
        test("expired signed URL does not hide its working rotation; duplicate labels stay clean") {
            PlaybackApiFixture.responder = { r -> if(r.url.encodedPath.endsWith("subject/play"))
                response(r,body="""{"data":{"streams":[{"url":"$root/rotate.mp4?Signature=expired","id":"x","codecName":"h264","resolutions":"1080"},{"url":"$root/rotate.mp4?Signature=valid","id":"y","codecName":"h264","resolutions":"1080"},{"url":"$root/rotate.mp4?Signature=other","id":"z","codecName":"h264","resolutions":"1080"}]}}""") else response(r) }
            val (ok,links,_)=load();check(ok && links.size==1)
            check(links.single().name=="MovieBox • Asli • Video • H.264" && links.single().quality==1080)
            check(!links.single().url.contains("expired"))
        }
        test("dub on third alias remains discoverable after original H5 already succeeded") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("subject-api/get") && r.url.queryParameter("subjectId")=="123" -> response(r,body="""{"data":{"subjectId":"123","detailPath":"correct-path","detailUrl":"https://movie-box.co/movies/old-path","dubs":[{"subjectId":"456","lanName":"Indonesian dub","detailPath":"dub-path"}]}}""")
                r.url.encodedPath.endsWith("subject/play") && r.url.queryParameter("subjectId")=="123" -> response(r,body=json("original.mp4"))
                r.url.encodedPath.endsWith("subject/play") && r.url.host=="movie-box.co" -> response(r,body=json("indonesia.mp4"))
                else -> response(r,404)
            } }
            val (ok,links,_)=load(2,4);check(ok && links.size==2)
            check(links[0].name.startsWith("MovieBox • Asli") && links[1].name.startsWith("MovieBox • Indonesia"))
            check(PlaybackApiFixture.requests.any { it.url.host=="movie-box.co" && it.url.queryParameter("subjectId")=="456" })
            check(PlaybackApiFixture.requests.filter { it.url.queryParameter("subjectId")=="123" && it.url.encodedPath.endsWith("subject/play") }
                .all { it.url.queryParameter("detailPath")=="correct-path" })
        }
        test("catalog metadata profile recovers H5 detailPath after modern metadata fails") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("ranking-list") -> response(r,token="fixture")
                r.url.encodedPath.endsWith("subject-api/get") && r.header("User-Agent").orEmpty().contains("oneroom/") -> response(r,body="""{"data":{"subjectId":"123","detailPath":"catalog-path"}}""")
                r.url.encodedPath.endsWith("subject/play") && r.url.queryParameter("detailPath")=="catalog-path" -> response(r,body=json("catalog-path.mp4"))
                else -> response(r,403)
            } }
            val (ok,links,_)=load();check(ok && links.single().url.endsWith("catalog-path.mp4"))
            check(PlaybackApiFixture.requests.count { it.url.encodedPath.endsWith("subject-api/get") }==2)
        }
        test("nested exact-subject H5 detail recovers path when mobile metadata is unavailable") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("/detail") -> response(r,body="""{"data":{"subject":{"subjectId":"123","detailPath":"web-path"}}}""")
                r.url.encodedPath.endsWith("subject/play") && r.url.queryParameter("detailPath")=="web-path" -> response(r,body=json("web-path.mp4"))
                else -> response(r,403)
            } }
            val (ok,links,_)=load();check(ok && links.single().url.endsWith("web-path.mp4"))
        }
        test("Reacher observed empty H5 schema tries every alias without playing an upgrade notice") {
            val empty="""{"code":0,"message":"ok","data":{"streams":[],"dash":[],"hls":[],"hasResource":false,"vipLocked":false}}"""
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("subject/play") -> response(r,body=empty)
                r.url.encodedPath.contains("play-info") -> response(r,body=json("upgrade-notice.mp4"))
                else -> response(r,404)
            } }
            val (ok,links,_)=load(1,1,"3620534210064175096");check(!ok && links.isEmpty())
            check(PlaybackApiFixture.requests.count { it.url.encodedPath.endsWith("subject/play") }==MovieboxPlayback.webDomains.size)
            check(mediaRequests.isEmpty())
        }
        test("audio filtering skips unsupported dub requests and retains original Indonesian English qualities") {
            val metadata="""{"data":{"subjectId":"123","dubs":[
                {"subjectId":"123","lanName":"Korean"},
                {"subjectId":"456","lan":"in_id","lanName":"Indonesia"},
                {"subjectId":"789","lan":"en","lanName":"English"},
                {"subjectId":"900","lanName":"Hindi"},
                {"subjectId":"901","lanName":"Japanese"},
                {"subjectId":"902"},
                {"subjectId":"903","lan":"hi","lanName":"English"}]}}"""
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("subject-api/get") && r.url.queryParameter("subjectId")=="123" -> response(r,body=metadata)
                r.url.encodedPath.endsWith("subject/play") -> when (r.url.queryParameter("subjectId")) {
                    "123" -> response(r,body="""{"data":{"streams":[{"id":"orig","url":"$root/ko.mp4","lanName":"Korean"}]}}""")
                    "456" -> response(r,body="""{"data":{"dash":[{"url":"$root/indo.mpd","lan":"in_id","resolutions":"1080","codecName":"hevc"}],"hls":[{"url":"$root/indo.m3u8","lanName":"Indonesian","resolutions":"720"}],"streams":[{"url":"$root/indo.mp4","resolutions":"1080","codecName":"h264"},{"url":"$root/indo.mp4","resolutions":"480","codecName":"h264"}]}}""")
                    "789" -> response(r,body=json("en.mp4"))
                    else -> error("Unsupported dub requested")
                }
                r.url.encodedPath.endsWith("get-stream-captions") -> response(r,body="""{"data":{"extCaptions":[{"lanName":"Hindi","url":"https://subs.example.test/hi.srt"},{"lanName":"Japanese","url":"https://subs.example.test/ja.srt"}]}}""")
                else -> response(r,404)
            } }
            val (ok,links,subs)=load(1,1);check(ok && links.size==6)
            check(links.count { it.name.startsWith("MovieBox • Asli") }==1)
            check(links.count { it.name.startsWith("MovieBox • Indonesia") }==4)
            check(links.count { it.name.startsWith("MovieBox • Inggris") }==1)
            check(links.filter { it.url.endsWith("indo.mp4") }.map { it.quality }.toSet()==setOf(1080,480))
            check(links.all { !it.name.contains("1080") && !it.name.contains("dub",true) })
            check(PlaybackApiFixture.requests.filter { it.url.encodedPath.endsWith("subject/play") }
                .map { it.url.queryParameter("subjectId") }.toSet()==setOf("123","456","789"))
            check(PlaybackApiFixture.requests.none { it.url.queryParameter("subjectId") in setOf("900","901","902","903") })
            check(subs.map { it.url }.toSet()==setOf("https://subs.example.test/hi.srt","https://subs.example.test/ja.srt"))
            // Preserve the existing subtitle audio-context suffix; audio filtering must not rename it.
            check(subs.map { it.lang }.toSet()==setOf("Hindi (Korea)","Japanese (Korea)"))
        }
        test("actual H5 language is filtered before media fetch and CloudStream callback") {
            PlaybackApiFixture.responder = { r -> when {
                r.url.encodedPath.endsWith("subject-api/get") -> response(r,body="""{"data":{"subjectId":"123","dubs":[{"subjectId":"456","lanName":"Indonesia"}]}}""")
                r.url.encodedPath.endsWith("subject/play") && r.url.queryParameter("subjectId")=="123" -> response(r,body=json("original.mp4"))
                r.url.encodedPath.endsWith("subject/play") -> response(r,body="""{"data":{"streams":[{"url":"$root/hindi-blocked.mp4","lanName":"Hindi"},{"url":"$root/conflict-blocked.mp4","lan":"hi","lanName":"English"},{"url":"$root/actual-en.mp4","lan":"eng"},{"url":"$root/actual-id.mp4"}]}}""")
                else -> response(r,404)
            } }
            val (ok,links,_)=load();check(ok && links.size==3)
            check(links.any { it.name.startsWith("MovieBox • Inggris") && it.url.endsWith("actual-en.mp4") })
            check(links.any { it.name.startsWith("MovieBox • Indonesia") && it.url.endsWith("actual-id.mp4") })
            check(links.none { it.url.contains("blocked") } && mediaRequests.none { it.first.contains("blocked") })
        }
        test("Reacher exact identity path and episode reject errors mismatches and notice without substitutions") {
            val id="3620534210064175096"
            for (scenario in listOf("403","404","api-error","mismatch","notice")) {
                PlaybackApiFixture.requests.clear()
                PlaybackApiFixture.responder = { r -> when {
                    r.url.encodedPath.endsWith("subject-api/get") -> response(r,body="""{"data":{"subjectId":"$id","detailPath":"fixture-exact-path","dubs":[{"subjectId":"999","lanName":"Hindi"}]}}""")
                    r.url.encodedPath.endsWith("subject/play") -> when (scenario) {
                        "403", "404" -> response(r,scenario.toInt())
                        "api-error" -> response(r,body="""{"code":500,"data":{"streams":[{"url":"$root/wrong.mp4"}]}}""")
                        "mismatch" -> response(r,body="""{"data":{"subjectId":"$id","se":1,"ep":2,"streams":[{"url":"$root/wrong.mp4"}]}}""")
                        else -> response(r,body=json("upgrade-notice.mp4"))
                    }
                    else -> response(r,404)
                } }
                val (ok,links,_)=load(1,1,id);check(!ok && links.isEmpty())
                val playback=PlaybackApiFixture.requests.filter { it.url.encodedPath.endsWith("subject/play") }
                check(playback.size==MovieboxPlayback.webDomains.size)
                check(playback.all { it.url.queryParameter("subjectId")==id && it.url.queryParameter("se")=="1" &&
                    it.url.queryParameter("ep")=="1" && it.url.queryParameter("detailPath")=="fixture-exact-path" })
                check(mediaRequests.isEmpty())
            }
        }
        test("Reacher blank detailUrl is repaired for original and server-declared Indonesian exact episodes") {
            val original="3620534210064175096";val ind="4149489342181954296"
            val paths=mapOf(original to "reacher-e1nw56h5sj4",ind to "reacher-indonesian-WjrjjPIHwW4")
            PlaybackApiFixture.responder = { r ->
                val sid=r.url.queryParameter("subjectId").orEmpty()
                when {
                    r.url.encodedPath.endsWith("subject-api/get") -> response(r,body="""{"code":0,"data":{"subjectId":"$sid","detailUrl":"","dubs":[{"subjectId":"$original","lanName":"Original Audio","original":true},{"subjectId":"$ind","lanName":"Indonesia"}]}}""")
                    r.url.encodedPath.endsWith("subject/play") -> {
                        val path=paths[sid]
                        if(path!=null && r.url.queryParameter("detailPath")==path &&
                            r.header("Referer").orEmpty().contains("/movies/$path?"))
                            response(r,body=json("reacher-$sid-${r.url.queryParameter("ep")}.mp4"))
                        else response(r,body="""{"code":0,"data":{"streams":[],"hasResource":false}}""")
                    }
                    else -> response(r,404)
                }
            }
            for(ep in listOf(1,2)) {
                PlaybackApiFixture.requests.clear()
                val (ok,links,_)=load(1,ep,original)
                check(ok && links.size==2)
                check(links.any { it.name.startsWith("MovieBox • Asli") })
                check(links.any { it.name.startsWith("MovieBox • Indonesia") })
                check(links.all { it.url.endsWith("-$ep.mp4") })
                val requests=PlaybackApiFixture.requests.filter { it.url.encodedPath.endsWith("subject/play") }
                check(requests.size==2 && requests.map { it.url.queryParameter("subjectId") }.toSet()==paths.keys)
                check(requests.all { it.url.queryParameter("se")=="1" && it.url.queryParameter("ep")==ep.toString() })
            }
        }
        test("Reacher selected identity retains Share context when all metadata endpoints fail") {
            val ind="4149489342181954296";val path="reacher-indonesian-WjrjjPIHwW4"
            PlaybackApiFixture.responder = { r ->
                if(r.url.encodedPath.endsWith("subject/play") && r.url.queryParameter("subjectId")==ind &&
                    r.url.queryParameter("detailPath")==path) response(r,body=json("reacher-ind.mp4"))
                else response(r,403)
            }
            val (ok,links,_)=load(1,1,ind);check(ok && links.size==1)
            check(PlaybackApiFixture.requests.filter { it.url.encodedPath.endsWith("subject/play") }.all {
                it.url.queryParameter("subjectId")==ind && it.url.queryParameter("se")=="1" && it.url.queryParameter("ep")=="1"
            })
        }
        test("House of the Dragon each selected Share identity retains exact S1E1 and S1E2 context") {
            val paths=mapOf("6890674365592471456" to "house-of-the-dragon-indonesian-2vLI2nqm1d8",
                "5373384118887662624" to "house-of-the-dragon-uTatZHG9Wo6",
                "7721864815710718808" to "house-of-the-dragon-G7LGtmOdqc9",
                "3135126230249492760" to "house-of-the-dragon-kkZZ8pvUAJ3")
            for ((id,path) in paths) for (ep in listOf(1,2)) {
                PlaybackApiFixture.requests.clear()
                PlaybackApiFixture.responder = { r -> when {
                    r.url.encodedPath.endsWith("subject-api/get") -> response(r,body="""{"data":{"subjectId":"$id","detailUrl":""}}""")
                    r.url.encodedPath.endsWith("subject/play") && r.url.queryParameter("subjectId")==id &&
                        r.url.queryParameter("detailPath")==path &&
                        r.header("Referer").orEmpty().contains("/movies/$path?id=$id&") ->
                        response(r,body=json("dragon-$id-$ep.mp4"))
                    r.url.encodedPath.endsWith("subject/play") -> response(r,body="""{"code":0,"data":{"streams":[],"hasResource":false}}""")
                    else -> response(r,404)
                } }
                val (ok,links,_)=load(1,ep,id)
                check(ok && links.single().url.endsWith("dragon-$id-$ep.mp4"))
                val requests=PlaybackApiFixture.requests.filter { it.url.encodedPath.endsWith("subject/play") }
                check(requests.size==1 && requests.all { it.url.queryParameter("subjectId")==id &&
                    it.url.queryParameter("se")=="1" && it.url.queryParameter("ep")==ep.toString() })
            }
        }
        test("House of the Dragon Share context survives metadata failure without linking editions") {
            val id="5373384118887662624";val path="house-of-the-dragon-uTatZHG9Wo6"
            PlaybackApiFixture.responder = { r -> if(r.url.encodedPath.endsWith("subject/play") &&
                r.url.queryParameter("subjectId")==id && r.header("Referer").orEmpty().contains("/movies/$path?"))
                response(r,body=json("dragon-guest.mp4")) else response(r,403) }
            val (ok,links,_)=load(1,1,id);check(ok && links.single().url.endsWith("dragon-guest.mp4"))
            check(PlaybackApiFixture.requests.filter { it.url.encodedPath.endsWith("subject/play") }.size==1)
        }
        test("unrelated subjects keep existing blank-path H5 behavior") {
            PlaybackApiFixture.responder = { r ->
                if(r.url.encodedPath.endsWith("subject/play")) {
                    check(r.url.queryParameter("detailPath")=="")
                    response(r,body=json("unrelated.mp4"))
                } else response(r,404)
            }
            val (ok,links,_)=load(1,1,"123");check(ok && links.single().url.endsWith("unrelated.mp4"))
        }
        println("INTEGRATION $count PASS; synthetic API/local HTTP, MovieBox device playback NOT_TESTED")
    } finally {
        server.stop(0)
        com.lagradost.cloudstream3.app.baseClient.connectionPool.evictAll()
        MovieboxPlayback.client.connectionPool.evictAll()
        com.lagradost.cloudstream3.app.baseClient.dispatcher.executorService.shutdown()
        MovieboxPlayback.client.dispatcher.executorService.shutdown()
    }
}
