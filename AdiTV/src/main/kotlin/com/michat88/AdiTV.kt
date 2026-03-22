package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AdiTV : MainAPI() {
    override var name = "AdiTV" 
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u" 
    
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // Langkah 1: Mengambil data dan memparsing seperti di React Native
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val m3uData = app.get(mainUrl).text
        val lines = m3uData.lines()
        
        val groupedChannels = mutableMapOf<String, MutableList<SearchResponse>>()
        
        var currentName = "Channel Tanpa Nama"
        var currentLogo = ""
        var currentGroup = "Lain-lain"
        val currentHeaders = mutableMapOf<String, String>()
        
        for (line in lines) {
            val trimmedLine = line.trim()
            
            if (trimmedLine.startsWith("#EXTINF")) {
                currentName = trimmedLine.substringAfterLast(",").trim()
                
                val logoRegex = """tvg-logo="(.*?)"""".toRegex()
                currentLogo = logoRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""
                
                val groupRegex = """group-title="(.*?)"""".toRegex()
                currentGroup = groupRegex.find(trimmedLine)?.groupValues?.get(1) ?: "Lain-lain"
                
                // Reset headers untuk channel baru
                currentHeaders.clear() 
                
            } else if (trimmedLine.startsWith("#EXTVLCOPT:")) {
                // Meniru logika ekstraksi headers VLC dari React Native-mu
                if (trimmedLine.contains("http-referrer=")) {
                    val referer = trimmedLine.substringAfter("http-referrer=")
                    currentHeaders["Referer"] = referer
                    currentHeaders["Origin"] = referer
                }
                if (trimmedLine.contains("http-user-agent=")) {
                    val ua = trimmedLine.substringAfter("http-user-agent=")
                    currentHeaders["User-Agent"] = ua
                }
                
            } else if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                var cleanUrl = trimmedLine

                // 1. Membersihkan link dari format MX Player (|User-Agent=...)
                if (cleanUrl.contains("|")) {
                    val parts = cleanUrl.split("|")
                    cleanUrl = parts[0]
                    
                    // Menangkap header dari MX Player format
                    val headerPart = parts.getOrNull(1) ?: ""
                    headerPart.split("&").forEach { pair ->
                        val kv = pair.split("=")
                        if (kv.size == 2) {
                            currentHeaders[kv[0]] = kv[1]
                        }
                    }
                }

                // 2. Memaksa HTTPS seperti logika di kodemu
                if (cleanUrl.startsWith("http://")) {
                    cleanUrl = cleanUrl.replace("http://", "https://")
                }

                // Kita bungkus URL dan Headers jadi satu string untuk dikirim ke LoadLinks
                val finalUrlToPass = if (currentHeaders.isNotEmpty()) {
                    cleanUrl + "|" + currentHeaders.map { "${it.key}=${it.value}" }.joinToString("&")
                } else {
                    cleanUrl
                }

                if (!groupedChannels.containsKey(currentGroup)) {
                    groupedChannels[currentGroup] = mutableListOf()
                }
                
                groupedChannels[currentGroup]?.add(
                    newLiveSearchResponse(
                        name = currentName,
                        url = finalUrlToPass,
                        type = TvType.Live
                    ) {
                        this.posterUrl = currentLogo
                        this.lang = "id"
                    }
                )
            }
        }

        val homePageLists = groupedChannels.map { (groupName, channels) ->
            HomePageList(groupName, channels)
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse(
            name = "Live TV",
            url = url,
            dataUrl = url
        ) {}
    }

    // Langkah 3: Eksekusi link dan header di pemutar video
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        var cleanUrl = data
        val mapHeaders = mutableMapOf<String, String>()
        
        // Membongkar kembali URL dan Headers yang kita gabungkan tadi
        if (cleanUrl.contains("|")) {
            val parts = cleanUrl.split("|")
            cleanUrl = parts[0]
            val headerPart = parts.getOrNull(1) ?: ""
            headerPart.split("&").forEach {
                val kv = it.split("=")
                if (kv.size == 2) {
                    mapHeaders[kv[0]] = kv[1]
                }
            }
        }

        // Default fallback User-Agent (sama persis dengan yang kau pakai di tester)
        if (!mapHeaders.containsKey("User-Agent")) {
            mapHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
        }

        val linkType = when {
            cleanUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            cleanUrl.contains(".m3u", ignoreCase = true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = cleanUrl,
                type = linkType
            ) {
                this.quality = Qualities.Unknown.value
                this.headers = mapHeaders // Memasukkan headers ke Cloudstream!
            }
        )
        return true
    }
}
