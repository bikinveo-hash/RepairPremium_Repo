package com.IndoTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.net.URLEncoder

class IndoTV : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/michat88/Zaneta/master/Indonesia.m3u"
    override var name = "IndoTV"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        // Caching: Menyimpan data M3U di memori agar HP tidak ngelag / patah-patah saat scrolling
        var cachedChannels: List<M3UChannel>? = null
        var lastCacheTime: Long = 0

        // Pre-compile Regex agar proses baca kode jauh lebih ngebut!
        private val logoRegex = Regex("""(?:tvg-logo|group-logo)=["'](.*?)["']""")
        private val groupRegex = Regex("""group-title=["'](.*?)["']""")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Cek apakah data sudah ada di memori (berlaku selama 1 jam)
        val channels = if (cachedChannels != null && System.currentTimeMillis() - lastCacheTime < 3600000) {
            cachedChannels!!
        } else {
            val playlistRaw = app.get(mainUrl).text
            val parsed = parseM3U(playlistRaw)
            cachedChannels = parsed
            lastCacheTime = System.currentTimeMillis()
            parsed
        }
        
        // Mengelompokkan channel berdasarkan Group Title
        val groupedChannels = channels.groupBy { it.group }
        
        // Membentuk deretan horizontal per kategori
        val homePageLists = groupedChannels.map { (groupName, channelList) ->
            val shows = channelList.map { channel ->
                newLiveSearchResponse(channel.name, channel.toJson(), TvType.Live) {
                    // Logo sudah dilewatkan ke sistem Auto-Scale
                    this.posterUrl = fixLogo(channel.logo)
                }
            }
            // isHorizontalImages = true membuat daftar ini berjejer rapi ke samping (16:9)
            HomePageList(groupName, shows, isHorizontalImages = true)
        }

        return newHomePageResponse(homePageLists)
    }

    private fun fixLogo(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            // CDN Image Proxy (wsrv.nl)
            // w=400 & h=225 & fit=contain akan "memaksa" logo sekecil apapun 
            // menjadi pas di dalam rasio 16:9 tanpa terpotong sama sekali!
            // Sekaligus mem-bypass error HTTP 429 (Terlalu banyak request).
            val encoded = URLEncoder.encode(url, "UTF-8")
            "https://wsrv.nl/?url=$encoded&w=400&h=225&fit=contain"
        } catch (e: Exception) {
            url
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = AppUtils.tryParseJson<M3UChannel>(url) ?: throw ErrorLoadingException("Gagal parse data")

        return newLiveStreamLoadResponse(channel.name, url, url) {
            this.posterUrl = fixLogo(channel.logo)
            this.plot = "Kategori: ${channel.group}\nSistem: ${if(channel.drmType != null) "DRM Protected" else "Direct Stream"}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = AppUtils.tryParseJson<M3UChannel>(data) ?: return false

        // DETEKSI TIPE LINK OTOMATIS (Mencegah error M3U8/ParserException di Logcat untuk Google Drive)
        val linkType = when {
            channel.url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            channel.url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO // Untuk Google Drive atau MP4 biasa
        }

        // Pembacaan Kunci DRM yang tangguh
        if (channel.drmType?.contains("clearkey", ignoreCase = true) == true && !channel.drmKey.isNullOrEmpty()) {
            val keyParts = channel.drmKey!!.split(":")
            val kid = if (keyParts.size >= 2) keyParts[0] else ""
            val key = if (keyParts.size >= 2) keyParts[1] else channel.drmKey!!
            
            callback.invoke(
                newDrmExtractorLink(
                    source = name,
                    name = "${channel.name} (DRM)",
                    url = channel.url,
                    type = linkType,
                    uuid = CLEARKEY_UUID
                ) {
                    this.kid = kid
                    this.key = key
                    this.headers = channel.headers
                    this.referer = channel.headers["referer"] ?: ""
                }
            )
        } else {
            // Pemutaran Normal + Suntik Headers
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = channel.name,
                    url = channel.url,
                    type = linkType
                ) {
                    this.headers = channel.headers
                    this.referer = channel.headers["referer"] ?: ""
                }
            )
        }
        return true
    }

    // --- FUNGSI PARSER M3U ---
    private fun parseM3U(raw: String): List<M3UChannel> {
        val list = mutableListOf<M3UChannel>()
        val lines = raw.lines()
        var currentChannel: M3UChannel? = null

        for (line in lines) {
            val cleanLine = line.trim()
            when {
                cleanLine.startsWith("#EXTINF") -> {
                    val name = cleanLine.substringAfterLast(",").trim()
                    val logo = logoRegex.find(cleanLine)?.groupValues?.get(1)
                    val group = groupRegex.find(cleanLine)?.groupValues?.get(1) ?: "Lainnya"
                    
                    currentChannel = M3UChannel(name, "", logo, group)
                }
                cleanLine.startsWith("#EXTVLCOPT:http-referrer") -> {
                    val ref = cleanLine.substringAfter("=").trim()
                    currentChannel?.headers?.put("referer", ref)
                }
                cleanLine.startsWith("#EXTVLCOPT:http-user-agent") -> {
                    val ua = cleanLine.substringAfter("=").trim()
                    currentChannel?.headers?.put("user-agent", ua)
                }
                cleanLine.startsWith("#KODIPROP:inputstream.adaptive.license_type") -> {
                    currentChannel?.drmType = cleanLine.substringAfter("=").trim()
                }
                cleanLine.startsWith("#KODIPROP:inputstream.adaptive.license_key") -> {
                    currentChannel?.drmKey = cleanLine.substringAfter("=").trim()
                }
                cleanLine.startsWith("http") -> {
                    currentChannel?.let {
                        it.url = cleanLine
                        list.add(it)
                    }
                    currentChannel = null
                }
            }
        }
        return list
    }

    // Data class internal
    data class M3UChannel(
        val name: String,
        var url: String,
        val logo: String?,
        val group: String,
        val headers: MutableMap<String, String> = mutableMapOf(),
        var drmType: String? = null,
        var drmKey: String? = null
    )
}
