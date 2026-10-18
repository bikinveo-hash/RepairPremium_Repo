package com.Moviebox

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic metadata fixtures against actual SDK Episode + provider; no video/network changes. */
fun main() = runBlocking {
    // The minimal playback HTTP shim has no parser. Detail loading uses parsedSafe,
    // so install the actual SDK's parser, as the real application's Requests does.
    app.responseParser=Class.forName("com.lagradost.cloudstream3.MainActivityKt\$jsonResponseParser\$1")
        .getDeclaredConstructor().apply { isAccessible=true }.newInstance() as com.lagradost.nicehttp.ResponseParser
    var count=0
    suspend fun test(name: String, block: suspend () -> Unit) { block();count++;println("PASS $name") }
    val builder=MovieBoxProvider()
    fun ep(se: Int=1,number: Int=1)=builder.newEpisode("""{"subjectId":"3620534210064175096","se":$se,"ep":$number,"subjectType":2}""") {
        name="Episode $number";season=se;episode=number
    }
    val poster="https://images.example.test/reacher-cover.jpg"
    val search="""{"results":[{"id":108978,"name":"Reacher","first_air_date":"2022-02-03"}]}"""
    fun season(se: Int=1,entries: String="""{"episode_number":1,"season_number":$se,"show_id":108978,"name":"Welcome to Margrave","still_path":"/still-one.jpg","overview":"Episode overview","vote_average":7.7,"air_date":"2022-02-03"}""")=
        """{"season_number":$se,"episodes":[$entries]}"""

    test("metadata title strips explicit dub and season labels without fuzzy title matching") {
        check(MovieboxEpisodes.lookupTitle("Reacher [Indonesian] S1-S4",2022)=="Reacher")
        check(MovieboxEpisodes.lookupTitle("Reacher S1-S4 [English dub]",2022)=="Reacher")
        check(MovieboxEpisodes.lookupTitle("Reacher (2022)",2022)=="Reacher")
        check(MovieboxEpisodes.lookupTitle("The English",2022)=="The English")
        check(MovieboxEpisodes.lookupTitle("Reacher [Unrelated]",2022)=="Reacher [Unrelated]")
    }
    test("exact TV title and year required; ambiguous and wrong-year results cannot decorate") {
        check(MovieboxEpisodes.seriesId(search,"Reacher",2022)==108978)
        check(MovieboxEpisodes.seriesId(search,"Reacher",2023)==null)
        check(MovieboxEpisodes.seriesId(search,"Jack Reacher",2022)==null)
        check(MovieboxEpisodes.seriesId("""{"results":[{"id":1,"name":"Reacher","first_air_date":"2022-01-01"},{"id":2,"name":"Reacher","first_air_date":"2022-02-03"}]}""","Reacher",2022)==null)
        check(MovieboxEpisodes.seriesId("not-json","Reacher",2022)==null)
    }
    test("Unicode original title matches without dropping non-Latin title identity") {
        check(MovieboxEpisodes.seriesId("""{"results":[{"id":9,"name":"Other","original_name":"秘密","first_air_date":"2022-01-01"}]}""","秘密",2022)==9)
    }
    test("episode cards receive still title overview rating and UTC air date") {
        val episode=ep();val originalData=episode.data
        MovieboxEpisodes.decorate("Reacher",2022,poster,listOf(episode)) { if(it.startsWith("/search/")) search else season() }
        check(episode.name=="Welcome to Margrave")
        check(episode.posterUrl=="https://image.tmdb.org/t/p/w300/still-one.jpg")
        check(episode.description=="Episode overview" && episode.score!=null && episode.date==1643846400000L)
        check(episode.data==originalData && episode.season==1 && episode.episode==1)
    }
    test("same episode number on different seasons retains separate metadata and playback data") {
        val episodes=listOf(ep(1,1),ep(2,1),ep(1,2));val original=episodes.map { it.data }
        MovieboxEpisodes.decorate("Reacher",2022,poster,episodes) { path -> when {
            path.startsWith("/search/") -> search
            path.contains("/season/2?") -> season(2,"""{"episode_number":1,"season_number":2,"show_id":108978,"name":"ATM","still_path":"/season-two.jpg"}""")
            else -> season()
        } }
        check(episodes.map { it.data }==original)
        check(episodes.map { it.season to it.episode }==listOf(1 to 1,2 to 1,1 to 2))
        check(episodes[0].name=="Welcome to Margrave" && episodes[1].name=="ATM")
        check(episodes[2].name=="Episode 2" && episodes[2].posterUrl==poster)
        check(episodes[1].posterUrl!!.endsWith("season-two.jpg"))
    }
    test("TMDB cannot add remove reorder or substitute Moviebox catalog episodes") {
        val episodes=listOf(ep(1,3),ep(1,1));val original=episodes.map { it.data }
        MovieboxEpisodes.decorate("Reacher",2022,poster,episodes) { if(it.startsWith("/search/")) search else season() }
        check(episodes.size==2 && episodes.map { it.episode }==listOf(3,1))
        check(episodes.map { it.data }==original)
    }
    test("wrong season show identity and duplicate episode metadata are ignored") {
        check(MovieboxEpisodes.season(season(2),108978,1).isEmpty())
        check(MovieboxEpisodes.season(season(entries="""{"episode_number":1,"season_number":1,"show_id":99}"""),108978,1).isEmpty())
        val duplicate="""{"episode_number":1,"season_number":1,"show_id":108978}"""
        check(MovieboxEpisodes.season(season(entries="$duplicate,$duplicate"),108978,1).isEmpty())
    }
    test("invalid still URL date and score do not become metadata") {
        val d=MovieboxEpisodes.season(season(entries="""{"episode_number":1,"season_number":1,"show_id":108978,"still_path":"https://other.test/image.jpg","vote_average":99,"air_date":"2022-02-31"}"""),108978,1)[1]!!
        check(d.poster==null && d.rating==null && d.date==null)
    }
    test("missing still or TMDB failure uses existing series cover without changing payload") {
        for(failed in listOf(false,true)) {
            val episode=ep();val before=episode.data
            MovieboxEpisodes.decorate("Reacher",2022,poster,listOf(episode)) {
                if(failed) throw java.net.SocketTimeoutException()
                if(it.startsWith("/search/")) search else season(entries="""{"episode_number":1,"season_number":1,"show_id":108978}""")
            }
            check(episode.posterUrl==poster && episode.name=="Episode 1" && episode.data==before)
        }
    }
    test("unknown year performs no optional lookup and preserves an existing episode image") {
        val episode=ep().apply { posterUrl="https://images.example.test/already.jpg" }
        MovieboxEpisodes.decorate("Reacher",null,poster,listOf(episode)) { error("must not fetch") }
        check(episode.posterUrl!!.endsWith("already.jpg"))
    }
    test("future optional air dates cannot mark existing Moviebox episodes upcoming") {
        val episode=ep();val before=episode.data
        MovieboxEpisodes.decorate("Reacher",2022,poster,listOf(episode)) {
            if(it.startsWith("/search/")) search else season(entries="""{"episode_number":1,"season_number":1,"show_id":108978,"air_date":"2999-01-01","still_path":"/future.jpg"}""")
        }
        check(episode.date==null && episode.data==before && episode.posterUrl!!.endsWith("future.jpg"))
    }
    test("ambiguous series skips season fetches and leaves generic episode cards") {
        val episode=ep();var requests=0
        MovieboxEpisodes.decorate("Reacher",2023,poster,listOf(episode)) { requests++;search }
        check(requests==1 && episode.name=="Episode 1" && episode.posterUrl==poster)
    }
    test("one optional season HTTP failure does not discard another season metadata") {
        val episodes=listOf(ep(1,1),ep(2,1))
        MovieboxEpisodes.decorate("Reacher",2022,poster,episodes) { path -> when {
            path.startsWith("/search/") -> search
            path.contains("/season/1?") -> season()
            else -> null
        } }
        check(episodes[0].name=="Welcome to Margrave" && episodes[1].name=="Episode 1")
        check(episodes[1].posterUrl==poster)
    }
    test("season lookups are unique and have bounded concurrency") {
        val active=AtomicInteger();val maximum=AtomicInteger();val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
        val episodes=(1..5).flatMap { listOf(ep(it,1),ep(it,2)) }
        MovieboxEpisodes.decorate("Reacher",2022,poster,episodes) { path ->
            if(path.startsWith("/search/")) search else {
                requests+=path;val current=active.incrementAndGet();maximum.updateAndGet { maxOf(it,current) }
                try { delay(15);null } finally { active.decrementAndGet() }
            }
        }
        check(requests.size==5 && requests.distinct().size==5 && maximum.get()<=3)
    }
    test("parent cancellation is preserved and a cover exists before optional requests finish") {
        val episode=ep()
        val result=withTimeoutOrNull(30) {
            MovieboxEpisodes.decorate("Reacher",2022,poster,listOf(episode)) { delay(500);search };true
        }
        check(result==null && episode.posterUrl==poster && episode.name=="Episode 1")
        var cancelled=false
        try { MovieboxEpisodes.decorate("Reacher",2022,poster,listOf(ep())) { throw CancellationException("fixture") } }
        catch (_:CancellationException) { cancelled=true }
        check(cancelled)
    }

    fun response(request: Request,body: String,code: Int=200,token: Boolean=false): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).apply {
                if(token) header("x-user","{\"token\":\"fixture-guest\"}")
            }.build()
    fun installFixture(series: Boolean=true,metadataStatus: Int=200) {
        PlaybackApiFixture.requests.clear()
        PlaybackApiFixture.responder={r -> when {
            r.url.encodedPath.endsWith("ranking-list") -> response(r,"{}",token=true)
            r.url.encodedPath.endsWith("subject-api/get") -> response(r,"""{"code":0,"data":{"subjectId":"3620534210064175096","title":"Reacher","subjectType":${if(series) 2 else 1},"cover":{"url":"$poster"},"releaseDate":"2022-02-03","description":"Unchanged series plot"}}""")
            r.url.encodedPath.endsWith("season-info") -> response(r,if(series) """{"code":0,"data":{"seasons":[{"se":1,"maxEp":2}]}}""" else """{"code":0,"data":{"seasons":[]}}""")
            r.url.encodedPath=="/3/search/tv" -> response(r,search,metadataStatus)
            r.url.encodedPath=="/3/tv/108978/season/1" -> response(r,season(),metadataStatus)
            else -> response(r,"{}")
        } }
    }
    try {
        test("actual provider load decorates only display fields and keeps exact EpData and list") {
            installFixture()
            val result=MovieBoxProvider().load("https://api3.aoneroom.com/detail?id=3620534210064175096") as TvSeriesLoadResponse
            val episodes=result.episodes
            check(result.name=="Reacher" && result.plot=="Unchanged series plot" && result.posterUrl==poster)
            check(episodes.size==2 && episodes.map { it.episode }==listOf(1,2))
            check(episodes[0].name=="Welcome to Margrave" && episodes[0].posterUrl!!.contains("still-one.jpg"))
            check(episodes[1].name=="Episode 2" && episodes[1].posterUrl==poster)
            episodes.forEach { e ->
                val data=org.json.JSONObject(e.data)
                check(data.getString("subjectId")=="3620534210064175096" && data.getInt("se")==1 && data.getInt("ep")==e.episode && data.getInt("subjectType")==2)
            }
            check(PlaybackApiFixture.requests.none { it.url.encodedPath.contains("play-info") || it.url.encodedPath.endsWith("subject/play") })
        }
        test("actual provider metadata HTTP failure still returns every original playable episode") {
            installFixture(metadataStatus=503)
            val result=MovieBoxProvider().load("https://api3.aoneroom.com/detail?id=3620534210064175096") as TvSeriesLoadResponse
            val episodes=result.episodes
            check(episodes.size==2 && episodes.all { it.posterUrl==poster && it.data.contains("3620534210064175096") })
        }
        test("movie load never requests TV episode metadata") {
            installFixture(series=false)
            check(MovieBoxProvider().load("https://api3.aoneroom.com/detail?id=3620534210064175096") is MovieLoadResponse)
            check(PlaybackApiFixture.requests.none { it.url.encodedPath=="/3/search/tv" || it.url.encodedPath.contains("/season/") })
        }
    } finally {
        app.baseClient.connectionPool.evictAll();app.baseClient.dispatcher.executorService.shutdown()
    }
    println("EPISODE_METADATA $count PASS; metadata fixtures only, Android card rendering NOT_TESTED")
}
