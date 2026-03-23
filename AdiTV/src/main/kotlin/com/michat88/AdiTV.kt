package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.amap

/**
 * Data class untuk menyimpan informasi satu channel dari M3U playlist.
 *
 * @property name          Nama channel yang ditampilkan di UI.
 * @property streamUrl     URL stream langsung (HLS / DASH).
 * @property logo          URL logo/poster channel.
 * @property group         Nama grup/kategori channel.
 * @property userAgent     Custom User-Agent khusus channel ini.
 * @property referer       Custom Referer khusus channel ini.
 * @property drmType       Tipe DRM: null | "clearkey" | "widevine"
 * @property drmKid        ClearKey: Key ID (hex string).
 * @property drmKey        ClearKey: Key value (hex string).
 * @property drmLicenseUrl Widevine: URL license server.
 */
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
)

class AdiTVProvider : MainAPI() {

    // -----------------------------------------------------------------------
    // Konfigurasi dasar plugin
    // -----------------------------------------------------------------------

    override var name = "AdiTV"
    override var mainUrl = "https://raw.githubusercontent.com"
    override var lang = "id"

    override val hasMainPage = true
    override val hasQuickSearch = false

    // Wajib true untuk Live stream — link langsung diputar tanpa ekstra loading
    override val instantLinkLoading = true

    override val supportedTypes = setOf(TvType.Live)

    override val providerType = ProviderType.DirectProvider
    override val vpnStatus = VPNStatus.None

    // -----------------------------------------------------------------------
    // Konstanta internal
    // -----------------------------------------------------------------------

    /**
     * URL raw GitHub tempat file playlist.m3u dihost.
     * Ganti dengan URL repo milikmu setelah upload file M3U ke GitHub.
     * Contoh: https://raw.githubusercontent.com/michat88/iptv-playlist/main/playlist.m3u
     */
    private val playlistUrl =
        "https://raw.githubusercontent.com/michat88/iptv-playlist/main/playlist.m3u"

    /**
     * dt-custom-data header untuk channel Transvision & HBO (Widevine).
     * Nilai ini diambil langsung dari playlist.
     */
    private val transvisionDtCustomData =
        "eyJ1c2VySWQiOiJyZWFjdC1qdy1wbGF5ZXIiLCJzZXNzaW9uSWQiOiIxMjM0NTY3ODkiLCJtZXJjaGFudCI6ImdpaXRkX3RyYW5zdmlzaW9uIn0="

    /**
     * Widevine license URL untuk channel Transvision & HBO.
     */
    private val transvisionLicenseUrl =
        "https://cubmu.devhik.workers.dev/license_cenc"

    /**
     * Grup yang di-skip saat parsing (VOD-Movie tidak termasuk IPTV live).
     */
    private val skippedGroups = setOf("VOD-Movie")

    // -----------------------------------------------------------------------
    // Cache internal
    // -----------------------------------------------------------------------

    /** Cache semua channel yang sudah diparse agar tidak fetch ulang per session. */
    private var cachedChannels: List<IptvChannel>? = null

    // -----------------------------------------------------------------------
    // Konfigurasi mainPage — kategori yang tampil di beranda
    // -----------------------------------------------------------------------

