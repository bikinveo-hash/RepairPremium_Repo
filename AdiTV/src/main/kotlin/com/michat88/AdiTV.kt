package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AdiTVProvider : MainAPI() {
    // Nama plugin yang akan muncul di aplikasi
    override var name = "AdiTV"
    
    // URL mentah (raw) file M3U kamu di Github
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    /**
     * Langkah 1: Memuat dan mengelompokkan daftar channel
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val m3uText = app.get(mainUrl).text
        
        val channelsByGroup = mutableMapOf<String, MutableList<SearchResponse>>()
        
        var currentName = ""
        var currentLogo = ""
        var currentGroup = "Lainnya"

        val lines = m3uText.split("\n")
        for (line in lines) {
            val trimmedLine = line.trim()
            
            if (trimmedLine.startsWith("#EXTINF")) {
                val logoRegex = """tvg-logo="([^"]+)"""".toRegex()
                val groupRegex = """group-title="([^"]+)"""".toRegex()
                
                currentLogo = logoRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""
                
                val foundGroup = groupRegex.find(trimmedLine)?.groupValues?.get(1)
                if (!foundGroup.isNullOrBlank()) {
                    currentGroup = foundGroup
                }

                currentName = trimmedLine.substringAfterLast(",").trim()
            } 
            else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                val channel = newLiveSearchResponse(currentName, trimmedLine, TvType.Live) {
                    this.posterUrl = currentLogo
                }

                if (!channelsByGroup.containsKey(currentGroup)) {
                    channelsByGroup[currentGroup] = mutableListOf()
                }
                channelsByGroup[currentGroup]?.add(channel)

                currentName = ""
                currentLogo = ""
                currentGroup = "Lainnya" 
            }
        }

        val homeLists = channelsByGroup.map { (groupName, list) ->
            HomePageList(groupName, list)
        }

        return newHomePageResponse(homeLists)
    }

    /**
     * Langkah 2: Memuat detail stream saat diklik
     */
    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("Live Stream", url, url)
    }

    /**
     * Langkah 3: Memberikan link ke Player beserta Headers (User-Agent) penyamaran
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Menyiapkan identitas palsu (User-Agent) persis seperti skrip Termux kita
        // Ini berfungsi untuk mencegah server menolak koneksi (Error 404 / 2004)
        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        if (data.contains(".m3u8")) {
            // M3u8Helper dibekali dengan 'customHeaders' agar server mengira ini browser asli
            M3u8Helper.generateM3u8(
                source = this.name,
                streamUrl = data,
                referer = "",
                headers = customHeaders
            ).forEach(callback)
            
        } else {
            // Untuk link format lain (seperti DASH .mpd)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = data
                )
            )
        }
        
        return true
    }
}
