package com.LayarKacaProvider

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class LayarKacaProvider : MainAPI() {
    // URL utama website yang akan kita scrape
    override var mainUrl = "https://mamamas.xyz"
    
    // Nama provider yang akan muncul di aplikasi Cloudstream
    override var name = "LK21"
    
    // Memberitahu Cloudstream bahwa plugin ini punya halaman depan (Home)
    override val hasMainPage = true
    
    // Bahasa default subtitle atau web
    override var lang = "id"
    
    // Mengizinkan fitur download pada plugin ini
    override val hasDownloadSupport = true
    
    // Tipe media yang didukung oleh provider ini
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    // Daftar menu kategori di halaman depan (Home)
    // Kata di sebelah kiri adalah path URL, sebelah kanan adalah nama tab-nya
    override val mainPage = mainPageOf(
        "/latest" to "Terbaru",
        "/populer" to "Populer",
        "/rating" to "Top Rating"
    )

    /**
     * Fungsi ini dipanggil oleh Cloudstream untuk memuat halaman depan.
     * Akan dijalankan setiap kali user membuka tab "Terbaru", "Populer", dll, atau saat men-scroll (halaman berikutnya).
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Mengatur pagination (Halaman 1, 2, 3...)
        // Jika halaman 1, gunakan URL asli (contoh: https://mamamas.xyz/latest)
        // Jika halaman > 1, tambahkan /page/angka (contoh: https://mamamas.xyz/latest/page/2)
        val url = if (page == 1) {
            "$mainUrl${request.data}"
        } else {
            "$mainUrl${request.data}/page/$page"
        }

        // Mengunduh dan mem-parsing dokumen HTML dari URL tersebut
        val document = app.get(url).document

        // Mencari semua elemen film di dalam <div class="gallery-grid">
        val home = document.select("div.gallery-grid article").mapNotNull { element ->
            // Mengambil teks judul film
            val title = element.selectFirst("h3.poster-title")?.text() ?: return@mapNotNull null
            
            // Mengambil URL detail film
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val link = fixUrl(href) // fixUrl memastikan URL menjadi absolute (menambahkan https://mamamas.xyz jika perlu)
            
            // Mengambil URL gambar poster
            val poster = element.selectFirst("img[itemprop=image]")?.attr("src")
            
            // Mengambil teks kualitas (misal: "HD")
            val qualityStr = element.selectFirst("span.label")?.text()
            
            // Mengambil teks rating (misal: "6.8")
            val ratingStr = element.selectFirst("span[itemprop=ratingValue]")?.text()

            // Mengecek apakah elemen ini adalah TV Series (ditandai dengan adanya class 'episode')
            val episodeStr = element.selectFirst("span.episode")?.text()
            val isTvSeries = episodeStr != null

            // Menggunakan Builder Pattern (fungsi 'new...') untuk membungkus data
            if (isTvSeries) {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    addQuality(qualityStr ?: "")
                    ratingStr?.let { addScore(it, 10) } // Rating maksimal 10
                }
            } else {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    addQuality(qualityStr ?: "")
                    ratingStr?.let { addScore(it, 10) }
                }
            }
        }

        // Mengembalikan daftar film ke antarmuka aplikasi Cloudstream
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false // Kita set false karena poster film biasanya berbentuk vertikal/potret
            ),
            hasNext = true // Set true agar Cloudstream tahu bahwa ada halaman selanjutnya untuk di-scroll
        )
    }
}
