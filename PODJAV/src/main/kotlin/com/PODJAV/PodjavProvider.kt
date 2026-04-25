package com.PODJAV

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class PodjavProvider : MainAPI() {
    override var name = "PODJAV"
    override var mainUrl = "https://podjav.tv"
    override var lang = "id"
    override val hasMainPage = true
    
    // Memberikan label NSFW agar konten dewasa terpisah di aplikasi
    override val supportedTypes = setOf(TvType.NSFW)

    // Daftar kategori/genre yang akan muncul di halaman utama
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Baru Upload",
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
        val titleText = this.selectFirst(".card-title")?.text() 
            ?: this.attr("data-title") 
            ?: return null
        
        // Ambil gambar sampul/poster
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        // Mendeteksi label Uncensored dari class badge atau data genre
        val isUncensored = this.selectFirst(".badge-uncen") != null ||
            this.attr("data-genre").contains("uncensored", ignoreCase = true)
        val finalTitle = if (isUncensored) "🔥 [UNCENSORED] $titleText" else titleText

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
                    items.add(HomePageList(sectionTitle, list))
                }
            }
        } else {
            // Logika untuk halaman kategori/genre atau halaman 2 ke atas
            val elements = document.select("a.video-card")
            val list = elements.mapNotNull { it.toSearchResult() }
            if (list.isNotEmpty()) {
                items.add(HomePageList(request.name, list))
            }
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(items, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        // Mencari semua elemen a.video-card di halaman hasil pencarian
        return document.select("a.video-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleText = document.selectFirst("h1.video-info-title")?.text() ?: return null
        val posterUrl = document.selectFirst(".video-info-top img")?.attr("src")
        val plot = document.selectFirst("#tab-synopsis .text-sm p")?.text()
        
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

        // Cari elemen video utama (Desain Baru Podjav)
        val videoElement = document.selectFirst("#podjavPlayer")
        var foundNativeVideo = false

        // RENCANA A: Coba ambil dari sistem JSON (Sistem Baru Podjav)
        if (videoElement != null) {
            val dataSourcesRaw = videoElement.attr("data-sources")
            if (dataSourcesRaw.isNotBlank() && dataSourcesRaw != "[]") {
                val sources = AppUtils.parseJson<List<VideoSource>>(dataSourcesRaw)
                
                sources.forEach { source ->
                    val url = source.url
                    if (url.isNotBlank()) {
                        // Jika tipenya embed atau bukan file langsung, serahkan ke Extractor
                        if (source.type == "embed" || (!url.contains(".m3u8", true) && !url.contains(".mp4", true))) {
                            loadExtractor(url, mainUrl, subtitleCallback, callback)
                            foundNativeVideo = true
                        } else {
                            // Jika link langsung, proses seperti biasa
                            val isM3u8 = source.type == "m3u8" || url.contains(".m3u8", ignoreCase = true)
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = source.label ?: "HD Stream",
                                    url = url,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mainUrl
                                    this.quality = Qualities.P720.value
                                }
                            )
                            foundNativeVideo = true
                        }
                    }
                }
            }

            // Ekstrak Subtitle Rencana A
            val dataSubtitlesRaw = videoElement.attr("data-subtitles")
            if (dataSubtitlesRaw.isNotBlank() && dataSubtitlesRaw != "[]") {
                val subtitles = AppUtils.parseJson<List<SubtitleSource>>(dataSubtitlesRaw)
                
                subtitles.forEach { sub ->
                    if (sub.src.isNotBlank()) {
                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = sub.label ?: "Indonesia",
                                url = sub.src
                            )
                        )
                    }
                }
            }
        }

        // RENCANA B: JIKA RENCANA A KOSONG, KITA CARI IFRAME DAN GUNAKAN EXTRACTOR API
        if (!foundNativeVideo) {
            val iframeSrc = document.selectFirst("iframe#podjavEmbed")?.attr("src")
                ?: document.selectFirst(".player-wrapper iframe")?.attr("src")
                ?: document.selectFirst("iframe")?.attr("src")

            if (iframeSrc != null && iframeSrc.isNotBlank()) {
                var fixUrl = iframeSrc
                if (fixUrl.startsWith("//")) fixUrl = "https:$fixUrl"
                
                try {
                    // Serahkan ke Cloudstream Extractor untuk membongkar script
                    loadExtractor(fixUrl, mainUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
