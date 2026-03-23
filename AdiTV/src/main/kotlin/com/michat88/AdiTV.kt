package com.michat88

import com.lagradost.cloudstream3.*
// Tambahan import yang kurang ada di bawah ini:
import com.lagradost.cloudstream3.utils.ExtractorLink 
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class AdiTV : MainAPI() {
    override var name = "AdiTV"
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    // Data class bantuan sementara
    data class ChannelData(
        val name: String,
        val logo: String?,
        val group: String,
        val streamUrl: String
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        try {
            // Ambil text dari github
            val m3uText = app.get(mainUrl).text
            
            val channels = mutableListOf<ChannelData>()
            val lines = m3uText.lines()
            
            var currentName = ""
            var currentLogo = ""
            var currentGroup = "Lainnya"

            // Parsing M3U manual
            for (line in lines) {
                val cleanLine = line.trim()
                if (cleanLine.startsWith("#EXTINF")) {
                    val groupMatch = Regex("""group-title="([^"]+)"""").find(cleanLine)
                    currentGroup = groupMatch?.groupValues?.get(1) ?: "Lainnya"

                    val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(cleanLine)
                    currentLogo = logoMatch?.groupValues?.get(1) ?: ""

                    currentName = cleanLine.substringAfterLast(",").trim()
                } else if (cleanLine.startsWith("http")) {
                    channels.add(ChannelData(currentName, currentLogo, currentGroup, cleanLine))
                }
            }

            // Kelompokkan berdasarkan grup
            val groupedChannels = channels.groupBy { it.group }

            // Gunakan amap untuk memproses list secara concurrent/async
            val homePageLists = groupedChannels.toList().amap { (groupName, channelList) ->
                
                val searchResponses = channelList.amap { ch ->
                    newLiveSearchResponse(
                        name = ch.name,
                        url = ch.streamUrl,
                        type = TvType.Live
                    ) {
                        this.posterUrl = ch.logo
                    }
                }
                
                HomePageList(name = groupName, list = searchResponses)
            }

            return newHomePageResponse(homePageLists)

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse(
            name = "Live Stream",
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Cek tipe ekstensi stream
        val linkType = when {
            data.contains(".mpd") -> ExtractorLinkType.DASH
            data.contains(".m3u") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        val extractor = newExtractorLink(
            source = this.name,
            name = this.name,
            url = data,
            type = linkType
        ) {
            this.quality = Qualities.Unknown.value
            this.referer = "" 
        }
        
        callback.invoke(extractor)
        
        return true
    }
}
