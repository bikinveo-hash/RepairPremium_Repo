package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.amap

data class IptvChannel(
    val name: String,
    val streamUrl: String,
    val logo: String? = null,
    val group: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val drmType: String? = null,
    val drmKid: String? = null,
    val drmKey: String? = null,
    val drmLicenseUrl: String? = null,
    val drmLicenseHeaders: Map<String, String>? = null, // Tambahan untuk memegang Header DRM
)

class AdiTVProvider : MainAPI() {

    override var name = "AdiTV"
    override var mainUrl = "https://raw.githubusercontent.com"
    override var lang = "id"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)
    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    private val playlistUrl = "https://raw.githubusercontent.com/michat88/iptv-playlist/main/playlist.m3u"
    
    // Cadangan jika M3U tidak memberikan header
    private val transvisionDtCustomData = "eyJ1c2VySWQiOiJyZWFjdC1qdy1wbGF5ZXIiLCJzZXNzaW9uSWQiOiIxMjM0NTY3ODkiLCJtZXJjaGFudCI6ImdpaXRkX3RyYW5zdmlzaW9uIn0="
    private val transvisionLicenseUrl = "https://cubmu.devhik.workers.dev/license_cenc"
    private val skippedGroups = setOf("VOD-Movie")

    private var cachedChannels: List<IptvChannel>? = null

    override val mainPage = mainPageOf(
        mainPage(url = "Event",                  name = "🔴 Event"),
        mainPage(url = "Channel Tv Indihome",    name = "📺 Indihome"),
        mainPage(url = "Channel Vision+",        name = "📺 Vision+"),
        mainPage(url = "Channel Indonesia",      name = "🇮🇩 Indonesia"),
        mainPage(url = "Channel Transvision",    name = "📡 Transvision"),
        mainPage(url = "HBO Group",              name = "🎬 HBO"),
        mainPage(url = "Sports",                 name = "⚽ Sports"),
        mainPage(url = "KIDS",                   name = "🧒 Kids"),
        mainPage(url = "Channel Music",          name = "🎵 Music"),
        mainPage(url = "Movies",                 name = "🎥 Movies"),
        mainPage(url = "KNOWLEDGE",              name = "🔬 Knowledge"),
        mainPage(url = "NEWS & ENTERTAINMENT",   name = "📰 News & Entertainment"),
        mainPage(url = "Channel Tv Singapore",   name = "🇸🇬 Singapore"),
        mainPage(url = "MALAYSIA",               name = "🇲🇾 Malaysia"),
        mainPage(url = "TVRI",                   name = "📡 TVRI"),
    )

    private suspend fun fetchChannels(): List<IptvChannel> {
        cachedChannels?.let { return it }
        return try {
            val text = app.get(playlistUrl).text
            val channels = parseM3u(text)
            cachedChannels = channels
            channels
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseM3u(m3uText: String): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        val lines = m3uText.lines()

        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingUserAgent: String? = null
        var pendingReferer: String? = null
        var pendingDrmType: String? = null
        var pendingDrmKid: String? = null
        var pendingDrmKey: String? = null
        var pendingDrmLicenseUrl: String? = null
        var pendingDrmLicenseHeaders: Map<String, String>? = null

        fun resetState() {
            pendingName = null
            pendingLogo = null
            pendingGroup = null
            pendingUserAgent = null
            pendingReferer = null
            pendingDrmType = null
            pendingDrmKid = null
            pendingDrmKey = null
            pendingDrmLicenseUrl = null
            pendingDrmLicenseHeaders = null
        }

        for (line in lines) {
            val trimmed = line.trim()

            when {
                trimmed.startsWith("#EXTINF") -> {
                    pendingLogo = Regex("""tvg-logo="([^"]*)"""").find(trimmed)?.groupValues?.get(1)?.takeIf { it.isNotBlank() && it != "_____" }
                    pendingGroup = Regex("""group-title="([^"]*)"""").find(trimmed)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                    pendingName = trimmed.substringAfterLast(",").trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    pendingUserAgent = trimmed.removePrefix("#EXTVLCOPT:http-user-agent=").trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    pendingReferer = trimmed.removePrefix("#EXTVLCOPT:http-referrer=").trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    val rawType = trimmed.removePrefix("#KODIPROP:inputstream.adaptive.license_type=").trim().lowercase()
                    pendingDrmType = when {
                        rawType.startsWith("clearkey") || rawType == "org.w3.clearkey" -> "clearkey"
                        rawType.startsWith("com.widevine") -> "widevine"
                        else -> pendingDrmType 
                    }
                }
                trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val keyValue = trimmed.removePrefix("#KODIPROP:inputstream.adaptive.license_key=").trim()

                    if (keyValue.startsWith("http")) {
                        // FIX: Memisahkan URL dengan Header bawaan Kodi (|)
                        val parts = keyValue.split("|")
                        pendingDrmLicenseUrl = parts[0]
                        if (parts.size > 1) {
                            val headersMap = mutableMapOf<String, String>()
                            parts[1].split("&").forEach { h ->
                                val kv = h.split("=", limit = 2)
                                if (kv.size == 2) headersMap[kv[0]] = kv[1]
                            }
                            pendingDrmLicenseHeaders = headersMap
                        }
                    } else if (keyValue.contains(":")) {
                        val parts = keyValue.split(":")
                        if (parts.size == 2) {
                            pendingDrmKid = parts[0].trim()
                            pendingDrmKey = parts[1].trim()
                        }
                    }
                }
                trimmed.startsWith("http") -> {
                    val group = pendingGroup ?: ""
                    if (group !in skippedGroups && pendingName != null) {
                        channels.add(
                            IptvChannel(
                                name = pendingName!!, streamUrl = trimmed, logo = pendingLogo, group = group.takeIf { it.isNotBlank() },
                                userAgent = pendingUserAgent, referer = pendingReferer, drmType = pendingDrmType,
                                drmKid = pendingDrmKid, drmKey = pendingDrmKey, drmLicenseUrl = pendingDrmLicenseUrl,
                                drmLicenseHeaders = pendingDrmLicenseHeaders
                            )
                        )
                    }
                    resetState()
                }
            }
        }
        return channels
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val allChannels = fetchChannels()
        if (allChannels.isEmpty()) return null

        val groupFilter = request.data
        val filtered = allChannels.filter { it.group?.equals(groupFilter, ignoreCase = true) == true }
        
        val pageSize = 50
        val fromIndex = (page - 1) * pageSize
        val toIndex = minOf(fromIndex + pageSize, filtered.size)

        if (fromIndex >= filtered.size) return newHomePageResponse(request, emptyList(), false)

        val pageItems = filtered.subList(fromIndex, toIndex)
        val searchResults = pageItems.amap { channel ->
            newLiveSearchResponse(name = channel.name, url = channel.streamUrl, type = TvType.Live) { posterUrl = channel.logo }
        }
        return newHomePageResponse(request, searchResults, toIndex < filtered.size)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val allChannels = fetchChannels()
        if (allChannels.isEmpty()) return null

        val q = query.trim().lowercase()
        val matched = allChannels.filter { it.name.lowercase().contains(q) || it.group?.lowercase()?.contains(q) == true }

        if (matched.isEmpty()) return emptyList()
        return matched.amap { channel ->
            newLiveSearchResponse(name = channel.name, url = channel.streamUrl, type = TvType.Live) { posterUrl = channel.logo }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val allChannels = fetchChannels()
        val channel = allChannels.firstOrNull { it.streamUrl == url } ?: IptvChannel(name = url.substringAfterLast("/").substringBefore("?").ifBlank { "Channel" }, streamUrl = url)

        return newLiveStreamLoadResponse(name = channel.name, url = url, dataUrl = url) {
            posterUrl = channel.logo
            plot = buildString {
                channel.group?.let { append("Grup: $it\n") }
                channel.drmType?.let { append("DRM: ${it.uppercase()}\n") }
            }.trim().takeIf { it.isNotBlank() }
            tags = listOfNotNull(channel.group)
        }
    }

    // --- ALAT TRANSLATOR KODI HEX KE CLOUDSTREAM BASE64 ---
    private fun String.hexToBase64(): String {
        return try {
            if (this.length % 2 == 0 && this.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                val bytes = this.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } else this
        } catch (e: Exception) { this }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false

        val streamUrl = data.trim()
        val allChannels = fetchChannels()
        val channel = allChannels.firstOrNull { it.streamUrl == streamUrl }

        val linkType = when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            streamUrl.contains(".mpd",  ignoreCase = true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.M3U8
        }

        val headers = buildMap<String, String> {
            put("User-Agent", channel?.userAgent ?: USER_AGENT)
            channel?.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }

        val referer = channel?.referer ?: ""

        return when (channel?.drmType?.lowercase()) {
            "clearkey", "org.w3.clearkey" -> {
                // FIX: Konversi HEX ke Base64 agar layar tidak Blank
                val kidBase64 = channel.drmKid?.hexToBase64()
                val keyBase64 = channel.drmKey?.hexToBase64()

                if (kidBase64 != null && keyBase64 != null) {
                    val link = newDrmExtractorLink(
                        source = this.name, name = channel.name, url = streamUrl, uuid = CLEARKEY_UUID, type = linkType,
                    ) {
                        this.referer = referer; this.quality = Qualities.Unknown.value; this.headers = headers
                        this.kid = kidBase64; this.key = keyBase64; this.kty = "oct"
                    }
                    callback(link)
                    true
                } else {
                    val link = newExtractorLink(source = this.name, name = channel.name, url = streamUrl, type = linkType) {
                        this.referer = referer; this.quality = Qualities.Unknown.value; this.headers = headers
                    }
                    callback(link)
                    true
                }
            }
            "com.widevine.alpha", "widevine" -> {
                val licenseUrl = channel.drmLicenseUrl ?: transvisionLicenseUrl
                val licenseHeaders = channel.drmLicenseHeaders ?: emptyMap()

                val link = newDrmExtractorLink(
                    source = this.name, name = channel.name, url = streamUrl, uuid = WIDEVINE_UUID, type = linkType,
                ) {
                    this.referer = referer; this.quality = Qualities.Unknown.value; this.headers = headers; this.licenseUrl = licenseUrl
                    
                    // FIX: Gabungkan Header Kodi (Pipa |) ke dalam keyRequestParameters
                    this.keyRequestParameters = hashMapOf<String, String>().apply {
                        putAll(licenseHeaders)
                        if (!containsKey("dt-custom-data")) put("dt-custom-data", transvisionDtCustomData)
                    }
                }
                callback(link)
                true
            }
            else -> {
                val channelName = channel?.name ?: this.name
                val link = newExtractorLink(source = this.name, name = channelName, url = streamUrl, type = linkType) {
                    this.referer = referer; this.quality = Qualities.Unknown.value; this.headers = headers
                }
                callback(link)
                true
            }
        }
    }
}
