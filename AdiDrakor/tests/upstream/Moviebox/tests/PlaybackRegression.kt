package com.Moviebox

import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Synthetic, sanitized schema/transport fixtures. These do NOT prove MovieBox server/device playback. */
fun main() = runBlocking {
    var passed = 0
    suspend fun test(name: String, block: suspend () -> Unit) {
        block(); passed++; println("PASS $name")
    }
    val url = "https://cdn.example.test/title/index.mpd?Signature=a%2Bb&Policy=p%3D"
    fun stream(u: String = url, format: String = "DASH") = MovieboxPlayback.Stream(u,format,"s1",1080,"h264","test-cookie","X-MB-Token","English","H5")
    val video = byteArrayOf(0,0,0,24) + "ftypisom".toByteArray() + ByteArray(512)
    fun sample(u: String, body: String, code: Int = 200) = MovieboxPlayback.Sample(u,code,"text/plain",body.toByteArray())
    fun media(u: String) = MovieboxPlayback.Sample(u,206,"application/octet-stream",video)
    suspend fun validate(body: String, responses: Map<String,MovieboxPlayback.Sample> = emptyMap(), u: String = url): MovieboxPlayback.Validated? {
        return MovieboxPlayback.validate(stream(u),emptyMap()) { target,_,_ ->
            if(target == u) sample(u,body) else responses[target]
        }
    }
    test("H5 movie 0/0 and signed query preserved") {
        val r = MovieboxPlayback.parse("""{"code":0,"data":{"streams":[{"id":"s1","url":"$url","resolutions":"360,720,1080","codecName":"h264"}]}}""","H5","123",0,0)
        check(r.single().url == url && r.single().quality == 1080)
    }
    test("H5 DASH HLS and direct groups with audio and signHeaderKey") {
        val raw = """{"code":0,"data":{"dash":[{"url":"https://cdn.example.test/a.mpd","signCookie":"cookie","signHeaderKey":"X-Stream-Token"}],"hls":[{"url":"https://cdn.example.test/a.m3u8"}],"streams":[{"url":"https://cdn.example.test/a.mp4"}]}}"""
        val r = MovieboxPlayback.parse(raw,"H5","123",1,1,"Indonesia")
        check(r.size == 3 && r[0].format == "DASH" && r[1].format == "HLS" && r[2].format == "")
        check(r[0].audio == "Indonesia" && r[0].signHeader == "X-Stream-Token")
    }
    test("empty data and API error rejected") {
        check(MovieboxPlayback.parse("""{"code":0,"data":{}}""","H5","123",0,0).isEmpty())
        check(MovieboxPlayback.parse("""{"code":401,"data":{"streams":[{"url":"$url"}]}}""","H5","123",0,0).isEmpty())
    }
    test("removed native resources and v2 streams are never accepted") {
        val raw = """{"data":{"resourceDetectors":[{"resolutionList":[{"resourceLink":"https://cdn.example.test/native.mp4","se":2,"ep":4}]}]}}"""
        check(MovieboxPlayback.parse(raw,"H5","123",2,4).isEmpty())
        val streams = """{"data":{"streams":[{"url":"https://cdn.example.test/old.mp4"}]}}"""
        for (source in listOf("V2","Native")) check(MovieboxPlayback.parse(streams,source,"123",0,0).isEmpty())
    }
    test("wrong subject and wrong top-level episode rejected") {
        for (extra in listOf("\"subjectId\":\"124\",", "\"se\":1,\"ep\":2,")) {
            check(MovieboxPlayback.parse("""{"data":{$extra"streams":[{"url":"$url"}]}}""","H5","123",2,4).isEmpty())
        }
    }
    test("HTML notice response cannot pass by URL or HTTP 200") {
        check(validate("<!DOCTYPE html><html>upgrade</html>") == null)
        check(MovieboxPlayback.format(stream(),sample(url,"{\"error\":\"expired\"}")) == null)
    }
    test("known notice URL rejected") {
        check(MovieboxPlayback.notice("https://cdn.example.test/notice.mp4"))
        check(MovieboxPlayback.format(stream(),media("https://cdn.example.test/upgrade-notice.mp4")) == null)
    }
    test("direct MP4 sniffed without relying on extension or format label") {
        check(MovieboxPlayback.format(stream(format="HLS"),media(url)) == MovieboxPlayback.Format.VIDEO)
    }
    test("signed cookie scope same directory, no cross-host/scheme/path leakage") {
        val h = MovieboxPlayback.mediaHeaders(stream(),"https://web.example.test","https://web.example.test/movies/title",MovieboxPlayback.WEB_UA)
        check(h["Cookie"] == "test-cookie" && h["X-MB-Token"] == "test-cookie")
        check(!h.containsKey("Authorization"))
        check(MovieboxPlayback.scopeHeaders(h,url,"https://cdn.example.test/title/video/1.m4s")["Cookie"] == "test-cookie")
        for (other in listOf("https://other.example.test/title/1.m4s","https://cdn.example.test/another/1.m4s","http://cdn.example.test/title/1.m4s")) {
            check(MovieboxPlayback.scopeHeaders(h,url,other).keys.none { it == "Cookie" || it == "X-MB-Token" })
        }
    }
    test("malicious signHeaderKey cannot override authorization or inject CRLF") {
        check(!MovieboxPlayback.mediaHeaders(stream().copy(signHeader="Authorization"),"o","r","ua").containsKey("Authorization"))
        check(!MovieboxPlayback.mediaHeaders(stream().copy(cookie="a\r\nb"),"o","r","ua").containsKey("Cookie"))
    }
    test("dedup preserves differing signed queries and audio, ignores fragments") {
        check(MovieboxPlayback.key(stream()) == MovieboxPlayback.key(stream(url+"#fragment")))
        check(MovieboxPlayback.key(stream()) != MovieboxPlayback.key(stream(url+"&token=other")))
        check(MovieboxPlayback.key(stream()) != MovieboxPlayback.key(stream().copy(audio="Indonesia",category=MovieboxPlayback.AudioCategory.INDONESIAN)))
    }
    test("validated signature rotations deduplicate without mutating signed request URLs") {
        val a=stream("https://cdn.example.test/a.mp4?Signature=one&Policy=p&quality=1080")
        val b=stream("https://cdn.example.test/a.mp4?Signature=two&Policy=q&quality=1080")
        check(MovieboxPlayback.key(a)==MovieboxPlayback.key(b))
        check(MovieboxPlayback.requestKey(a)!=MovieboxPlayback.requestKey(b))
        check(a.url.endsWith("Signature=one&Policy=p&quality=1080"))
        check(MovieboxPlayback.key(a)!=MovieboxPlayback.key(b.copy(url=b.url.replace("quality=1080","quality=720"))))
    }
    test("labels normalize language codec and avoid duplicated quality") {
        val label=MovieboxPlayback.label(stream().copy(audio="Indonesian dub Audio",codec="hevc",category=MovieboxPlayback.AudioCategory.INDONESIAN),MovieboxPlayback.Format.DASH)
        check(label=="MovieBox • Indonesia • DASH • H.265")
        check(!label.contains("1080") && !label.contains("dub",true))
        check(MovieboxPlayback.audioName("English dub")=="Inggris")
        check(MovieboxPlayback.audioName("")=="Asli")
        check(MovieboxPlayback.audioOrder("Indonesian dub")<MovieboxPlayback.audioOrder("English dub"))
    }
    test("exact-identity flat and nested H5 detail metadata; explicit detailPath wins") {
        val raw="""{"code":0,"data":{"subject":{"subjectId":"123","detailPath":"correct-slug","detailUrl":"https://movie-box.co/movies/old-slug?id=123"}}}"""
        val detail=MovieboxPlayback.metadata(raw,"123")!!
        check(MovieboxPlayback.detailPath(detail)=="correct-slug")
        check(MovieboxPlayback.metadata(raw,"456")==null)
        check(MovieboxPlayback.metadata("""{"code":404,"data":{"subjectId":"123"}}""","123")==null)
    }
    test("Reacher empty mobile paths use exact server Share identities and case") {
        for ((id,path) in mapOf("3620534210064175096" to "reacher-e1nw56h5sj4",
                "4149489342181954296" to "reacher-indonesian-WjrjjPIHwW4")) {
            val detail=org.json.JSONObject().put("subjectId",id).put("detailUrl","")
            check(MovieboxPlayback.detailPath(detail)==path)
            check(MovieboxPlayback.detailPath(org.json.JSONObject(),id)==path)
        }
    }
    test("Reacher fallback never replaces an explicit API path or detail URL") {
        val id="3620534210064175096"
        check(MovieboxPlayback.detailPath(org.json.JSONObject().put("subjectId",id)
            .put("detailPath","server-new-path"),id)=="server-new-path")
        check(MovieboxPlayback.detailPath(org.json.JSONObject().put("subjectId",id)
            .put("detailUrl","https://movie-box.co/detail/server-url-path?id=$id"),id)=="server-url-path")
    }
    test("Reacher mapping cannot leak to another subject or mismatched metadata identity") {
        check(MovieboxPlayback.detailPath(org.json.JSONObject().put("subjectId","123"))=="")
        check(MovieboxPlayback.detailPath(org.json.JSONObject(),"123")=="")
        check(MovieboxPlayback.detailPath(org.json.JSONObject().put("subjectId","123"),"3620534210064175096")=="")
        check(MovieboxPlayback.detailPath(org.json.JSONObject().put("subjectId","4149489342181954296"),"3620534210064175096")=="")
    }
    test("House of the Dragon exact Share identities preserve path case and API precedence") {
        val paths=mapOf("6890674365592471456" to "house-of-the-dragon-indonesian-2vLI2nqm1d8",
            "5373384118887662624" to "house-of-the-dragon-uTatZHG9Wo6",
            "7721864815710718808" to "house-of-the-dragon-G7LGtmOdqc9",
            "3135126230249492760" to "house-of-the-dragon-kkZZ8pvUAJ3")
        for ((id,path) in paths) {
            check(MovieboxPlayback.detailPath(JSONObject(),id)==path)
            check(MovieboxPlayback.detailPath(JSONObject().put("subjectId",id).put("detailUrl",""),id)==path)
            check(MovieboxPlayback.detailPath(JSONObject().put("subjectId",id).put("detailPath","api-path"),id)=="api-path")
            check(MovieboxPlayback.detailPath(JSONObject().put("subjectId",id)
                .put("detailUrl","https://movie-box.co/movies/api-url?id=$id"),id)=="api-url")
            for (other in paths.keys.filter { it!=id })
                check(MovieboxPlayback.detailPath(JSONObject().put("subjectId",other),id)=="")
        }
    }
    test("House of the Dragon context cannot override a mismatched episode or subject") {
        val id="5373384118887662624"
        for (identity in listOf("\"subjectId\":\"7721864815710718808\"", "\"se\":1,\"ep\":2")) {
            check(MovieboxPlayback.parse("""{"code":0,"data":{$identity,"streams":[{"url":"$url"}]}}""",
                "H5",id,1,1).isEmpty())
        }
    }
    test("audio whitelist accepts exact official APK codes and aliases") {
        val groups = mapOf(
            MovieboxPlayback.AudioCategory.ORIGINAL to listOf("original","DEFAULT","Asli"),
            MovieboxPlayback.AudioCategory.INDONESIAN to listOf("id","ind","in_id","Indonesia","Indonesian dub Audio","bahasa indonesia"),
            MovieboxPlayback.AudioCategory.ENGLISH to listOf("en","eng","English dub","Inggris"))
        groups.forEach { (category,aliases) -> aliases.forEach { check(MovieboxPlayback.audioCategory(it)==category) } }
    }
    test("Hindi other languages unknown and missing dub metadata are unsupported") {
        for (language in listOf("Hindi","hin","hi","Spanish","Portuguese","Tamil","Telugu","Arabic","French",
            "Korean","Japanese","Chinese","Bengali","Urdu","", "unidentified", "id-other", "english-ish")) {
            check(MovieboxPlayback.audioCategory(language)==MovieboxPlayback.AudioCategory.UNSUPPORTED)
        }
        check(MovieboxPlayback.dubCategory(JSONObject("""{"subjectId":"456"}"""))==MovieboxPlayback.AudioCategory.UNSUPPORTED)
        check(MovieboxPlayback.dubCategory(JSONObject("""{"lan":"hi","lanName":"English"}"""))==MovieboxPlayback.AudioCategory.UNSUPPORTED)
    }
    test("stream language overrides a supported dubbed subject and rejects conflicting labels") {
        val raw="""{"data":{"streams":[{"url":"$url","lanName":"Hindi"},{"url":"$url","lan":"en"},{"url":"$url"},{"url":"$url","lanName":"English","lan":"hi"}]}}"""
        val parsed=MovieboxPlayback.parse(raw,"H5","123",0,0,"Indonesia")
        check(parsed.map { it.category }==listOf(MovieboxPlayback.AudioCategory.UNSUPPORTED,
            MovieboxPlayback.AudioCategory.ENGLISH,MovieboxPlayback.AudioCategory.INDONESIAN,
            MovieboxPlayback.AudioCategory.UNSUPPORTED))
    }
    test("original Korean audio is preserved by exact original subject language evidence") {
        val raw="""{"data":{"streams":[{"url":"$url","lanName":"Korean","lan":"ko"},{"url":"$url"},{"url":"$url","lanName":"Hindi"}]}}"""
        val parsed=MovieboxPlayback.parse(raw,"H5","123",0,0,"Asli","Korean")
        check(parsed.map { it.category }==listOf(MovieboxPlayback.AudioCategory.ORIGINAL,
            MovieboxPlayback.AudioCategory.ORIGINAL,MovieboxPlayback.AudioCategory.UNSUPPORTED))
        check(parsed.take(2).all { MovieboxPlayback.label(it,MovieboxPlayback.Format.DASH).startsWith("MovieBox • Asli") })
    }
    test("explicit original marker preserves the stream even with a foreign language code") {
        val raw="""{"data":{"streams":[{"url":"$url","lanName":"Original","lan":"ja"}]}}"""
        val parsed=MovieboxPlayback.parse(raw,"H5","123",0,0)
        check(parsed.single().category==MovieboxPlayback.AudioCategory.ORIGINAL)
    }
    test("same URL retains distinct quality codec and content selector alternatives") {
        val a=stream()
        check(MovieboxPlayback.key(a)!=MovieboxPlayback.key(a.copy(quality=720)))
        check(MovieboxPlayback.requestKey(a)!=MovieboxPlayback.requestKey(a.copy(quality=720)))
        check(MovieboxPlayback.key(a)!=MovieboxPlayback.key(a.copy(codec="hevc")))
        check(MovieboxPlayback.key(a)!=MovieboxPlayback.key(a.copy(url=a.url+"&audioTrack=2")))
    }
    test("subtitle languages have no audio whitelist") {
        val raw="""{"data":{"extCaptions":[{"lanName":"Hindi","url":"https://subs.example.test/hi.srt"},{"lanName":"Japanese","url":"https://subs.example.test/ja.srt"},{"lanName":"Arabic","url":"https://subs.example.test/ar.srt"}]}}"""
        check(MovieboxPlayback.captionEntries(raw).map { it.first }==listOf("Hindi","Japanese","Arabic"))
    }
    val mpd = """<?xml version="1.0"?><MPD><Period><AdaptationSet mimeType="video/mp4"><Representation id="v1" bandwidth="1000" codecs="avc1.42"><SegmentTemplate initialization="init-${'$'}RepresentationID${'$'}.mp4" media="v-${'$'}Number%05d${'$'}.m4s" startNumber="7"/></Representation></AdaptationSet><AdaptationSet mimeType="audio/mp4"><Representation id="a1"><SegmentTemplate initialization="audio-init.mp4" media="audio-${'$'}Time${'$'}.m4s"><SegmentTimeline><S t="42" d="10"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet></Period></MPD>"""
    val mediaUrls = listOf("init-v1.mp4","v-00007.m4s","audio-init.mp4","audio-42.m4s").associate { val u="https://cdn.example.test/title/$it"; u to media(u) }
    test("DASH manifest validates initialization video AND audio segments") { check(validate(mpd,mediaUrls)?.format == MovieboxPlayback.Format.DASH) }
    test("DASH audio segment 403 invalidates source") { check(validate(mpd,mediaUrls - "https://cdn.example.test/title/audio-42.m4s") == null) }
    test("DRM MPD rejected without attempting a bypass") { check(validate(mpd.replace("<Period>","<Period><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9\"/>"),mediaUrls) == null) }
    test("DASH inherited template and relative BaseURL") {
        val body = """<MPD><BaseURL>media/</BaseURL><Period><AdaptationSet mimeType="video/mp4"><SegmentTemplate media="v.m4s"/><Representation id="v"/></AdaptationSet></Period></MPD>"""
        val u="https://cdn.example.test/title/media/v.m4s"
        check(validate(body,mapOf(u to media(u))) != null)
    }
    test("HLS master validates variant and its media segment") {
        val u="https://cdn.example.test/title/master.m3u8";val child="https://cdn.example.test/title/720/index.m3u8";val segment="https://cdn.example.test/title/720/1.ts"
        val ts=ByteArray(376).apply { this[0]=0x47;this[188]=0x47 }
        check(validate("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n720/index.m3u8\n",mapOf(child to sample(child,"#EXTM3U\n#EXTINF:5,\n1.ts\n"),segment to MovieboxPlayback.Sample(segment,206,"video/mp2t",ts)),u)?.format == MovieboxPlayback.Format.HLS)
    }
    test("HLS master HTTP 200 but expired variant fails") {
        val u="https://cdn.example.test/title/master.m3u8";val child="https://cdn.example.test/title/v.m3u8"
        check(validate("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8\n",mapOf(child to sample(child,"expired",403)),u) == null)
    }
    test("subtitle extCaptions language fallbacks preserve signed URLs") {
        val r=MovieboxPlayback.captionEntries("""{"data":{"extCaptions":[{"lanName":"Indonesia","url":"https://subs.example.test/a.srt?Signature=a%2Bb"},{"language":"English","url":"https://subs.example.test/b.vtt"}]}}""")
        check(r[0].first == "Indonesia" && r[0].second.endsWith("a%2Bb") && r[1].first == "English")
    }
    test("timeout transport returns no valid link") { check(MovieboxPlayback.validate(stream(),emptyMap()) { _,_,_ -> null } == null) }
    println("REGRESSION $passed PASS; server/device playback NOT_TESTED")
}