    /**
     * Setiap [mainPage] data-nya adalah nama grup yang difilter dari playlist.
     * Menggunakan `mainPageOf` sesuai standar MainAPI.kt.
     */
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
        "NEWS & ENTERTAINMENT" to "📰 News & Entertainment",
        "Channel Tv Singapore" to "🇸🇬 Singapore",
        "MALAYSIA"             to "🇲🇾 Malaysia",
        "TVRI"                 to "📡 TVRI",
    )

    // -----------------------------------------------------------------------
    // Parser M3U
    // -----------------------------------------------------------------------

    /**
     * Fetch dan parse file M3U dari [playlistUrl].
     * Hasil di-cache di [cachedChannels] supaya tidak re-fetch dalam satu session.
     *
     * @return List [IptvChannel], kosong jika gagal fetch.
     */
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

    /**
     * Parse teks M3U menjadi list [IptvChannel].
     * Mendukung:
     * - `#EXTINF` untuk metadata channel (tvg-logo, group-title, nama)
     * - `#EXTVLCOPT:http-user-agent` untuk custom User-Agent
     * - `#EXTVLCOPT:http-referrer` untuk custom Referer
     * - `#KODIPROP:inputstream.adaptive.license_type` untuk tipe DRM
     * - `#KODIPROP:inputstream.adaptive.license_key` untuk ClearKey KID:KEY
     *   atau Widevine license URL
     *
     * Group "VOD-Movie" di-skip otomatis.
     *
     * @param m3uText Isi file M3U sebagai String.
     * @return List [IptvChannel] hasil parsing.
     */
    private fun parseM3u(m3uText: String): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        val lines = m3uText.lines()

        // State sementara untuk satu blok channel
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingUserAgent: String? = null
        var pendingReferer: String? = null
        var pendingDrmType: String? = null
        var pendingDrmKid: String? = null
        var pendingDrmKey: String? = null
        var pendingDrmLicenseUrl: String? = null

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
        }

        for (line in lines) {
            val trimmed = line.trim()

            when {
                // ----- Tag #EXTINF: metadata utama channel -----
                trimmed.startsWith("#EXTINF") -> {
                    pendingLogo = Regex("""tvg-logo="([^"]*)"""")
                        .find(trimmed)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() && it != "_____" }

                    pendingGroup = Regex("""group-title="([^"]*)"""")
                        .find(trimmed)?.groupValues?.get(1)
                        ?.takeIf { it.isNotBlank() }

                    // Nama channel = teks setelah koma terakhir di baris #EXTINF
                    pendingName = trimmed.substringAfterLast(",").trim()
                        .takeIf { it.isNotBlank() }
                }

                // ----- Custom User-Agent per channel -----
                trimmed.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    pendingUserAgent = trimmed
                        .removePrefix("#EXTVLCOPT:http-user-agent=")
                        .trim()
                        .takeIf { it.isNotBlank() }
                }

                // ----- Custom Referer per channel -----
                trimmed.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    pendingReferer = trimmed
                        .removePrefix("#EXTVLCOPT:http-referrer=")
                        .trim()
                        .takeIf { it.isNotBlank() }
                }

                // ----- Tipe DRM -----
                trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> {
                    val rawType = trimmed
                        .removePrefix("#KODIPROP:inputstream.adaptive.license_type=")
                        .trim()
                        .lowercase()
                    // Ambil hanya nilai yang valid, abaikan noise seperti "clearkeystro supersporta"
                    pendingDrmType = when {
                        rawType.startsWith("clearkey") || rawType == "org.w3.clearkey" -> "clearkey"
                        rawType.startsWith("com.widevine") -> "widevine"
                        else -> pendingDrmType // pertahankan nilai sebelumnya jika ada multi-prop
                    }
                }

                // ----- DRM Key (ClearKey KID:KEY atau Widevine license URL) -----
                trimmed.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val keyValue = trimmed
                        .removePrefix("#KODIPROP:inputstream.adaptive.license_key=")
                        .trim()

                    if (keyValue.startsWith("http")) {
                        // Ini adalah Widevine license URL
                        pendingDrmLicenseUrl = keyValue
                    } else if (keyValue.contains(":")) {
                        // Format ClearKey: KID:KEY (hex)
                        val parts = keyValue.split(":")
                        if (parts.size == 2) {
                            pendingDrmKid = parts[0].trim()
                            pendingDrmKey = parts[1].trim()
                        }
                    }
                }

                // ----- URL stream: baris yang dimulai dengan http/https -----
                trimmed.startsWith("http") -> {
                    val group = pendingGroup ?: ""

                    // Skip VOD-Movie dan grup kosong
                    if (group !in skippedGroups && pendingName != null) {
                        channels.add(
                            IptvChannel(
                                name          = pendingName!!,
                                streamUrl     = trimmed,
                                logo          = pendingLogo,
                                group         = group.takeIf { it.isNotBlank() },
                                userAgent     = pendingUserAgent,
                                referer       = pendingReferer,
                                drmType       = pendingDrmType,
                                drmKid        = pendingDrmKid,
                                drmKey        = pendingDrmKey,
                                drmLicenseUrl = pendingDrmLicenseUrl,
                            )
                        )
                    }
                    // Reset state setelah URL ditemukan
                    resetState()
                }

                // ----- Baris komentar / separator / kosong: abaikan -----
                trimmed.startsWith("#") || trimmed.isBlank() -> {
                    // Tidak ada aksi, lanjut ke baris berikutnya
                }
            }
        }

        return channels
    }

    // -----------------------------------------------------------------------
    // getMainPage — beranda per kategori
    // -----------------------------------------------------------------------

    /**
     * Memuat daftar channel berdasarkan grup yang dipilih di [mainPage].
     *
     * Sesuai standar MainAPI.kt:
     * - [MainPageRequest.data] berisi nama grup sebagai filter.
     * - Mengembalikan [HomePageResponse] via `newHomePageResponse`.
     * - Menggunakan `amap` dari ParCollections.kt untuk concurrent mapping.
     *
     * @param page    Nomor halaman dimulai dari 1, 50 channel per halaman.
     * @param request [MainPageRequest] berisi name & data (nama grup).
     * @return [HomePageResponse] atau null jika tidak ada channel.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val allChannels = fetchChannels()
        if (allChannels.isEmpty()) return null

        val groupFilter = request.data

        val filtered = allChannels.filter { channel ->
            channel.group?.equals(groupFilter, ignoreCase = true) == true
        }

        // Pagination: 50 channel per halaman
        val pageSize  = 50
        val fromIndex = (page - 1) * pageSize
        val toIndex   = minOf(fromIndex + pageSize, filtered.size)

        if (fromIndex >= filtered.size) return newHomePageResponse(
            name    = request.name,
            list    = emptyList(),
            hasNext = false,
        )

        val pageItems = filtered.subList(fromIndex, toIndex)

        // amap dari ParCollections.kt: concurrent mapping
        val searchResults = pageItems.amap { channel ->
            newLiveSearchResponse(
                name = channel.name,
                url  = channel.streamUrl,
                type = TvType.Live,
            ) {
                posterUrl = channel.logo
            }
        }

        return newHomePageResponse(
            name    = request.name,
            list    = searchResults,
            hasNext = toIndex < filtered.size,
        )
    }

    // -----------------------------------------------------------------------
    // search — pencarian channel by nama
    // -----------------------------------------------------------------------

    /**
     * Mencari channel berdasarkan query string (case-insensitive).
     * Mencari di nama channel dan nama grup.
     *
     * Sesuai standar MainAPI.kt:
     * - Mengembalikan `List<SearchResponse>?`
     * - Menggunakan `newLiveSearchResponse` factory function.
     *
     * @param query String pencarian.
     * @return List [SearchResponse] atau null jika gagal fetch.
     */
    override suspend fun search(query: String): List<SearchResponse>? {
        val allChannels = fetchChannels()
        if (allChannels.isEmpty()) return null

        val q = query.trim().lowercase()

        val matched = allChannels.filter { channel ->
            channel.name.lowercase().contains(q) ||
            channel.group?.lowercase()?.contains(q) == true
        }

        if (matched.isEmpty()) return emptyList()

        // amap dari ParCollections.kt: concurrent mapping
        return matched.amap { channel ->
            newLiveSearchResponse(
                name = channel.name,
                url  = channel.streamUrl,
                type = TvType.Live,
            ) {
                posterUrl = channel.logo
            }
        }
    }

    // -----------------------------------------------------------------------
    // load — halaman detail channel
    // -----------------------------------------------------------------------

    /**
     * Memuat detail channel dari URL stream-nya.
     *
     * URL yang diterima adalah URL stream langsung karena pada [search] dan
     * [getMainPage] kita set `url = channel.streamUrl`.
     *
     * Sesuai standar MainAPI.kt:
     * - Mengembalikan [LiveStreamLoadResponse] via `newLiveStreamLoadResponse`.
     *
     * @param url URL stream channel (stream URL dari hasil search/mainpage).
     * @return [LiveStreamLoadResponse] sebagai [LoadResponse], atau null.
     */
    override suspend fun load(url: String): LoadResponse? {
        val allChannels = fetchChannels()

        // Temukan channel yang cocok berdasarkan stream URL
        val channel = allChannels.firstOrNull { it.streamUrl == url }
            ?: IptvChannel(
                name      = url.substringAfterLast("/").substringBefore("?")
                              .ifBlank { "Channel" },
                streamUrl = url,
            )

        return newLiveStreamLoadResponse(
            name    = channel.name,
            url     = url,
            dataUrl = url,
        ) {
            posterUrl = channel.logo
            plot      = buildString {
                channel.group?.let    { append("Grup: $it\n") }
                channel.drmType?.let  { append("DRM: ${it.uppercase()}\n") }
            }.trim().takeIf { it.isNotBlank() }
            tags = listOfNotNull(channel.group)
        }
    }

    // -----------------------------------------------------------------------
    // loadLinks — menyediakan link stream ke player
    // -----------------------------------------------------------------------

    /**
     * Menyediakan link stream ke player CloudStream.
     *
     * Logika DRM mengikuti standar ExtractorApi.kt:
     *
     * **Tanpa DRM / unencrypted:**
     * - `newExtractorLink` dengan `ExtractorLinkType.M3U8` atau `DASH`
     *
     * **ClearKey:**
     * - `newDrmExtractorLink` dengan `CLEARKEY_UUID`
     * - `kid` dan `key` dari hasil parse `#KODIPROP:inputstream.adaptive.license_key`
     *
     * **Widevine (Transvision & HBO):**
     * - `newDrmExtractorLink` dengan `WIDEVINE_UUID`
     * - `licenseUrl` = `transvisionLicenseUrl`
     * - Header `dt-custom-data` dikirim via `keyRequestParameters`
     *
     * @param data             dataUrl string dari [load] (= URL stream channel).
     * @param isCasting        True jika sedang casting ke perangkat lain.
     * @param subtitleCallback Callback subtitle (tidak digunakan untuk IPTV).
     * @param callback         Callback yang dipanggil dengan setiap [ExtractorLink].
     * @return `true` jika link berhasil ditemukan, `false` jika gagal.
     */
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

        // Tentukan tipe link dari URL
        val linkType = when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            streamUrl.contains(".mpd",  ignoreCase = true) -> ExtractorLinkType.DASH
            else                                           -> ExtractorLinkType.M3U8
        }

        // Bangun headers per channel
        val headers = buildMap<String, String> {
            put("User-Agent", channel?.userAgent ?: USER_AGENT)
            channel?.referer?.takeIf { it.isNotBlank() }?.let {
                put("Referer", it)
            }
        }

        val referer = channel?.referer ?: ""

        return when (channel?.drmType?.lowercase()) {

            // ------------------------------------------------------------------
            // ClearKey DRM
            // ------------------------------------------------------------------
            "clearkey", "org.w3.clearkey" -> {
                val kid = channel.drmKid
                val key = channel.drmKey

                if (kid != null && key != null) {
                    val link = newDrmExtractorLink(
                        source = this.name,
                        name   = channel.name,
                        url    = streamUrl,
                        uuid   = CLEARKEY_UUID,
                        type   = linkType,
                    ) {
                        this.referer  = referer
                        this.quality  = Qualities.Unknown.value
                        this.headers  = headers
                        this.kid      = kid
                        this.key      = key
                        this.kty      = "oct"
                    }
                    callback(link)
                    true
                } else {
                    // ClearKey tanpa kid/key — coba putar tanpa DRM
                    val link = newExtractorLink(
                        source = this.name,
                        name   = channel.name,
                        url    = streamUrl,
                        type   = linkType,
                    ) {
                        this.referer  = referer
                        this.quality  = Qualities.Unknown.value
                        this.headers  = headers
                    }
                    callback(link)
                    true
                }
            }

            // ------------------------------------------------------------------
            // Widevine DRM (Transvision, HBO, dll)
            // ------------------------------------------------------------------
            "com.widevine.alpha", "widevine" -> {
                val licenseUrl = channel.drmLicenseUrl ?: transvisionLicenseUrl

                val link = newDrmExtractorLink(
                    source = this.name,
                    name   = channel.name,
                    url    = streamUrl,
                    uuid   = WIDEVINE_UUID,
                    type   = linkType,
                ) {
                    this.referer    = referer
                    this.quality    = Qualities.Unknown.value
                    this.headers    = headers
                    this.licenseUrl = licenseUrl
                    this.keyRequestParameters = hashMapOf(
                        "dt-custom-data" to transvisionDtCustomData
                    )
                }
                callback(link)
                true
            }

            // ------------------------------------------------------------------
            // Tanpa DRM / plain stream
            // ------------------------------------------------------------------
            else -> {
                val channelName = channel?.name ?: this.name
                val link = newExtractorLink(
                    source = this.name,
                    name   = channelName,
                    url    = streamUrl,
                    type   = linkType,
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
                callback(link)
                true
            }
        }
    }
}
