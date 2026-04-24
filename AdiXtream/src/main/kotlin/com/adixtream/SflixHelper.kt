package com.adixtream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

object SflixHelper {
    private var currentToken: String? = null
    private const val sflixMainUrl = "https://sflix.film"

    private suspend fun getSflixHeaders(): MutableMap<String, String> {
        if (currentToken == null) {
            try {
                val response = app.get(
                    "https://h5-api.aoneroom.com/wefeed-h5api-bff/country-code",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Origin" to sflixMainUrl,
                        "Referer" to "$sflixMainUrl/"
                    )
                )
                val cookies = response.okhttpResponse.headers("set-cookie")
                val tokenCookie = cookies.find { it.contains("token=") }
                if (tokenCookie != null) {
                    currentToken = tokenCookie.substringAfter("token=").substringBefore(";")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Origin" to sflixMainUrl,
            "Referer" to "$sflixMainUrl/",
            "X-Client-Info" to "{\"timezone\":\"Asia/Jakarta\"}",
            "X-Request-Lang" to "en",
            "X-Source" to "null"
        )

        if (!currentToken.isNullOrEmpty()) {
            headers["Authorization"] = "Bearer $currentToken"
            headers["Cookie"] = "token=$currentToken; sflix_token=%22$currentToken%22; sflix_i18n_lang=en"
        }
        return headers
    }

    suspend fun getLinks(
        title: String,
        isTvSeries: Boolean,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val searchUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search"
            val payload = mapOf("keyword" to title, "page" to "1", "perPage" to 10, "subjectType" to 0)
            
            val searchRes = app.post(searchUrl, headers = getSflixHeaders(), json = payload).parsedSafe<SflixSearchResponse>()
            
            // Sflix subjectType: 1 = Movie, selain 1 = Series
            val matchItem = searchRes?.data?.items?.firstOrNull { 
                if (isTvSeries) it.subjectType != 1 else it.subjectType == 1 
            } ?: return
            
            val detailPath = matchItem.detailPath ?: return
            
            val detailUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=$detailPath"
            val detailRes = app.get(detailUrl, headers = getSflixHeaders()).parsedSafe<SflixDetailResponse>()
            val subjectId = detailRes?.data?.subject?.subjectId ?: return

            // =========================================================
            // BAGIAN KRUSIAL: REFERER DINAMIS (PERSIS SEPERTI Sflix.kt)
            // =========================================================
            val typePath = if (isTvSeries) "series" else "movies"
            val typeQuery = if (isTvSeries) "/tv/detail" else "/movie/detail"
            val dynamicReferer = "$sflixMainUrl/spa/videoPlayPage/$typePath/$detailPath?id=$subjectId&type=$typeQuery&lang=en"

            // Tempelkan dynamic referer ke header
            val requestHeaders = getSflixHeaders()
            requestHeaders["Referer"] = dynamicReferer

            val playUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/play?subjectId=$subjectId&se=$season&ep=$episode&detailPath=$detailPath"
            
            // Pakai requestHeaders yang sudah ada dynamic referernya
            val playRes = app.get(playUrl, headers = requestHeaders).parsedSafe<SflixPlayResponse>()

            playRes?.data?.streams?.forEach { stream ->
                val videoUrl = stream.url ?: return@forEach
                val qualityStr = stream.resolutions ?: ""
                
                callback.invoke(
                    newExtractorLink(
                        source = "Sflix API",
                        name = "Sflix ${stream.format ?: "MP4"}",
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$sflixMainUrl/"
                        this.quality = getQualityFromName("${qualityStr}p")
                    }
                )
            }

            val firstStreamId = playRes?.data?.streams?.firstOrNull()?.id
            if (firstStreamId != null) {
                val capUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/caption?format=MP4&id=$firstStreamId&subjectId=$subjectId&detailPath=$detailPath"
                val capRes = app.get(capUrl, headers = requestHeaders).parsedSafe<SflixCaptionResponse>()
                capRes?.data?.captions?.forEach { cap ->
                    cap.url?.let {
                        subtitleCallback.invoke(SubtitleFile(cap.lanName ?: "Unknown", it))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==========================================
    // DATA CLASSES SFLIX HANYA DISIMPAN DI SINI
    // ==========================================
    data class SflixSearchResponse(@JsonProperty("data") val data: SflixSearchData? = null)
    data class SflixSearchData(@JsonProperty("items") val items: List<SflixSubjectItem>? = null)
    data class SflixSubjectItem(
        @JsonProperty("subjectType") val subjectType: Int? = null, 
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null
    )
    data class SflixDetailResponse(@JsonProperty("data") val data: SflixDetailData? = null)
    data class SflixDetailData(@JsonProperty("subject") val subject: SflixSubject? = null)
    data class SflixSubject(@JsonProperty("subjectId") val subjectId: String? = null)
    
    data class SflixPlayResponse(@JsonProperty("data") val data: SflixPlayData? = null)
    data class SflixPlayData(@JsonProperty("streams") val streams: List<SflixStream>? = null)
    data class SflixStream(
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("format") val format: String? = null, 
        @JsonProperty("url") val url: String? = null, 
        @JsonProperty("resolutions") val resolutions: String? = null
    )
    
    data class SflixCaptionResponse(@JsonProperty("data") val data: SflixCaptionData? = null)
    data class SflixCaptionData(@JsonProperty("captions") val captions: List<SflixCaption>? = null)
    data class SflixCaption(
        @JsonProperty("lanName") val lanName: String? = null, 
        @JsonProperty("url") val url: String? = null
    )
}
