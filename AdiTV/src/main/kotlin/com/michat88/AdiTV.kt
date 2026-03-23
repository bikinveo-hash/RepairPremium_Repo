package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.amap

/**
 * Data class untuk menyimpan informasi satu channel dari M3U playlist.
 */
data class IptvChannel(
    val name: String,
    val streamUrl: String,
    val logo: String?          = null,
    val group: String?         = null,
    val userAgent: String?     = null,
    val referer: String?       = null,
    val drmType: String?       = null,
    val drmKid: String?        = null,
    val drmKey: String?        = null,
    val drmLicenseUrl: String? = null,
)

class AdiTVProvider : MainAPI() {

    // -----------------------------------------------------------------------
    // Identitas plugin
    // mainUrl WAJIB unik dan BUKAN URL sumber playlist — ini hanya identifier
    // -----------------------------------------------------------------------
    override var name               = "AdiTV"
    override var mainUrl            = "https://adikasepuh.github.io"
    override var lang               = "id"
    override val hasMainPage        = true
    override val hasQuickSearch     = false
    override val instantLinkLoading = true
    override val supportedTypes     = setOf(TvType.Live)

    // -----------------------------------------------------------------------
    // URL playlist di GitHub — sesuaikan dengan path repo kamu
    // -----------------------------------------------------------------------
    private val playlistUrl =
        "https://raw.githubusercontent.com/amanhnb88/Premium_Repo/main/playlist.m3u"

    private val transvisionDtCustomData =
        "eyJ1c2VySWQiOiJyZWFjdC1qdy1wbGF5ZXIiLCJzZXNzaW9uSWQiOiIxMjM0NTY3ODkiLCJtZXJjaGFudCI6ImdpaXRkX3RyYW5zdmlzaW9uIn0="

    private val transvisionLicenseUrl =
        "https://cubmu.devhik.workers.dev/license_cenc"

    private val skippedGroups = setOf("VOD-Movie")

    // Cache agar tidak re-fetch setiap scroll
    private var cachedChannels: List<IptvChannel>? = null

    // -----------------------------------------------------------------------
    // mainPage — format Pair<String, String> sesuai standar MainAPI.kt
    // key (kiri) = nilai request.data yang dikirim ke getMainPage
    // value (kanan) = nama kategori yang tampil di UI
    // -----------------------------------------------------------------------
    override val mainPage = mainPageOf(
        "Event"                to "🔴 Event",
        "Channel Tv Indihome"  to "📺 Indihome",
        "Channel Vision+"      to "📺 Vision+",
        "Channel Indonesia"    to "🇮🇩 Indonesia",
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
    )

    // -----------------------------------------------------------------------
    // Fetch playlist dari GitHub dan cache hasilnya
    // -----------------------------------------------------------------------
    private suspend fun fetchChannels(): List<IptvChannel> {
        cachedChannels?.let { return it }
        return try {
            val text = app.get(playlistUrl).text
            parseM3u(text).also { cachedChannels = it }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -----------------------------------------------------------------------
    // Parser M3U — membaca semua tag EXTINF, EXTVLCOPT, KODIPROP
    // -----------------------------------------------------------------------
    private fun parseM3u(m3uText: String): List<IptvChannel> {
        val channels  = mutableListOf<IptvChannel>()
        val lines     = m3uText.lines()

        var pName:    String? = null
        var pLogo:    String? = null
        var pGroup:   String? = null
        var pUA:      String? = null
        var pRef:     String? = null
        var pDrmType: String? = null
        var pDrmKid:  String? = null
        var pDrmKey:  String? = null
        var pDrmLic:  String? = null

        fun reset() {
            pName = null; pLogo = null; pGroup = null
            pUA = null; pRef = null
            pDrmType = null; pDrmKid = null; pDrmKey = null; pDrmLic = null
        }

        for (raw in lines) {
            val line = raw.trim()
            when {
                // --- Metadata utama channel ---
                line.startsWith("#EXTINF") -> {
                    pLogo = Regex("""tvg-logo="([^"]*)"""")
                        .find(line)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() && it != "_____" }

                    pGroup = Regex("""group-title="([^"]*)"""")
                        .find(line)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() }

                    pName = line.substringAfterLast(",").trim()
                        .takeIf { it.isNotBlank() }
                }

                // --- Custom User-Agent ---
                line.startsWith("#EXTVLCOPT:http-user-agent=") ->
                    pUA = line.removePrefix("#EXTVLCOPT:http-user-agent=").trim()
                        .takeIf { it.isNotBlank() }

                // --- Custom Referer ---
                line.startsWith("#EXTVLCOPT:http-referrer=") ->
                    pRef = line.removePrefix("#EXTVLCOPT:http-referrer=").trim()
                        .takeIf { it.isNotBlank() }

                // --- Tipe DRM ---
                line.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    val t = line
                        .removePrefix("#KODIPROP:inputstream.adaptive.license_type=")
                        .trim().lowercase()
                    pDrmType = when {
                        t == "clearkey" || t == "org.w3.clearkey" -> "clearkey"
                        t.startsWith("com.widevine")              -> "widevine"
                        else                                       -> pDrmType
                    }
                }

                // --- DRM Key: ClearKey KID:KEY atau Widevine license URL ---
                line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val kv = line
                        .removePrefix("#KODIPROP:inputstream.adaptive.license_key=")
                        .trim()
                    when {
                        // Widevine: license URL
                        kv.startsWith("http") -> pDrmLic = kv

                        // ClearKey: format KID:KEY — keduanya hex 32 karakter
                        kv.contains(":") -> {
                            // limit=2 agar tidak salah split jika ada karakter ':' lain
                            val p = kv.split(":", limit = 2)
                            if (p.size == 2) {
                                val kid = p[0].trim()
                                val key = p[1].trim()
                                // Validasi: keduanya harus hex string (hanya 0-9, a-f, A-F)
                                val hexRegex = Regex("^[0-9a-fA-F]+$")
                                if (kid.matches(hexRegex) && key.matches(hexRegex)) {
                                    pDrmKid = kid
                                    pDrmKey = key
                                }
                            }
                        }
                    }
                }

                // --- URL stream ditemukan: simpan channel lalu reset ---
                // Hanya proses URL pertama per blok (pName null = sudah di-reset = skip)
                line.startsWith("http") -> {
                    val grp = pGroup ?: ""
                    if (grp !in skippedGroups && pName != null) {
                        channels.add(
                            IptvChannel(
                                name          = pName!!,
                                streamUrl     = line,
                                logo          = pLogo,
                                group         = grp.takeIf { it.isNotBlank() },
                                userAgent     = pUA,
                                referer       = pRef,
                                drmType       = pDrmType,
                                drmKid        = pDrmKid,
                                drmKey        = pDrmKey,
                                drmLicenseUrl = pDrmLic,
                            )
                        )
                        // Reset setelah URL pertama — URL fallback berikutnya diabaikan
                        reset()
                    }
                    // Jika pName sudah null: ini URL fallback, skip saja
                }

                // --- Baris komentar/separator/kosong: abaikan ---
                else -> { /* no-op */ }
            }
        }
        return channels
    }

