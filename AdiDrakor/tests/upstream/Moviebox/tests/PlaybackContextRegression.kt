package com.Moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.io.File

fun main() = runBlocking {
    var count=0
    suspend fun test(name: String, block: suspend () -> Unit) { block();count++;println("PASS $name") }
    val fixtures=File(System.getProperty("moviebox.fixtures"))
    val observed=JSONObject(File(fixtures,"spiderman-share.json").readText())
    val id=observed.getString("subjectId")
    val title=observed.getString("title")
    val slug=observed.getString("detailPath")
    val html=File(fixtures,"spiderman-context.html").readText()
    fun meta(subject: String=id, name: String=title)=JSONObject().put("subjectId",subject).put("title",name)
    fun catalog(vararg pairs: Pair<String,String>): String {
        val table=JSONArray()
        for((subject,path) in pairs) {
            val start=table.length()
            table.put(JSONObject().put("subjectId",start+1).put("detailPath",start+2))
                .put(subject).put(path)
        }
        return "<script id=__NUXT_DATA__ type=application/json>$table</script>"
    }
    test("owner Share and captured public catalog agree on exact Spider-Man subject and case-sensitive path") {
        check(MovieboxPlaybackContext().catalogPath(html,id)==slug)
        check(MovieboxPlaybackContext().catalogPath(html,"1321879841695749848")==null)
    }
    test("same title on another subject cannot provide the selected H5 path") {
        val reader=MovieboxPlaybackContext()
        check(reader.catalogPath(catalog("999" to slug),id)==null)
        check(reader.catalogPath(catalog("999" to "wrong-film",id to slug),id)==slug)
    }
    test("ambiguous paths for one subject fail closed instead of choosing the first row") {
        check(MovieboxPlaybackContext().catalogPath(catalog(id to slug,id to "different-context"),id)==null)
        check(MovieboxPlaybackContext().catalogPath(catalog(id to slug,id to slug),id)==slug)
    }
    test("malformed and oversized inert catalog documents cannot execute code or create paths") {
        val reader=MovieboxPlaybackContext()
        check(reader.catalogPath("<script>throw Error()</script>",id)==null)
        check(reader.catalogPath("<script id=__NUXT_DATA__ type=application/json>[{\"subjectId\":0,\"detailPath\":0}]</script>",id)==null)
        check(reader.catalogPath(" ".repeat(2_000_001),id)==null)
        check(reader.catalogPath("<script id=__NUXT_DATA__ type=application/json>[{\"subjectId\":4294967297,\"detailPath\":2},\"$id\",\"$slug\"]</script>",id)==null)
        check(reader.catalogPath("<script id=__NUXT_DATA__ type=application/json>[{\"subjectId\":1.5,\"detailPath\":2},\"$id\",\"$slug\"]</script>",id)==null)
    }
    test("official path extraction rejects credential URLs foreign hosts conflicting IDs and protocol-relative hosts") {
        val reader=MovieboxPlaybackContext()
        check(reader.path("https://movie-box.co/detail/$slug?id=$id",id)==slug)
        for(value in listOf("https://evil.example/$slug?id=$id","https://user@moviebox.ph/$slug", "//evil.example/$slug",
            "https://moviebox.ph/detail/$slug?id=999","https://moviebox.ph/detail/$slug?id=$id&id=999", "https://moviebox.ph/download-app", "javascript:alert(1)"))
            check(reader.path(value,id)==null) { value }
    }
    test("native generic detail routes and contradictory URL IDs are never treated as H5 slugs") {
        val reader=MovieboxPlaybackContext()
        check(reader.knownPath(meta().put("detailUrl","https://api3.aoneroom.com/detail?id=$id"),id)==null)
        check(reader.knownPath(meta().put("detailUrl","https://moviebox.ph/movies/$slug?id=999"),id)==null)
        check(reader.knownPath(meta().put("detailUrl","https://moviebox.ph/movies/$slug?id=$id"),id)==slug)
        check(reader.knownPath(meta("999").put("detailPath",slug),id)==null)
    }
    test("selected same-ID path survives metadata denial without network discovery") {
        check(MovieboxPlaybackContext().resolve(id,id,title,slug,JSONObject()) {error("unexpected lookup")}==slug)
    }
    test("selected path and title are never borrowed by a different dubbed subject") {
        var reads=0
        check(MovieboxPlaybackContext().resolve("999",id,title,slug,JSONObject()) { reads++;html }.isEmpty())
        check(reads==0)
    }
    test("a contradictory metadata subject blocks selected payload context") {
        check(MovieboxPlaybackContext().resolve(id,id,title,slug,meta("999")) {error("unexpected lookup")}.isEmpty())
    }
    test("old payload can recover path through same-ID native title and public catalog") {
        var reads=0
        check(MovieboxPlaybackContext().resolve(id,id,null,null,meta()) { url ->
            reads++;check(url.startsWith("https://moviebox.ph/web/searchResult?keyword="));html
        }==slug && reads==1)
    }
    test("saved selected title can recover same-ID path when all metadata APIs fail") {
        check(MovieboxPlaybackContext().resolve(id,id,title,null,JSONObject()) {html}==slug)
    }
    test("missing title never fabricates a slug or tries a different film") {
        check(MovieboxPlaybackContext().resolve(id,id,null,null,JSONObject()) {error("no query should exist")}.isEmpty())
    }
    test("public error no matching ID and lookup exception remain empty") {
        for(body in listOf<String?>(null,"<html>sign in</html>",catalog("999" to slug)))
            check(MovieboxPlaybackContext().resolve(id,id,title,null,JSONObject()) {body}.isEmpty())
        check(MovieboxPlaybackContext().resolve(id,id,title,null,JSONObject()) {throw java.io.IOException("fixture")}.isEmpty())
    }
    test("different native and saved display languages are tried under one exact subject ID") {
        val queries=mutableListOf<String>()
        val got=MovieboxPlaybackContext().resolve(id,id,title,null,meta(name="Spider-Man: Hari Baru")) { url ->
            val query=java.net.URLDecoder.decode(url.substringAfter("keyword="),"UTF-8");queries+=query
            if(query==title) html else catalog("999" to "unrelated-film")
        }
        check(got==slug && queries==listOf("Spider-Man: Hari Baru",title))
    }
    test("localized query never borrows a matching-title path from another subject") {
        var reads=0
        check(MovieboxPlaybackContext().resolve(id,id,title,null,meta(name="Spider-Man: Hari Baru")) {
            reads++;catalog("999" to slug)
        }.isEmpty() && reads==2)
    }
    test("one failed localized lookup does not suppress saved canonical title recovery") {
        var reads=0
        check(MovieboxPlaybackContext().resolve(id,id,title,null,meta(name="Spider-Man: Hari Baru")) {
            reads++;if(reads==1) throw java.io.IOException("fixture") else html
        }==slug && reads==2)
    }
    test("public bilingual discovery retains canonical IDs and rejects malformed types references and scripts") {
        val rows=MovieboxPlaybackContext().catalogSubjects(html)
        check(rows.single().getString("subjectId")==id && rows.single().getString("title")==title)
        check(MovieboxPlaybackContext().catalogSubjects("<script>evil()</script>").isEmpty())
        check(MovieboxPlaybackContext().catalogSubjects("<script id=__NUXT_DATA__ type=application/json>[{\"subjectId\":4294967297,\"title\":2,\"subjectType\":3},\"123\",\"Title\",1]</script>").isEmpty())
    }
    test("context cache is ID-scoped short-lived and cannot override an explicit contradiction") {
        var now=0L;var reads=0
        val reader=MovieboxPlaybackContext(clock={now})
        suspend fun lookup(subject: String)=reader.resolve(subject,subject,title,null,meta(subject)) {
            reads++;catalog(id to slug,"999" to "another-authoritative-path")
        }
        check(lookup(id)==slug && lookup(id)==slug && reads==1)
        check(lookup("999")=="another-authoritative-path" && reads==2)
        check(reader.resolve(id,id,title,null,meta("999")) {error("contradiction")}.isEmpty())
        now=60_001;check(lookup(id)==slug && reads==3)
    }
    test("temporary public failure is not negatively cached") {
        var reads=0;val reader=MovieboxPlaybackContext()
        check(reader.resolve(id,id,title,null,meta()) {reads++;null}.isEmpty())
        check(reader.resolve(id,id,title,null,meta()) {reads++;html}==slug && reads==2)
    }
    test("lookup timeout returns empty and parent cancellation propagates") {
        check(MovieboxPlaybackContext(budgetMs=20).resolve(id,id,title,null,meta()) {delay(1000);html}.isEmpty())
        var cancelled=false
        try { MovieboxPlaybackContext().resolve(id,id,title,null,meta()) {throw CancellationException("fixture")} }
        catch (_:CancellationException) {cancelled=true}
        check(cancelled)
        check(withTimeoutOrNull(20) { MovieboxPlaybackContext().resolve(id,id,title,null,meta()) {delay(1000);html} }==null)
    }

    val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    server.createContext("/") { exchange ->
        val bytes=byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()+ByteArray(8192)
        exchange.responseHeaders.add("Content-Type","video/mp4")
        exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
    }
    server.start()
    val media="http://127.0.0.1:${server.address.port}/sample.mp4"
    fun response(req: Request,body: String="{}",code: Int=200,token: Boolean=false)=Response.Builder().request(req)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType()))
        .apply {if(token)header("x-user","{\"token\":\"fixture-guest\"}")}.build()
    suspend fun playback(provider: MovieBoxProvider,data: String): Pair<Boolean,List<ExtractorLink>> {
        val links=mutableListOf<ExtractorLink>();val subs=mutableListOf<SubtitleFile>()
        val ok=provider.loadLinks(data,false,subs::add,links::add)
        check(PlaybackApiFixture.requests.none {it.url.encodedPath.contains("play-info")})
        return ok to links
    }
    fun fixture(public: String=html,native: JSONObject?=meta(),path: String=slug,se: Int=0,ep: Int=0,subject: String=id) {
        PlaybackApiFixture.requests.clear()
        PlaybackApiFixture.responder={req -> when {
            req.url.encodedPath.endsWith("ranking-list") -> response(req,token=true)
            req.url.host=="moviebox.ph" -> {
                check(req.header("Authorization")==null && req.header("Cookie")==null)
                response(req,public)
            }
            req.url.encodedPath.endsWith("subject-api/get") -> if(native==null) response(req,code=403) else response(req,JSONObject().put("code",0).put("data",native).toString())
            req.url.encodedPath.endsWith("subject/play") -> {
                if(req.url.queryParameter("detailPath")!=path) response(req,"""{"code":0,"data":{"streams":[]}}""")
                else {
                    check(req.url.queryParameter("subjectId")==subject)
                    check(req.url.queryParameter("se")==se.toString() && req.url.queryParameter("ep")==ep.toString())
                    response(req,"""{"code":0,"data":{"subjectId":"$subject","se":$se,"ep":$ep,"streams":[{"id":"s1","url":"$media"}]}}""")
                }
            }
            else -> response(req,code=403)
        } }
    }
    try {
        test("actual Moviebox search and quick search recover Indonesian query through public catalog without bearer") {
            for(query in listOf("Hari Baru","Brand New Day")) {
                PlaybackApiFixture.requests.clear()
                PlaybackApiFixture.responder={ req -> when {
                    req.url.host=="moviebox.ph" -> {
                        check(req.url.queryParameter("keyword")==query && req.header("Authorization")==null && req.header("Cookie")==null)
                        response(req,html)
                    }
                    else -> response(req,code=403)
                } }
                val results=MovieBoxProvider().quickSearch(query)
                check(results.single().url.endsWith("id=$id") && results.single().name==title)
            }
        }
        test("native search survives public catalog failure and duplicate public IDs do not replace native results") {
            for(publicAvailable in listOf(false,true)) {
                PlaybackApiFixture.requests.clear()
                PlaybackApiFixture.responder={ req -> when {
                    req.url.encodedPath.endsWith("ranking-list") -> response(req,token=true)
                    req.url.encodedPath.endsWith("search/v2") -> response(req,"""{"code":0,"data":{"results":[{"subjects":[{"subjectId":"$id","title":"$title","subjectType":1}]}]}}""")
                    req.url.host=="moviebox.ph" -> if(publicAvailable) response(req,html) else response(req,code=503)
                    else -> response(req,code=403)
                } }
                val results=MovieBoxProvider().search(title)
                check(results.single().url.endsWith("id=$id") && results.single().name==title)
            }
        }
        test("actual old Spider-Man payload recovers authoritative H5 path before validated callback") {
            fixture()
            val result=playback(MovieBoxProvider(),"""{"subjectId":"$id","se":0,"ep":0,"subjectType":1}""")
            check(result.first && result.second.single().source=="MovieBox")
            check(PlaybackApiFixture.requests.count {it.url.host=="moviebox.ph"}==1)
        }
        test("generic native detail URL recovers the same-ID public context instead of requesting slug detail") {
            fixture(native=meta().put("detailUrl","https://api3.aoneroom.com/detail?id=$id"))
            check(playback(MovieBoxProvider(),"""{"subjectId":"$id","se":0,"ep":0,"subjectType":1}""").first)
            check(PlaybackApiFixture.requests.filter {it.url.encodedPath.endsWith("subject/play")}.all {it.url.queryParameter("detailPath")==slug})
        }
        test("actual selected path survives total metadata failure and exact movie 0/0 is retained") {
            fixture(native=null)
            val result=playback(MovieBoxProvider(),"""{"subjectId":"$id","se":9,"ep":9,"subjectType":1,"title":"$title","detailPath":"$slug"}""")
            check(result.first && result.second.size==1)
            check(PlaybackApiFixture.requests.none {it.url.host=="moviebox.ph"})
        }
        test("a different catalog subject never produces Spider-Man callback") {
            fixture(public=catalog("999" to slug))
            val result=playback(MovieBoxProvider(),"""{"subjectId":"$id","se":0,"ep":0,"subjectType":1}""")
            check(!result.first && result.second.isEmpty())
        }
        test("explicit metadata path retains priority over stale saved path") {
            fixture(native=meta().put("detailPath",slug))
            check(playback(MovieBoxProvider(),"""{"subjectId":"$id","se":0,"ep":0,"subjectType":1,"detailPath":"wrong-saved-path"}""").first)
            check(PlaybackApiFixture.requests.none {it.url.host=="moviebox.ph"})
        }
        test("new context never changes a selected series season 2 episode 4") {
            fixture(native=null,path="verified-series-context",se=2,ep=4,subject="901")
            check(playback(MovieBoxProvider(),"""{"subjectId":"901","se":2,"ep":4,"subjectType":2,"detailPath":"verified-series-context"}""").first)
        }
        test("actual detail load serializes selected ID title and path for playback after metadata denial") {
            app.responseParser=Class.forName("com.lagradost.cloudstream3.MainActivityKt\$jsonResponseParser\$1")
                .getDeclaredConstructor().apply {isAccessible=true}.newInstance() as com.lagradost.nicehttp.ResponseParser
            var loading=true
            fixture(native=null)
            val originalResponder=PlaybackApiFixture.responder
            PlaybackApiFixture.responder={req -> if(loading && req.url.encodedPath.endsWith("subject-api/get"))
                response(req,JSONObject().put("code",0).put("data",meta().put("subjectType",1).put("detailPath",slug)).toString())
                else originalResponder(req)
            }
            val provider=MovieBoxProvider()
            val loaded=provider.load("https://api3.aoneroom.com/detail?id=$id") as MovieLoadResponse
            val payload=JSONObject(loaded.dataUrl)
            check(payload.getString("subjectId")==id && payload.getString("title")==title && payload.getString("detailPath")==slug)
            loading=false
            check(playback(provider,loaded.dataUrl).first)
        }
        test("actual detail rejects mismatched subject returned by metadata server") {
            fixture(native=meta("999").put("subjectType",1))
            check(MovieBoxProvider().load("https://api3.aoneroom.com/detail?id=$id")==null)
        }
    } finally {
        server.stop(0)
        app.baseClient.connectionPool.evictAll();app.baseClient.dispatcher.executorService.shutdown()
        MovieboxPlayback.client.connectionPool.evictAll();MovieboxPlayback.client.dispatcher.executorService.shutdown()
    }
    println("PLAYBACK_CONTEXT $count PASS; fixture/local HTTP only; updated Android plugin NOT_TESTED")
}
