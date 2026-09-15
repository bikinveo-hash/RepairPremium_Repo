package com.PODJAV

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class PodjavProvider : MainAPI() {
    override var name = "PODJAV"
    override var mainUrl = "https://podjav.tv"
    override var lang = "id"
    override val hasMainPage = true
    
    // Memberikan label NSFW agar konten dewasa terpisah di aplikasi
    override val supportedTypes = setOf(TvType.NSFW)

    // Daftar kategori/genre yang akan muncul di halaman utama (Tanpa Baru Upload)
    override val mainPage = mainPageOf(
        "$mainUrl/genre/affair/" to "Perselingkuhan",
        "$mainUrl/genre/abuse/" to "Pelecehan",
        "$mainUrl/genre/cuckold/" to "Istri Tidak Setia",
        "$mainUrl/genre/married-woman/" to "Wanita Menikah",
        "$mainUrl/genre/rape/" to "Kekerasan",
        "$mainUrl/genre/young-wife/" to "Istri Muda",
        "$mainUrl/genre/sweat/" to "Sweat",
        "$mainUrl/genre/kiss/" to "Kiss",
        "$mainUrl/genre/step-mother/" to "Ibu Tiri"
    )

    /**
     * Mengubah elemen kotak film di website menjadi objek hasil pencarian
     */
    private fun Element.toSearchResult(): SearchResponse? {
        // Abaikan elemen jika itu adalah iklan (banner-card)
        if (this.hasClass("banner-card")) return null

        val url = this.attr("href")
        // Pastikan URL valid dan mengarah ke situs podjav
        if (url.isBlank() || !url.startsWith("http")) return null

        // Ambil judul dari class card-title atau data-title
        // data-title dipakai kalau .card-title kosong. attr() mengembalikan ""
        // (bukan null) saat atribut tidak ada, jadi kekosongan diperiksa lewat
        // isNotBlank() setelah dibersihkan, bukan lewat rantai ?: saja.
        val titleText = cleanTitle(
            this.selectFirst(".card-title")?.text() ?: this.attr("data-title")
        ).takeIf { it.isNotBlank() } ?: return null
        
        // Ambil gambar sampul/poster
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        // Mendeteksi label Uncensored.
        // Dipastikan langsung dari sumber HTML situs; dua penanda ini selalu ada:
        //   <span class="badge-uncen">UNCEN</span>
        //   data-genre="... uncensored"
        // data-genre dipecah per token supaya cocok persis, bukan sekadar contains.
        val isUncensored = this.selectFirst("span.badge-uncen") != null ||
            this.attr("data-genre").split(" ").any { it.equals("uncensored", ignoreCase = true) }

        // Hanya yang uncensored diberi penanda; yang disensor dibiarkan polos.
        // Satu ikon di depan judul, tidak memakan ruang judul seperti teks panjang.
        val finalTitle = if (isUncensored) "👑 $titleText" else titleText

        return newMovieSearchResponse(finalTitle, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val items = mutableListOf<HomePageList>()
        
        // Handle URL untuk pagination (halaman 1, 2, dst)
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data == "$mainUrl/") "$mainUrl/page/$page/" else "${request.data}page/$page/"
        }
        
        val document = app.get(url).document

        if (request.name == "Baru Upload" && page == 1) {
            // Mengambil semua section film di beranda (Trending, Terbaru, dll)
            document.select("section").forEach { section ->
                val sectionTitle = section.selectFirst(".section-title")?.text() ?: return@forEach
                
                // Lewati section yang bukan berisi daftar film
                if (sectionTitle.contains("Artis", true) || 
                    sectionTitle.contains("TENTANG", true) || 
                    sectionTitle.contains("FAQ", true)) return@forEach
                
                val list = section.select("a.video-card").mapNotNull { it.toSearchResult() }
                if (list.isNotEmpty()) {
                    // isHorizontalImages = true -> kartu jadi landscape.
                    // Sampul podjav memang berformat lebar (~2:1), selama ini
                    // dipotong paksa ke potret sehingga cuma terlihat sepotong.
                    items.add(HomePageList(sectionTitle, list, isHorizontalImages = true))
                }
            }
        } else {
            // Logika untuk halaman kategori/genre atau halaman 2 ke atas
            val elements = document.select("a.video-card")
            val list = elements.mapNotNull { it.toSearchResult() }
            if (list.isNotEmpty()) {
                items.add(HomePageList(request.name, list, isHorizontalImages = true))
            }
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(items, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        // Menerjemahkan teks pencarian agar spasi aman di URL
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encodedQuery"
        val document = app.get(url).document

        // Mencari semua elemen a.video-card di halaman hasil pencarian
        return document.select("a.video-card").mapNotNull { it.toSearchResult() }
    }

    /**
     * Membersihkan judul dari situs.
     *
     * Semua judul podjav berpola "KODE Sub Indo : deskripsi". Potongan
     * "Sub Indo :" muncul di SETIAP judul sehingga tidak menambah informasi
     * apa pun, tapi memakan ~10 karakter dari jatah 2 baris judul CloudStream.
     * Dibuang supaya deskripsinya kebagian ruang lebih banyak.
     *
     * Pola \s*Sub\s+[A-Za-z]+\s*:\s* juga menangkap "Sub Dutch", "Sub English",
     * dan varian bahasa lain yang dipakai situs. Judul tanpa pola itu
     * dibiarkan apa adanya.
     */
    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\s*Sub\s+[A-Za-z]+\s*:\s*""", RegexOption.IGNORE_CASE), ": ")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleText = cleanTitle(document.selectFirst("h1.video-info-title")?.text() ?: "")
            .takeIf { it.isNotBlank() } ?: return null
        // POSTER HALAMAN DETAIL
        // Halaman detail memuat DUA gambar untuk film yang sama:
        //   video[data-poster]  -> JUL-657-cover.jpg   sampul penuh, lebar ~2:1
        //   .video-info-top img -> JAV-JUL-657-1.jpg   200x283, tegak
        // Header detail CloudStream berbentuk lebar dan memakai centerCrop, jadi
        // gambar tegak pasti terpotong atas-bawah. Sampul lebar hampir pas mengisi
        // wadah itu, sehingga tampil paling utuh.
        //
        // JANGAN pakai "img[src*='-poster']": karusel "Rekomendasi JAV" di bagian
        // bawah halaman berisi gambar -poster.jpg MILIK FILM LAIN, dan poster film
        // ini sendiri tidak mengandung kata "poster" pada namanya. selectFirst akan
        // mengambil poster film lain.
        //
        // og:image juga tidak dipakai: isinya sama dengan versi tegak 379x538.
        val posterUrl = document.selectFirst("video[data-poster]")
            ?.attr("data-poster")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".video-info-top img")?.attr("src")

        // SINOPSIS
        // Selector lama "#tab-synopsis .text-sm p" sudah tidak cocok dengan tata
        // letak situs sekarang -> muncul "Plot Tidak Ditemukan".
        // Dicoba berjenjang, dari yang paling spesifik ke yang paling tahan banting.
        // Cara terakhir tidak bergantung nama class sama sekali: cari heading yang
        // teksnya "SYNOPSIS", lalu ambil elemen berisi teks panjang sesudahnya.
        val plot = document.selectFirst("#tab-synopsis .text-sm p")?.text()
            ?: document.selectFirst("#tab-synopsis p")?.text()
            ?: document.selectFirst("[class*=synopsis] p")?.text()
            ?: document.selectFirst("[id*=synopsis] p")?.text()
            ?: document.select("h1,h2,h3,h4,h5,h6,div,span,strong")
                .firstOrNull { it.ownText().trim().equals("SYNOPSIS", ignoreCase = true) }
                ?.let { heading ->
                    generateSequence(heading.nextElementSibling()) { it.nextElementSibling() }
                        .take(6)
                        .map { it.text().trim() }
                        .firstOrNull { it.length > 40 }
                        ?: heading.parent()?.select("p")
                            ?.map { it.text().trim() }
                            ?.firstOrNull { it.length > 40 }
                }
            // meta[name=description] SENGAJA tidak dipakai sebagai cadangan:
            // isinya cuma "Nonton JAV <judul>", yaitu judul yang diulang.
            // Lebih jujur menyatakan sinopsisnya memang belum ada.
            ?: "Sinopsis belum tersedia."
        
        val tags = mutableListOf<String>()
        var year: Int? = null
        val actors = mutableListOf<ActorData>()

        // Mengambil metadata dari tabel informasi film
        document.select(".info-row-item").forEach { row ->
            val label = row.selectFirst(".info-label")?.text()?.trim() ?: ""
            val values = row.select(".info-value a").map { it.text().trim() }
            
            when {
                label.contains("Genre", true) -> tags.addAll(values)
                label.contains("Cast", true) -> values.forEach { actors.add(ActorData(Actor(it))) }
                label.contains("Tahun", true) -> year = values.firstOrNull()?.toIntOrNull()
            }
        }

        // Mengambil daftar video rekomendasi
        val recommendations = document.select(".carousel-track a.reko-card").mapNotNull {
            val recUrl = it.attr("href") ?: return@mapNotNull null
            val imgElem = it.selectFirst("img") ?: return@mapNotNull null
            val recPoster = imgElem.attr("src")
            val recTitle = it.selectFirst(".reko-card-title")?.text() ?: return@mapNotNull null
            
            newMovieSearchResponse(recTitle, recUrl, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(titleText, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.year = year
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Cari elemen video utama
        val videoElement = document.selectFirst("#podjavPlayer")
        var embedUrl: String? = null
        var foundDirectLink = false

        // 1. Ambil data dari JSON data-sources
        if (videoElement != null) {
            val dataSourcesRaw = videoElement.attr("data-sources")
            if (dataSourcesRaw.isNotBlank() && dataSourcesRaw != "[]") {
                val sources = AppUtils.parseJson<List<VideoSource>>(dataSourcesRaw)
                
                sources.forEach { source ->
                    val url = source.url
                    if (url.isNotBlank()) {
                        // CEK TIPE LINK: Apakah ini Direct Link atau Embed?
                        val isDirectMp4 = source.type == "mp4" || url.contains(".mp4", ignoreCase = true)
                        val isDirectM3u8 = source.type == "m3u8" || url.contains(".m3u8", ignoreCase = true)

                        if (isDirectMp4 || isDirectM3u8) {
                            // JIKA DIRECT LINK (.mp4 / .m3u8): Langsung kirim ke player!
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    // source.label sering berisi string KOSONG,
                                    // bukan null, sehingga "?:" tidak pernah aktif
                                    // dan nama server tampil blank di daftar pemutar.
                                    // isNotBlank() menangani keduanya sekaligus.
                                    name = source.label?.takeIf { it.isNotBlank() }
                                        ?: if (isDirectMp4) "Podjav (MP4)" else "Podjav (M3U8)",
                                    url = url,
                                    type = if (isDirectMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.P720.value
                                }
                            )
                            foundDirectLink = true
                        } else if (source.type == "embed" && embedUrl == null) {
                            // JIKA EMBED LINK: Simpan dulu untuk kita bongkar nanti
                            embedUrl = url
                        }
                    }
                }
            }
            
            // Ekstrak Subtitle (jika ada)
            val dataSubtitlesRaw = videoElement.attr("data-subtitles")
            if (dataSubtitlesRaw.isNotBlank() && dataSubtitlesRaw != "[]") {
                val subtitles = AppUtils.parseJson<List<SubtitleSource>>(dataSubtitlesRaw)

                subtitles.forEach { sub ->
                    val rawSrc = sub.src.trim()
                    if (rawSrc.isNotBlank()) {

                        // BUG 1 - TOKEN DI HTML SUDAH BASI
                        // Halaman film dilayani dari cache (LiteSpeed / x-subtitle-cache: HIT),
                        // jadi token di dalam data-subtitles ikut basi -> subtitle.php
                        // menjawab "Access denied.".
                        // Player asli tidak pernah memakai token itu: dia POST dulu ke
                        // admin-ajax.php action=podjav_fresh_subtitle_url untuk minta
                        // token baru, baru mengunduh subtitlenya.
                        val pid = Regex("""pid=(\d+)""").find(rawSrc)?.groupValues?.getOrNull(1)
                        val bid = Regex("""bid=(\d+)""").find(rawSrc)?.groupValues?.getOrNull(1) ?: "1"

                        var chosenSrc = rawSrc
                        if (pid != null) {
                            try {
                                val freshRes = app.post(
                                    "$mainUrl/wp-admin/admin-ajax.php",
                                    data = mapOf(
                                        "action" to "podjav_fresh_subtitle_url",
                                        "pid" to pid,
                                        "bid" to bid
                                    ),
                                    referer = data,
                                    headers = mapOf("Origin" to mainUrl)
                                )
                                val fresh = AppUtils.tryParseJson<FreshSubtitleResponse>(freshRes.text)
                                val freshUrl = fresh?.data?.url?.trim()
                                if (fresh?.success == true && !freshUrl.isNullOrBlank()) {
                                    chosenSrc = freshUrl
                                }
                            } catch (e: Exception) {
                                // Kalau AJAX gagal, pakai token dari HTML sebagai cadangan
                            }
                        }

                        // BUG 2 - URL RELATIF
                        // Server mengirim "/subtitle.php?..." tanpa domain. ExoPlayer butuh
                        // URL absolut; kalau relatif dia lempar "Malformed URL" dan track
                        // subtitle muncul di menu tapi isinya kosong.
                        var subUrl = fixUrl(chosenSrc)

                        // BUG 3 - SALAH TEBAK FORMAT
                        // data-subtitles menulis "format":"srt", TAPI respons asli server
                        // adalah content-type: text/vtt, body diawali "WEBVTT", dan seluruh
                        // timestampnya bertitik (00:00:42.000) bukan berkoma.
                        // CloudStream menebak mime dari akhiran URL; "/subtitle.php?..."
                        // tidak berakhiran apa pun -> default application/x-subrip ->
                        // parser SRT dipakai untuk isi VTT -> nol cue, tanpa error.
                        // Fragment "#.vtt" membuat tebakan jadi text/vtt, dan sesuai spec
                        // HTTP fragment TIDAK pernah dikirim ke server, jadi token utuh.
                        if (!subUrl.endsWith("vtt", ignoreCase = true)) subUrl += "#.vtt"

                        // CATATAN HEADER
                        // Konstruktor SubtitleFile(lang, url, headers) bersifat PRIVATE.
                        // Jalur resminya adalah builder newSubtitleFile (lihat issue #1809 /
                        // PR #1810 di repo cloudstream). Untuk sekarang dipakai konstruktor
                        // 2-argumen yang publik, karena setelah token diperbarui lewat AJAX
                        // header kemungkinan besar tidak lagi dibutuhkan.
                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = sub.label ?: "Indonesia",
                                url = subUrl
                            )
                        )
                    }
                }
            }
        }


        // Jika kita sudah menemukan direct link (seperti MP4), HENTIKAN proses.
        // Kita tidak perlu susah-susah mencari dan membongkar iframe lagi.
        if (foundDirectLink) return true


        // ==========================================
        // PROSES UNTUK LINK EMBED / PIHAK KETIGA
        // ==========================================
        
        // 2. Fallback: Jika tidak ada direct link dan embedUrl kosong, cari tag iframe manual
        if (embedUrl == null) {
            embedUrl = document.selectFirst("iframe#podjavEmbed")?.attr("src")
                ?: document.selectFirst(".player-wrapper iframe")?.attr("src")
                ?: document.selectFirst("iframe")?.attr("src")
        }

        // 3. Proses bongkar link Embed
        if (embedUrl != null) {
            if (embedUrl.startsWith("//")) embedUrl = "https:$embedUrl"
            
            try {
                // Kunjungi halaman embed (misal: movearnpre.com / vidhide)
                val iframeResponse = app.get(embedUrl, referer = mainUrl).text
                
                // Gunakan fungsi Unpack untuk membongkar script
                val unpacked = getAndUnpack(iframeResponse)

                // Cari link m3u8 atau mp4 dari script yang sudah dibongkar
                val linkRegex = Regex("""file:\s*["']((?:https?://|/)[^"']*\.(?:m3u8|mp4)[^"']*)["']""")
                val alternateLinkRegex = Regex("""["']((?:https?://|/)[^"']*\.(?:m3u8|mp4)[^"']*)["']""")
                
                var videoLink = linkRegex.find(unpacked)?.groupValues?.get(1)
                    ?: alternateLinkRegex.find(unpacked)?.groupValues?.get(1)
                    ?: linkRegex.find(iframeResponse)?.groupValues?.get(1)
                    ?: alternateLinkRegex.find(iframeResponse)?.groupValues?.get(1)

                if (videoLink != null) {
                    if (videoLink.startsWith("/")) {
                        val uri = URI(embedUrl)
                        videoLink = "${uri.scheme}://${uri.host}$videoLink"
                    }

                    val isMp4 = videoLink.contains(".mp4", ignoreCase = true)

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "Server Eksternal " + if (isMp4) "(MP4)" else "(M3U8)",
                            url = videoLink,
                            type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedUrl 
                            this.quality = Qualities.P720.value
                        }
                    )
                } else {
                    // Jika regex gagal, serahkan ke Extractor bawaan Cloudstream
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}

/**
 * Model data untuk membaca data-sources dan data-subtitles dari player baru
 */
data class VideoSource(
    @JsonProperty("url") val url: String,
    @JsonProperty("type") val type: String?,
    @JsonProperty("label") val label: String?
)

data class SubtitleSource(
    @JsonProperty("src") val src: String,
    @JsonProperty("srclang") val srclang: String?,
    @JsonProperty("label") val label: String?
)

/**
 * Balasan admin-ajax.php action=podjav_fresh_subtitle_url
 * Contoh: {"success":true,"data":{"url":"\/subtitle.php?pid=13587&bid=1&token=4840..."}}
 */
data class FreshSubtitleResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("data") val data: FreshSubtitleData?
)

data class FreshSubtitleData(
    @JsonProperty("url") val url: String?
)
