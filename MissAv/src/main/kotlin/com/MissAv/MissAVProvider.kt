package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAVProvider : MainAPI() {
    override var mainUrl = "https://missav.ws/id"
    override var name = "MissAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ==============================
    // 1. KONFIGURASI KATEGORI
    // ==============================
    override val mainPage = mainPageOf(
        "https://missav.ws/dm628/id/uncensored-leak" to "Kebocoran Tanpa Sensor",
        "https://missav.ws/dm590/id/release" to "Keluaran Terbaru",
        "https://missav.ws/dm515/id/new" to "Recent Update",
        "https://missav.ws/dm68/id/genres/Wanita%20Menikah/Ibu%20Rumah%20Tangga" to "Wanita menikah"
    )

    private fun String.toUrl(): String {
        // Memastikan tidak ada URL yang rusak karena kurang garis miring (/)
        return if (this.startsWith("http")) this else {
            if (this.startsWith("/")) "https://missav.ws$this" else "https://missav.ws/$this"
        }
    }

    // ==============================
    // 2. HOME PAGE
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            request.data
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data}${separator}page=$page"
        }

        val document = app.get(url).document
        
        val items = document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        // 1. Ambil tag <a> pertama. Ini biasanya membungkus gambar dan PASTI mengarah ke URL video
        val linkElement = element.selectFirst("a") ?: return null
        val url = linkElement.attr("href").toUrl()
        
        // 2. Ambil elemen gambar untuk poster
        val imgElement = element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
        
        // 3. Prioritaskan mengambil judul dari atribut 'alt' pada gambar karena lebih akurat.
        // Jika kosong, baru gunakan cara lama (teks dari link) sebagai cadangan.
        val title = imgElement?.attr("alt")?.trim()?.ifEmpty {
            element.selectFirst("a.text-secondary")?.text()?.trim()
        } ?: "Unknown Title"

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 3. PENCARIAN
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query"
        val document = app.get(url).document
        
        return document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    // ==============================
    // 4. DETAIL VIDEO
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base.text-nord6")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: "Unknown Title"

        val description = document.selectFirst("div.text-secondary.break-all")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = document.select("div.text-secondary a[href*='/genres/']").map { it.text() }
        
        val actors = document.select("div.text-secondary a[href*='/actresses/'], div.text-secondary a[href*='/actors/']")
            .map { element ->
                ActorData(Actor(element.text(), null))
            }

        val year = document.selectFirst("time")?.text()?.trim()?.take(4)?.toIntOrNull()

        val durationSeconds = document.selectFirst("meta[property=og:video:duration]")
            ?.attr("content")?.toIntOrNull()
        val durationMinutes = durationSeconds?.div(60)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actors
            this.year = year
            this.duration = durationMinutes
        }
    }

    // ==============================
    // 5. PLAYER + SMART ACCURATE SUBTITLE
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val text = document.html()
        
        val regex = """nineyu\.com\\/([0-9a-fA-F-]+)\\/seek""".toRegex()
        val match = regex.find(text)
        
        if (match != null) {
            val uuid = match.groupValues[1]
            val videoUrl = "https://surrit.com/$uuid/playlist.m3u8"

            callback.invoke(
                newExtractorLink(
                    source = "MissAV",
                    name = "MissAV (Surrit)",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )

            // --- LOGIKA AKURASI TINGGI ---
            val title = document.selectFirst("h1.text-base.text-nord6")?.text()?.trim() ?: ""
            
            // 1. Ekstrak KODE ID (Wajib ada). Contoh: SSIS-669, IPX-123
            val codeRegex = """([A-Za-z]{2,5}-[0-9]{3,5})""".toRegex()
            val codeMatch = codeRegex.find(title)
            val code = codeMatch?.value
            
            // Hanya cari subtitle jika KODE ditemukan.
            // Jika tidak ada kode, jangan cari (karena pencarian judul teks biasa sering salah film)
            if (!code.isNullOrEmpty()) {
                fetchSubtitleCatStrict(code, subtitleCallback)
            }
            
            return true
        }

        return false
    }

    // ==============================
    // 6. HELPER SUBTITLE STRICT MODE
    // ==============================
    private suspend fun fetchSubtitleCatStrict(codeId: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // Langkah 1: Cari berdasarkan KODE ID saja agar hasil relevan
            val searchUrl = "https://www.subtitlecat.com/index.php?search=$codeId"
            val searchDoc = app.get(searchUrl).document

            // Ambil baris tabel hasil pencarian
            val rows = searchDoc.select("table.sub-table tbody tr")

            // Variabel counter agar tidak mengambil terlalu banyak (maksimal 3 varian teratas)
            var variantsFound = 0
            val maxVariants = 3 

            // Loop setiap hasil pencarian
            for (row in rows) {
                if (variantsFound >= maxVariants) break

                val linkElement = row.selectFirst("td a") ?: continue
                val resultTitle = linkElement.text()
                val resultHref = linkElement.attr("href")

                // --- VALIDASI KETAT (STRICT CHECK) ---
                // Pastikan judul hasil pencarian MENGANDUNG Kode ID. Case insensitive (huruf besar/kecil dianggap sama)
                if (resultTitle.contains(codeId, ignoreCase = true)) {
                    
                    val detailPageUrl = if (resultHref.startsWith("http")) resultHref else "https://www.subtitlecat.com/$resultHref"
                    
                    // Langkah 2: Masuk ke halaman detail untuk varian ini
                    val detailDoc = app.get(detailPageUrl).document
                    
                    // Ambil nama varian dari judul hasil (misal: "JUR-613.ver1") untuk label
                    // Kita potong string agar tidak kepanjangan di layar
                    val shortVariantName = if (resultTitle.length > 20) resultTitle.take(20) + "..." else resultTitle

                    detailDoc.select("div.sub-single").forEach { subBox ->
                        val lang = subBox.select("span").getOrNull(1)?.text()?.trim() ?: "Unknown"
                        val downloadLink = subBox.select("a[href$='.srt']").firstOrNull()?.attr("href")

                        if (!downloadLink.isNullOrEmpty()) {
                            val fullDownloadUrl = if (downloadLink.startsWith("http")) downloadLink else "https://www.subtitlecat.com$downloadLink"
                            
                            // Ekstrak nama file asli untuk label (opsional, biar user tahu ini file yg mana)
                            val fileName = downloadLink.substringAfterLast("/").replace(".srt", "")
                            
                            // Format Label: "Indonesia [Nama File/Varian]"
                            // Contoh: "Indonesian [JUR-613.ver1]"
                            val label = "$lang [$fileName]"

                            subtitleCallback.invoke(
                                SubtitleFile(lang, fullDownloadUrl).apply {
                                    // Kita override label agar muncul varian di pilihan player
                                    // Catatan: Cloudstream mungkin menggunakan 'lang' sebagai label utama, 
                                    // tapi url unik tetap akan membedakannya.
                                }
                            )
                        }
                    }
                    variantsFound++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
