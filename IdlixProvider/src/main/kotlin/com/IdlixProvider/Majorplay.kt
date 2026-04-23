package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class Majorplay : ExtractorApi() {
    [span_2](start_span)override var name = "Majorplay"[span_2](end_span)
    [span_3](start_span)override var mainUrl = "https://e2e.majorplay.net"[span_3](end_span)
    [span_4](start_span)override val requiresReferer = true[span_4](end_span)

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            [span_5](start_span)// 1. Ambil Video ID dari URL[span_5](end_span)
            val videoId = url.split("/").lastOrNull() ?: return
            
            [span_6](start_span)// 2. Request Token dan Metadata dari API Majorplay[span_6](end_span)
            val response = app.get(
                url = "$mainUrl/api/token/viewer?videoId=$videoId", 
                referer = url, 
                headers = mapOf("Origin" to mainUrl)
            ).parsedSafe<MajorplayResponse>()
            
            [span_7](start_span)// 3. Ekstrak Subtitle[span_7](end_span)
            response?.subtitles?.forEach { sub ->
                val lang = sub.label ?: sub.lang ?: "Indonesian"
                val subUrl = sub.path ?: return@forEach
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }

            [span_8](start_span)// 4. Tentukan URL Video Utama[span_8](end_span)
            val videoUrl = response?.hlsUrl ?: response?.primaryUrl ?: return
            
            // 5. Header Khusus untuk mengatasi Error 2004 dan Throttling
            val streamHeaders = mapOf(
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Accept" to "*/*"
            )

            [span_9](start_span)// 6. Gunakan M3u8Helper untuk membedah resolusi agar tidak tersendat[span_9](end_span)
            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = videoUrl,
                    referer = url,
                    headers = streamHeaders
                ).forEach { parsedLink ->
                    callback.invoke(parsedLink)
                }
            } else {
                [span_10](start_span)// Fallback menggunakan standar newExtractorLink terbaru[span_10](end_span)
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        [span_11](start_span)type = ExtractorLinkType.VIDEO[span_11](end_span)
                    ) {
                        this.referer = url
                        [span_12](start_span)this.quality = Qualities.Unknown.value[span_12](end_span)
                        this.headers = streamHeaders
                    }
                )
            }
        } catch (e: Exception) { 
            Log.e("adixtream", "Majorplay Error: ${e.message}") 
        }
    }

    [span_13](start_span)// Model Data untuk Response API[span_13](end_span)
    data class MajorplayResponse(
        @JsonProperty("hlsUrl") val hlsUrl: String? = null, 
        @JsonProperty("primaryUrl") val primaryUrl: String? = null, 
        @JsonProperty("subtitles") val subtitles: List<MajorSubtitle>? = null
    )
    
    data class MajorSubtitle(
        @JsonProperty("lang") val lang: String? = null, 
        @JsonProperty("label") val label: String? = null, 
        @JsonProperty("path") val path: String? = null
    )
}
