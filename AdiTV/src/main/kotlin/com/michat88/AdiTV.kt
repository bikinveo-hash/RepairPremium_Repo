package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.get

class AdiTVProvider : MainAPI() {
    // Nama plugin yang akan muncul di aplikasi
    override var name = "AdiTV"
    
    // URL mentah (raw) file M3U kamu di Github
    override var mainUrl = "https://raw.githubusercontent.com/amanhnb88/AdiTV/main/streams/playlist_aktif.m3u"
    
    // Memberitahu Cloudstream bahwa plugin ini punya halaman utama (Home)
    override val hasMainPage = true
    
    // Fokus pada tipe Live TV
    override val supportedTypes = setOf(TvType.Live)

    /**
     * Langkah 1: Memuat daftar channel di halaman utama aplikasi
     */
    override suspend fun getMainPage(page: Int, requestPath: String?): HomePageResponse? {
        // Mengunduh isi teks dari file M3U di Github
        val m3uText = app.get(mainUrl).text
        val channels = mutableListOf<LiveSearchResponse>()
        
        // Variabel untuk menyimpan data sementara saat membaca baris
        var currentName = ""
        var currentLogo = ""

        // Membaca file baris demi baris
        val lines = m3uText.split("\n")
        for (line in lines) {
            val trimmedLine = line.trim()
            
            // Jika baris berisi informasi channel (dimulai dengan #EXTINF)
            if (trimmedLine.startsWith("#EXTINF")) {
                // Mencari URL logo channel (tvg-logo) menggunakan Regex
                val logoRegex = """tvg-logo="([^"]+)"""".toRegex()
                currentLogo = logoRegex.find(trimmedLine)?.groupValues?.get(1) ?: ""

                // Mengambil nama channel (biasanya teks setelah tanda koma terakhir)
                currentName = trimmedLine.substringAfterLast(",").trim()
            } 
            // Jika baris bukan kosong dan bukan komentar (biasanya ini adalah link streaming/video)
            else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                // Menyimpan channel ke dalam daftar
                channels.add(
                    LiveSearchResponse(
                        name = currentName,
                        url = trimmedLine, // URL video
                        apiName = this@AdiTVProvider.name,
                        type = TvType.Live,
                        posterUrl = currentLogo // Logo channel
                    )
                )
                // Mengosongkan nama untuk baris selanjutnya
                currentName = ""
            }
        }

        // Mengelompokkan semua channel dalam satu kategori bernama "Playlist Aktif"
        val homeList = HomePageList(
            name = "Playlist Aktif",
            list = channels
        )

        return HomePageResponse(listOf(homeList))
    }

    /**
     * Langkah 2: Mengatur halaman detail saat sebuah channel diklik
     */
    override suspend fun load(url: String): LoadResponse {
        return LiveStreamLoadResponse(
            name = "Live Stream", // Nama standar
            url = url,
            apiName = this.name,
            dataUrl = url
        )
    }

    /**
     * Langkah 3: Memberikan link video ke pemutar video (Player)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Memasukkan URL video langsung ke Extractor
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = this.name,
                url = data, // URL streaming dari M3U
                referer = "",
                quality = Qualities.Unknown.value,
                isM3u8 = data.contains(".m3u8") // Cek otomatis jika formatnya m3u8
            )
        )
        return true
    }
}
