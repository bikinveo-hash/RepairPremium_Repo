package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AdiTVProvider : MainAPI() {
    // Penamaan dan pengaturan dasar sesuai MainAPI.kt
    override var name = "AdiTV"
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    /**
     * Langkah 1: Memuat dan mengelompokkan daftar channel
     * Menggunakan MainPageRequest dan newHomePageResponse sesuai MainAPI.kt
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Menggunakan variabel 'app' bawaan Cloudstream untuk HTTP request
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
                // Menggunakan builder Kotlin DSL: newLiveSearchResponse
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

        // Wajib menggunakan builder newHomePageResponse
        return newHomePageResponse(homeLists)
    }

    /**
     * Langkah 2: Memuat detail stream saat diklik
     * Menggunakan newLiveStreamLoadResponse sesuai MainAPI.kt
     */
    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("Live Stream", url, url) {}
    }

    /**
     * Langkah 3: Mengirim video ke Player menggunakan newExtractorLink
     * Menerapkan ExtractorLinkType sesuai ExtractorApi.kt
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // Identitas super untuk menembus blokir server (Error 404/2004)
        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        // Mendeteksi tipe streaming berdasarkan URL
        val streamType = when {
            data.contains(".m3u8") -> ExtractorLinkType.M3U8
            data.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        // Menggunakan gaya penulisan DSL newExtractorLink yang diwajibkan ExtractorApi.kt
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                type = streamType // Menentukan tipe link di sini (M3U8/DASH/VIDEO)
            ) {
                // Modifikasi parameter ekstra di dalam blok DSL
                this.headers = customHeaders
                this.quality = Qualities.Unknown.value
                this.referer = ""
            }
        )
        
        return true
    }
}
