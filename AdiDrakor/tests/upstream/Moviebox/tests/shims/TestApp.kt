@file:JvmName("MainActivityKt")
package com.lagradost.cloudstream3
import com.lagradost.nicehttp.Requests
import okhttp3.*
import java.util.concurrent.CopyOnWriteArrayList
object PlaybackApiFixture {
    val requests = CopyOnWriteArrayList<Request>()
    var responder: (Request) -> Response = { error("Missing response fixture") }
}
val app = Requests(baseClient = OkHttpClient.Builder().addInterceptor { chain ->
    PlaybackApiFixture.requests += chain.request()
    PlaybackApiFixture.responder(chain.request())
}.build())
