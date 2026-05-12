package com.IndoTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.util.UUID

class IndoTV : MainAPI() {
    // Tautan playlist M3U sudah di-update ke repositori Zaneta
    override var mainUrl = "https://raw.githubusercontent.com/michat88/Zaneta/master/Indonesia.m3u"
    override var name = "IndoTV"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    // Mapping kategori berdasarkan group-title di playlist
    override val mainPage = mainPageOf(
        "INDONESIA" to "Saluran Indonesia",
        "KIDS" to "Anak-anak",
        "MOVIES" to "Film",
        "SPORTS" to "Olahraga",
        "KNOWLEDGE" to "Pengetahuan",
        "HATI-HATI PENIPUAN" to "Info Admin"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val playlistRaw = app.get(mainUrl).text
        val channels = parseM3U(playlistRaw)
        
        // Filter berdasarkan kategori yang dipilih user
        val filteredChannels = channels.filter { it.group == request.data }
            .map { channel ->
                newMovieSearchResponse(channel.name, channel.toJson(), TvType.Live) {
                    this.posterUrl = channel.logo
                }
            }

        return newHomePageResponse(request.name, filteredChannels)
    }

    override suspend fun load(url: String): LoadResponse {
        // Karena url berisi data JSON dari channel, kita parse kembali
        val channel = AppUtils.tryParseJson<M3UChannel>(url) ?: throw ErrorLoadingException("Gagal parse data")

        return newLiveStreamLoadResponse(channel.name, url, url) {
            this.posterUrl = channel.logo
            this.plot = "Kategori: ${channel.group}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = AppUtils.tryParseJson<M3UChannel>(data) ?: return false

        // Pengecekan apakah menggunakan proteksi ClearKey DRM
        if (channel.drmType == "clearkey" && channel.drmKey != null) {
            val keyParts = channel.drmKey!!.split(":")
            if (keyParts.size == 2) {
                callback.invoke(
                    newDrmExtractorLink(
                        source = name,
                        name = "${channel.name} (DRM)",
                        url = channel.url,
                        type = if (channel.url.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8,
                        uuid = CLEARKEY_UUID // Menggunakan UUID ClearKey dari API
                    ) {
                        this.kid = keyParts[0]
                        this.key = keyParts[1]
                        this.headers = channel.headers
                        this.referer = channel.headers["referer"] ?: ""
                    }
                )
            }
        } else {
            // Pemutaran biasa dengan kustom Headers/Referrer
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = channel.name,
                    url = channel.url,
                    type = if (channel.url.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                ) {
                    this.headers = channel.headers
                    this.referer = channel.headers["referer"] ?: ""
                }
            )
        }
        return true
    }

    // --- FUNGSI PARSER M3U TINGKAT LANJUT ---
    private fun parseM3U(raw: String): List<M3UChannel> {
        val list = mutableListOf<M3UChannel>()
        val lines = raw.lines()
        var currentChannel: M3UChannel? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTINF") -> {
                    // Ekstrak Nama, Logo, dan Group-Title
                    val name = line.substringAfterLast(",").trim()
                    val logo = Regex("""tvg-logo="([^"]+)"""").find(line)?.groupValues?.get(1)
                    val group = Regex("""group-title="([^"]+)"""").find(line)?.groupValues?.get(1) ?: "Other"
                    currentChannel = M3UChannel(name, "", logo, group)
                }
                line.startsWith("#EXTVLCOPT:http-referrer") -> {
                    // Ekstrak Referrer
                    val ref = line.substringAfter("=").trim()
                    currentChannel?.headers?.put("referer", ref)
                }
                line.startsWith("#EXTVLCOPT:http-user-agent") -> {
                    // Ekstrak User-Agent
                    val ua = line.substringAfter("=").trim()
                    currentChannel?.headers?.put("user-agent", ua)
                }
                line.startsWith("#KODIPROP:inputstream.adaptive.license_type") -> {
                    // Ekstrak Tipe DRM
                    currentChannel?.drmType = line.substringAfter("=").trim()
                }
                line.startsWith("#KODIPROP:inputstream.adaptive.license_key") -> {
                    // Ekstrak Kunci Lisensi DRM
                    currentChannel?.drmKey = line.substringAfter("=").trim()
                }
                line.startsWith("http") -> {
                    // Baris terakhir adalah URL Video
                    currentChannel?.let {
                        it.url = line.trim()
                        list.add(it)
                    }
                    currentChannel = null
                }
            }
        }
        return list
    }

    // Data class internal untuk menampung info channel hasil parsing
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
