package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.amap

// ─────────────────────────────────────────────────────────────────────────────
// Data class — satu channel hasil parse M3U
// ─────────────────────────────────────────────────────────────────────────────
data class AdiChannel(
    val name: String,
    val streamUrl: String,
    val logo: String?      = null,
    val group: String?     = null,
    val userAgent: String? = null,
    val referer: String?   = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Provider utama
// ─────────────────────────────────────────────────────────────────────────────
class AdiTVProvider : MainAPI() {

    override var name               = "AdiTV"
    override var mainUrl            = "https://raw.githubusercontent.com/amanhnb88"
    override var lang               = "id"
    override val hasMainPage        = true
    override val hasQuickSearch     = false
    override val instantLinkLoading = true
    override val supportedTypes     = setOf(TvType.Live)

    // ── Dua sumber playlist (sama persis seperti App__1_.js) ─────────────────
    private val URL_ID    = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/id.m3u"
    private val URL_SUPER = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_super.m3u"

    // ── Cache channel ─────────────────────────────────────────────────────────
    private var cachedChannels: List<AdiChannel>? = null

    // ─────────────────────────────────────────────────────────────────────────
    // mainPage — kategori yang tampil di beranda
    // data = nama group-title yang difilter dari playlist
    // ─────────────────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "TV Nasional"          to "📺 TV Nasional",
        "Channel Indonesia"    to "🇮🇩 Channel Indonesia",
        "Channel Tv Indihome"  to "📡 Indihome",
        "Channel Vision+"      to "📺 Vision+",
        "Channel Transvision"  to "📡 Transvision",
        "HBO Group"            to "🎬 HBO",
        "Sports"               to "⚽ Sports",
        "KIDS"                 to "🧒 Kids",
        "Channel Music"        to "🎵 Music",
        "Movies"               to "🎥 Movies",
        "KNOWLEDGE"            to "🔬 Knowledge",
        "NEWS & ENTERTAINMENT" to "📰 News",
        "Channel Tv Singapore" to "🇸🇬 Singapore",
        "MALAYSIA"             to "🇲🇾 Malaysia",
        "TVRI"                 to "📡 TVRI",
        "Event"                to "🔴 Event",
        "Sports"               to "⚽ Sports",
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Fetch gabungan kedua playlist — persis logika App__1_.js fetchChannels()
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun fetchChannels(): List<AdiChannel> {
        cachedChannels?.let { return it }

        var combined = ""

        // Fetch id.m3u (RCTI, MNCTV, GTV, iNews — di-generate script Python)
        try {
            val res = app.get(URL_ID, headers = mapOf("Cache-Control" to "no-cache")).text
            if (res.isNotBlank()) combined += res + "\n"
        } catch (_: Exception) {}

        // Fetch playlist_super.m3u (semua channel lainnya)
        try {
            val res = app.get(URL_SUPER, headers = mapOf("Cache-Control" to "no-cache")).text
            if (res.isNotBlank()) combined += res + "\n"
        } catch (_: Exception) {}

        if (combined.isBlank()) return emptyList()

        return parseM3u(combined).also { cachedChannels = it }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parser M3U — mengikuti logika parseM3U() di App__1_.js
    // ─────────────────────────────────────────────────────────────────────────
    private fun parseM3u(text: String): List<AdiChannel> {
        val channels  = mutableListOf<AdiChannel>()
        val lines     = text.lines()

        var pName:  String? = null
        var pLogo:  String? = null
        var pGroup: String? = null
        var pUA:    String? = null
        var pRef:   String? = null

        fun reset() {
            pName = null; pLogo = null; pGroup = null; pUA = null; pRef = null
        }

        for (raw in lines) {
            val line = raw.trim()
            when {
                // ── #EXTINF: ambil metadata (sama seperti App__1_.js parseM3U) ──
                line.startsWith("#EXTINF") -> {
                    pLogo  = Regex("""tvg-logo="([^"]*)"""")
                        .find(line)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() }

                    pGroup = Regex("""group-title="([^"]*)"""")
                        .find(line)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() }
                        ?.split(";")?.get(0)   // App.js: split(';')[0]

                    // Nama channel = teks setelah koma terakhir
                    var rawName = line.substringAfterLast(",").trim()
                    // App.js: bersihkan "(720p)", "(1080p)", "[...]"
                    rawName = rawName.replace(Regex("""\s*\(\d+[piPI]\)"""), "")
                    rawName = rawName.replace(Regex("""\s*\[.*?]"""), "")
                    pName   = rawName.trim().takeIf { it.isNotBlank() }
                }

                // ── #EXTVLCOPT: header referer & user-agent ──────────────────
                line.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    pRef = line.removePrefix("#EXTVLCOPT:http-referrer=").trim()
                        .takeIf { it.isNotBlank() }
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    pUA = line.removePrefix("#EXTVLCOPT:http-user-agent=").trim()
                        .takeIf { it.isNotBlank() }
                }

                // ── URL stream ────────────────────────────────────────────────
                line.startsWith("http") -> {
                    if (pName != null) {
                        channels.add(
                            AdiChannel(
                                name      = pName!!,
                                streamUrl = line,
                                logo      = pLogo,
                                group     = pGroup?.takeIf { it.isNotBlank() } ?: "Lain-lain",
                                userAgent = pUA,
                                referer   = pRef,
                            )
                        )
                        reset()
                    }
                    // URL kedua (fallback) diabaikan — reset sudah membuat pName=null
                }

                // ── Baris lain (komentar, KODIPROP, separator) — skip ─────────
                else -> { /* no-op */ }
            }
        }
        return channels
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getMainPage
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
            newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                posterUrl = ch.logo
            }
        }

        // PERUBAHAN HANYA DI BLOK INI: Menggunakan HomePageList persis seperti KisskhProvider
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = to < filtered.size
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // search
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
                newLiveSearchResponse(ch.name, ch.streamUrl, TvType.Live) {
                    posterUrl = ch.logo
                }
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // load
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val ch = fetchChannels().firstOrNull { it.streamUrl == url }
            ?: AdiChannel(
                name      = url.substringAfterLast("/").substringBefore("?").ifBlank { "Channel" },
                streamUrl = url,
            )
        return newLiveStreamLoadResponse(ch.name, url, url) {
            posterUrl = ch.logo
            plot      = ch.group?.let { "Kategori: $it" }
            tags      = listOfNotNull(ch.group)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // loadLinks — kirim link ke player
    // Referensi CS3IPlayer.kt:
    //   • DASH (.mpd) → ExoPlayer DashMediaSource
    //   • HLS (.m3u8) → DefaultMediaSourceFactory (AES-128 auto)
    //   • header dikirim via createVideoSource → setDefaultRequestProperties()
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false

        val url = data.trim()

        // Lookup channel — exact → trimEnd → case-insensitive
        val allChannels = fetchChannels()
        val ch = allChannels.firstOrNull { it.streamUrl == url }
            ?: allChannels.firstOrNull { it.streamUrl.trimEnd() == url.trimEnd() }

        // Deteksi tipe link dari clean URL (tanpa query string)
        val cleanUrl = url.split("?")[0].split("|")[0]
        val linkType = when {
            cleanUrl.contains(".mpd",  ignoreCase = true) -> ExtractorLinkType.DASH
            cleanUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            url.contains(".mpd",       ignoreCase = true) -> ExtractorLinkType.DASH
            url.contains(".m3u8",      ignoreCase = true) -> ExtractorLinkType.M3U8
            else                                          -> ExtractorLinkType.M3U8
        }

        val referer   = ch?.referer?.takeIf   { it.isNotBlank() } ?: ""
        val userAgent = ch?.userAgent?.takeIf { it.isNotBlank() } ?: USER_AGENT

        // Header lengkap: User-Agent + Referer + Origin
        val headers = buildMap<String, String> {
            put("User-Agent", userAgent)
            if (referer.isNotBlank()) {
                put("Referer", referer)
                put("Origin",  referer.trimEnd('/'))
            }
        }

        callback(
            newExtractorLink(
                source = name,
                name   = ch?.name ?: name,
                url    = url,
                type   = linkType,
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
        return true
    }
}
