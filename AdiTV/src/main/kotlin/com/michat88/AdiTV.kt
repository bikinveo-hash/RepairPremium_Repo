package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Membuat Kapsul Data agar Logo, Nama, dan Kategori tidak hilang
data class ChannelData(
    val name: String,
    val url: String,
    val logo: String,
    val group: String
)

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
                
                // BUNGKUS DATA KE DALAM JSON
                val channelDataJSON = ChannelData(currentName, trimmedLine, currentLogo, currentGroup).toJson()

                // Kirim JSON sebagai "URL" ke fungsi selanjutnya
                val channel = newLiveSearchResponse(currentName, channelDataJSON, TvType.Live) {
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
     * Langkah 2: Mengatur UI Halaman Pemutar (Memperbaiki "Plot Tidak Ditemukan")
     */
    override suspend fun load(url: String): LoadResponse {
        // BUKA BUNGKUSAN JSON
        val data = tryParseJson<ChannelData>(url)
        
        // Ambil data dari JSON, jika gagal (bukan JSON), gunakan URL mentahnya
        val streamUrl = data?.url ?: url
        val channelName = data?.name ?: "Live Stream"
        val channelLogo = data?.logo
        val channelGroup = data?.group ?: "Siaran Langsung"

        return newLiveStreamLoadResponse(channelName, url, streamUrl) {
            this.posterUrl = channelLogo // Memunculkan logo di player
            this.plot = "📺 Menyiarkan: $channelName\n📂 Kategori: $channelGroup\n\nSelamat menonton dari ekstensi AdiTV!" // Memperbaiki plot
        }
    }

    /**
     * Langkah 3: Mengirim video ke Player menggunakan M3u8Helper (Anti 32x18 & Anti 404)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // BUKA BUNGKUSAN JSON LAGI UNTUK MENDAPATKAN URL ASLI
        val streamUrl = tryParseJson<ChannelData>(data)?.url ?: data

        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )

        if (streamUrl.contains(".m3u8")) {
            // M3u8Helper akan membuang track 32x18/TrickPlay dan Meneruskan Headers!
            M3u8Helper.generateM3u8(
                source = this.name,
                streamUrl = streamUrl,
                referer = "",
                headers = customHeaders
            ).forEach(callback)
            
        } else {
            // Untuk link DASH (.mpd)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = streamUrl,
                    type = if (streamUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                ) {
                    this.headers = customHeaders
                }
            )
        }
        
        return true
    }
}
