package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val claimToken = url.substringAfter("claim=").substringBefore("&")
        if (claimToken.isEmpty()) return

        val safeHeaders = mapOf(
            "Origin" to "https://z1.idlixku.com",
            "Referer" to "https://z1.idlixku.com/",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Content-Type" to "text/plain" // WAJIB ada agar dikenali sebagai fetch normal
        )

        // LANGKAH 1: Validasi API Play & Ambil Subtitle
        val playRes = app.post(
            url = "$mainUrl/api/play",
            headers = safeHeaders,
            data = "{\"claim\":\"$claimToken\"}" // Paksa kirim string JSON
        ).parsedSafe<NewMajorplayResponse>() ?: return

        // Injeksi semua subtitle langsung ke player
        playRes.subtitles?.forEach { sub ->
            val lang = sub.label ?: sub.lang ?: "Unknown"
            val subUrl = sub.path ?: return@forEach
            subtitleCallback.invoke(SubtitleFile(lang, subUrl))
        }

        val configUrl = playRes.url ?: return
        
        // LANGKAH 2: Tarik file M3U8 Master Playlist (yang disamarkan sebagai file JSON)
        val playlistContent = app.get(configUrl, headers = safeHeaders).text
        
        // LANGKAH 3: Parsing HLS secara manual
        val lines = playlistContent.split("\n")
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val finalUrl = if (trimmed.startsWith("http")) trimmed else {
                    // Karena URL di dalam m3u8 relatif (/v/z6/...), gabungkan dengan host utama
                    "$mainUrl$trimmed"
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Majorplay Premium",
                        url = "$finalUrl&.m3u8", // Trik bypass ekstensi
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = safeHeaders
                    }
                )
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorplayResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null
    )
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NewMajorSubtitle(
        @JsonProperty("lang") val lang: String? = null, 
        @JsonProperty("label") val label: String? = null, 
        @JsonProperty("path") val path: String? = null
    )
}
