package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Membungkam protes compiler agar kita bisa menggunakan ExtractorLink dengan bebas
@Suppress("DEPRECATION")
class AdiTVProvider : MainAPI() {
    override var name = "AdiTV"
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
     * Langkah 2: Memuat detail stream
     */
    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("Live Stream", url, url)
    }

    /**
     * Langkah 3: Mengirim video ke Player secara NATIVE (Khusus Live TV)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Identitas super yang berhasil tembus 200 di Termux
        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        // Melewati M3u8Helper, berikan langsung ke ExoPlayer agar tidak macet/error 404!
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                referer = "",
                quality = Qualities.Unknown.value,
                isM3u8 = data.contains(".m3u8"), // Otomatis aktif jika link berakhiran m3u8
                headers = customHeaders
            )
        )
        
        return true
    }
}
