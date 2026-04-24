package com.adixtream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object AdimovieboxHelper {

    private const val apiUrlV1 = "https://filmboom.top"

    suspend fun getLinks(
        title: String, 
        year: Int?, 
        isTvSeries: Boolean, 
        season: Int, 
        episode: Int, 
        callback: (ExtractorLink) -> Unit, 
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val searchUrl = "$apiUrlV1/wefeed-h5-bff/web/subject/search"
            // Pakai raw string biar anti-error seperti Adicinemax21 aslimu
            val searchBody = """{"keyword":"$title","page":1,"perPage":0,"subjectType":0}"""
            
            val searchRes = app.post(
                searchUrl, 
                requestBody = searchBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            ).parsedSafe<AdimovieboxResponse>()
            
            val matchedMedia = searchRes?.data?.items?.find { item ->
                val itemYear = item.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                item.title.equals(title, true) || (item.title?.contains(title, true) == true && itemYear == year)
            } ?: return
            
            val subjectId = matchedMedia.subjectId ?: return
            val detailPath = matchedMedia.detailPath
            val playUrl = "$apiUrlV1/wefeed-h5-bff/web/subject/play?subjectId=$subjectId&se=$season&ep=$episode"
            val validReferer = "$apiUrlV1/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail&lang=en"
            
            val playRes = app.get(playUrl, referer = validReferer).parsedSafe<AdimovieboxResponse>()
            val streams = playRes?.data?.streams ?: return
            
            streams.reversed().distinctBy { it.url }.forEach { source ->
                 callback.invoke(newExtractorLink(
                     "Adimoviebox1", 
                     "Adimoviebox1", 
                     source.url ?: return@forEach, 
                     if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                 ) {
                        this.referer = "$apiUrlV1/"
                        this.quality = getQualityFromName(source.resolutions)
                 })
            }
            
            val id = streams.firstOrNull()?.id
            val format = streams.firstOrNull()?.format
            if (id != null) {
                val subUrl = "$apiUrlV1/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=$subjectId"
                app.get(subUrl, referer = validReferer).parsedSafe<AdimovieboxResponse>()?.data?.captions?.forEach { sub ->
                    subtitleCallback.invoke(SubtitleFile(sub.lanName ?: "Unknown", sub.url ?: return@forEach))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==========================================
    // DATA CLASSES PELINDUNG ADIMOVIEBOX 1
    // ==========================================
    data class AdimovieboxResponse(@JsonProperty("data") val data: AdimovieboxData? = null)
    data class AdimovieboxData(
        @JsonProperty("items") val items: ArrayList<AdimovieboxItem>? = arrayListOf(),
        @JsonProperty("streams") val streams: ArrayList<AdimovieboxStreamItem>? = arrayListOf(),
        @JsonProperty("captions") val captions: ArrayList<AdimovieboxCaptionItem>? = arrayListOf()
    )
    data class AdimovieboxItem(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null
    )
    data class AdimovieboxStreamItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolutions") val resolutions: String? = null
    )
    data class AdimovieboxCaptionItem(
        @JsonProperty("lanName") val lanName: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}
