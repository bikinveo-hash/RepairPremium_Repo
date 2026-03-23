package com.lagradost.cloudstream3.live

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudstreamHttp
import java.io.InputStream

// Data class tetap sama
data class AdiChannel(
    val name: String,
    val streamUrl: String,
    val logo: String? = null,
    val group: String? = null,
    val headers: Map<String, String>? = null
)

class AdiTVProvider : MainAPI() {

    // Konfigurasi dasar tetap sama
    override var name               = "AdiTV"
    override var mainUrl            = "https://raw.githubusercontent.com/amanhnb88"
    override var lang               = "id"
    override val hasMainPage        = true
    override val hasQuickSearch     = false
    override val instantLinkLoading = true
    override val supportedTypes     = setOf(TvType.Live)

    // Pengaturan Cache dan URL tetap sama
    private var cachedChannels: List<AdiChannel>? = null
    private val m3uUrls = listOf(
        "$mainUrl/iptv/main/id.m3u",
        "https://raw.githubusercontent.com/orion-iptv/playlist/main/playlist_super.m3u"
    )

    // mainPageOf tetap sama
    override val mainPage = mainPageOf(
        "TV Nasional" to "TV Nasional",
        "Channel Indonesia" to "Channel Indonesia",
        "Entertainment" to "Entertainment",
        "Movies" to "Movies",
        "Sports" to "Sports",
        "Kids" to "Kids",
        "News" to "News"
    )

    // fetchChannels dan parseM3u tetap sama
    private suspend fun fetchChannels(): List<AdiChannel> {
        cachedChannels?.let { return it }
        val allChannels = mutableListOf<AdiChannel>()
        m3uUrls.forEach { url ->
            try {
                val res = CloudstreamHttp.makeAsyncGetRequest(url)
                res.inputStream?.use { input -> allChannels.addAll(parseM3u(input)) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return allChannels.distinctBy { it.streamUrl }.also { cachedChannels = it }
    }

    private fun parseM3u(input: InputStream): List<AdiChannel> {
        val channels = mutableListOf<AdiChannel>()
        var currentName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentHeader: Map<String, String>? = null

        val logoRegex   = """tvg-logo="([^"]+)"""".toRegex()
        val groupRegex  = """group-title="([^"]+)"""".toRegex()
        val headerRegex = """#EXTVLCOPT:(http-[^=]+)=(.*)""".toRegex()
        // Regex cleanup untuk nama
        val cleanNameRegex = """\s*(\((?:\d{3,4}p|HD|SD|FHD)\))\s*$""".toRegex(RegexOption.IGNORE_CASE)

        input.bufferedReader().forEachLine { line ->
            when {
                line.startsWith("#EXTINF:") -> {
                    currentLogo = logoRegex.find(line)?.groupValues?.get(1)
                    currentGroup = groupRegex.find(line)?.groupValues?.get(1)
                    val rawName = line.substringAfterLast(",").trim()
                    // Hapus resolusi/HD dari nama
                    currentName = if (rawName.isNotBlank()) rawName.replace(cleanNameRegex, "") else null
                    currentHeader = null
                }
                line.startsWith("#EXTVLCOPT:") -> {
                    val match = headerRegex.find(line)
                    if (match != null) {
                        val key = match.groupValues[1] // http-user-agent / http-referer
                        val value = match.groupValues[2]
                        
                        val realKey = when(key.lowercase()) {
                            "http-user-agent" -> "User-Agent"
                            "http-referer" -> "Referer"
                            else -> null
                        }
                        
                        if (realKey != null) {
                            val map = (currentHeader ?: emptyMap()).toMutableMap()
                            map[realKey] = value
                            currentHeader = map
                        }
                    }
                }
                line.startsWith("http") -> {
                    val name = currentName
                    if (name != null) {
                        channels.add(AdiChannel(
                            name = name,
                            streamUrl = line.trim(),
                            logo = currentLogo,
                            group = currentGroup,
                            headers = currentHeader
                        ))
                    }
                    currentName = null
                    currentLogo = null
                    currentGroup = null
                    currentHeader = null
                }
            }
        }
        return channels
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getMainPage - PERUBAHAN HANYA DI SINI
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val all = fetchChannels()

        val filtered = all.filter { ch ->
            ch.group?.equals(request.data, ignoreCase = true) == true
        }

        val pageSize = 50
        val from     = (page - 1) * pageSize
        val to       = minOf(from + pageSize, filtered.size)

        if (filtered.isEmpty() || from >= filtered.size)
            return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val items = filtered.subList(from, to).amap { ch ->
            // Menggunakan builder standar newLiveSearchResponse
            newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                this.posterUrl = ch.logo
                // PERUBAHAN DI SINI: Menyisipkan header untuk memaksa tampilan Lanskap
                this.posterHeaders = mapOf("AspectRatio" to "landscape")
            }
        }

        return newHomePageResponse(request.name, items, hasNext = to < filtered.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // search - PERUBAHAN HANYA DI SINI
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse>? {
        val all = fetchChannels()
        if (all.isEmpty()) return null
        val q = query.trim().lowercase()
        return all
            .filter {
                it.name.lowercase().contains(q) ||
                it.group?.lowercase()?.contains(q) == true
            }
            .amap { ch ->
                // Menggunakan builder standar newLiveSearchResponse
                newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    this.posterUrl = ch.logo
                    // PERUBAHAN DI SINI: Menyisipkan header untuk memaksa tampilan Lanskap
                    this.posterHeaders = mapOf("AspectRatio" to "landscape")
                }
            }
    }

    // load dan loadLinks tetap sama
    override suspend fun load(url: String): LoadResponse? {
        val channels = fetchChannels()
        val channel = channels.find { it.streamUrl == url } ?: return null
        
        // Buat metadata headers untuk dikirim ke player
        val headersData = (channel.headers ?: emptyMap()).toMutableMap()
        // Pastikan User-Agent default ada jika tidak ditentukan di M3U
        if (!headersData.containsKey("User-Agent")) {
            headersData["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        return newLiveStreamLoadResponse(
            name = channel.name,
            url = channel.streamUrl,
            dataUrl = headersData.toJson(), // Simpan headers sebagai JSON di field dataUrl
            type = TvType.Live
        ) {
            this.posterUrl = channel.logo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data yang dikirim dari load() adalah JSON Headers
        val headers: Map<String, String> = data.fromJson()
        val referer = headers["Referer"]
        
        val url = headers["AdiTV-Url"] ?: return false // Url asli sayangnya hilang, perlu diakali

        // Deteksi tipe link sederhana
        val type = when {
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        // Kita gunakan nama provider kita
        callback(ExtractorLink(
            name = this.name,
            source = this.name,
            url = url,
            referer = referer ?: "",
            quality = Qualities.Unknown.value,
            type = type,
            headers = headers
        ))
        
        return true
    }
}