    // -----------------------------------------------------------------------
    // getMainPage — tampilkan channel per kategori
    // -----------------------------------------------------------------------
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

        return newHomePageResponse(request.name, items, hasNext = to < filtered.size)
    }

    // -----------------------------------------------------------------------
    // search
    // -----------------------------------------------------------------------
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

    // -----------------------------------------------------------------------
    // load — halaman detail channel
    // -----------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val ch = fetchChannels().firstOrNull { it.streamUrl == url }
            ?: IptvChannel(
                name      = url.substringAfterLast("/")
                              .substringBefore("?")
                              .ifBlank { "Channel" },
                streamUrl = url,
            )
        return newLiveStreamLoadResponse(ch.name, url, url) {
            posterUrl = ch.logo
            plot      = ch.group?.let { "Grup: $it" }
            tags      = listOfNotNull(ch.group)
        }
    }

    // -----------------------------------------------------------------------
    // loadLinks — kirim link ke player dengan DRM handling
    // -----------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false

        val url = data.trim()

        // Lookup robust: coba exact match dulu, fallback ke contains
        val allChannels = fetchChannels()
        val ch = allChannels.firstOrNull { it.streamUrl == url }
            ?: allChannels.firstOrNull { it.streamUrl.trimEnd() == url.trimEnd() }

        val linkType = when {
            url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            url.contains(".mpd",  ignoreCase = true) -> ExtractorLinkType.DASH
            else                                     -> ExtractorLinkType.M3U8
        }

        val referer = ch?.referer?.takeIf { it.isNotBlank() } ?: ""

        val baseHeaders = buildMap<String, String> {
            put("User-Agent", ch?.userAgent ?: USER_AGENT)
            if (referer.isNotBlank()) put("Referer", referer)
        }

        val channelName = ch?.name ?: name

        when (ch?.drmType?.lowercase()) {

            // ------------------------------------------------------------------
            // ClearKey DRM
            // ------------------------------------------------------------------
            "clearkey", "org.w3.clearkey" -> {
                if (ch.drmKid != null && ch.drmKey != null) {
                    callback(
                        newDrmExtractorLink(
                            source = name,
                            name   = channelName,
                            url    = url,
                            uuid   = CLEARKEY_UUID,
                            type   = linkType,
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.headers = baseHeaders
                            this.kid     = ch.drmKid
                            this.key     = ch.drmKey
                            this.kty     = "oct"
                        }
                    )
                } else {
                    // ClearKey tanpa kid/key — putar sebagai plain stream
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = channelName,
                            url    = url,
                            type   = linkType,
                        ) {
                            this.referer = referer
                            this.quality = Qualities.Unknown.value
                            this.headers = baseHeaders
                        }
                    )
                }
            }

            // ------------------------------------------------------------------
            // Widevine DRM (Transvision, HBO, dll)
            // dt-custom-data harus ada di KEDUA tempat:
            //   1. headers         → untuk HTTP request ke license server
            //   2. keyRequestParameters → untuk ExoPlayer DRM session
            // ------------------------------------------------------------------
            "com.widevine.alpha", "widevine" -> {
                val licUrl = ch.drmLicenseUrl ?: transvisionLicenseUrl

                // Header untuk license request — wajib include dt-custom-data
                val wvHeaders = baseHeaders + mapOf(
                    "dt-custom-data" to transvisionDtCustomData,
                    "Content-Type"   to "application/octet-stream",
                )

                callback(
                    newDrmExtractorLink(
                        source = name,
                        name   = channelName,
                        url    = url,
                        uuid   = WIDEVINE_UUID,
                        type   = linkType,
                    ) {
                        this.referer              = referer
                        this.quality              = Qualities.Unknown.value
                        this.headers              = wvHeaders
                        this.licenseUrl           = licUrl
                        this.keyRequestParameters = hashMapOf(
                            "dt-custom-data" to transvisionDtCustomData,
                        )
                    }
                )
            }

            // ------------------------------------------------------------------
            // Plain stream — HLS atau DASH tanpa DRM
            // ------------------------------------------------------------------
            else -> {
                callback(
                    newExtractorLink(
                        source = name,
                        name   = channelName,
                        url    = url,
                        type   = linkType,
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = baseHeaders
                    }
                )
            }
        }
        return true
    }
}
