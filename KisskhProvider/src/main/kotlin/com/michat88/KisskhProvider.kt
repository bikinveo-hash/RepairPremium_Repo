package com.michat88

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

class KisskhProvider : MainAPI() {
    override var mainUrl = "https://kisskh.ovh"
    override var name = "Kisskh"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Anime)

    override val mainPage = mainPageOf(
        "&type=0&sub=0&country=0&status=0&order=2" to "Latest",
        "&type=2&sub=0&country=2&status=0&order=1" to "Movie Popular",
        "&type=1&sub=0&country=2&status=0&order=1" to "TVSeries Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = app.get("$mainUrl/api/DramaList/List?page=$page${request.data}").parsedSafe<Responses>()
        val home = data?.data?.mapNotNull { it.toSearchResponse() } ?: throw ErrorLoadingException("Gagal load beranda")
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Media.toSearchResponse() = newAnimeSearchResponse(title ?: "", "$title/$id", TvType.TvSeries) {
        this.posterUrl = thumbnail
        addSub(episodesCount)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.split("/").last()
        val res = app.get("$mainUrl/api/DramaList/Drama/$id?isq=false").parsedSafe<MediaDetail>() ?: throw ErrorLoadingException("Detail null")
        val episodes = res.episodes?.map { eps ->
            newEpisode(Data(res.title, eps.number?.toInt(), res.id, eps.id).toJson()) {
                this.name = "Episode ${eps.number}"
                this.episode = eps.number?.toInt()
            }
        } ?: throw ErrorLoadingException("No Episode")

        return newTvSeriesLoadResponse(res.title ?: "", url, TvType.TvSeries, episodes.reversed()) {
            this.posterUrl = res.thumbnail
            this.plot = res.description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        [span_17](start_span)[span_18](start_span)// Tautan API Google Script rahasia[span_17](end_span)[span_18](end_span)
        val vApi = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val sApi = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="
        
        val loadData = parseJson<Data>(data)
        val kkey = app.get("$vApi${loadData.epsId}&version=2.8.10").parsedSafe<Key>()?.key ?: ""
        
        [span_19](start_span)// Referer rahasia dan ekstensi .png palsu[span_19](end_span)
        val videoUrl = "$mainUrl/api/DramaList/Episode/${loadData.epsId}.png?err=false&ts=&time=&kkey=$kkey"
        val ref = "$mainUrl/Drama/${loadData.title?.replace(Regex("[^a-zA-Z0-9]"), "-")}/Episode-${loadData.eps}?id=${loadData.id}&ep=${loadData.epsId}&page=0&pageSize=100"

        app.get(videoUrl, referer = ref).parsedSafe<Sources>()?.let { source ->
            [span_20](start_span)// Menggunakan Huruf Kapital sesuai struktur JAR[span_20](end_span)
            listOfNotNull(source.Video, source.ThirdParty).amap { link ->
                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(this.name, link, referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl)).forEach(callback)
                } else if (link.contains("mp4")) {
                    callback.invoke(newExtractorLink(this.name, this.name, link, INFER_TYPE) { this.referer = mainUrl })
                } else {
                    loadExtractor(link.substringBefore("=http"), "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        // Load Subtitles
        val kkeySub = app.get("$sApi${loadData.epsId}&version=2.8.10").parsedSafe<Key>()?.key ?: ""
        app.get("$mainUrl/api/Sub/${loadData.epsId}?kkey=$kkeySub").parsedSafe<List<Subtitle>>()?.forEach { sub ->
            subtitleCallback.invoke(newSubtitleFile(sub.label ?: "Indonesian", sub.src ?: return@forEach))
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.request.url.toString().contains(".txt")) {
            val decrypted = response.body.string().split(Regex("^\\d+$", RegexOption.MULTILINE))
                .filter { it.isNotBlank() }.mapIndexed { i, chunk ->
                    val lines = chunk.trim().split("\n")
                    val body = lines.drop(1).joinToString("\n") { decrypt(it) }
                    "${i + 1}\n${lines.first()}\n$body"
                }.joinToString("\n\n")
            response.newBuilder().body(decrypted.toResponseBody(response.body.contentType())).build()
        } else response
    }

    data class Data(val title: String?, val eps: Int?, val id: Int?, val epsId: Int?)
    [span_21](start_span)// PERBAIKAN: Huruf Kapital di @JsonProperty agar link ditemukan[span_21](end_span)
    data class Sources(@JsonProperty("Video") val Video: String?, @JsonProperty("ThirdParty") val ThirdParty: String?)
    data class Subtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)
    data class Responses(@JsonProperty("data") val data: ArrayList<Media>? = arrayListOf())
    data class Media(@JsonProperty("episodesCount") val episodesCount: Int?, @JsonProperty("thumbnail") val thumbnail: String?, @JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    data class Episodes(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    data class MediaDetail(@JsonProperty("description") val description: String?, @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(), @JsonProperty("thumbnail") val thumbnail: String?, @JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?, @JsonProperty("country") val country: String?, @JsonProperty("status") val status: String?, @JsonProperty("type") val type: String?, @JsonProperty("releaseDate") val releaseDate: String?)
    data class Key(val key: String)
}
