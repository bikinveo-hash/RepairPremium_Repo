package com.PODJAV

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PodjavProvider : MainAPI() {
    override var name = "PODJAV"
    override var mainUrl = "https://podjav.tv"
    override var lang = "id"
    override val hasMainPage = true
    
    // LABEL NSFW: Mengubah tipe konten menjadi khusus dewasa
    override val supportedTypes = setOf(TvType.NSFW)

    // MENAMBAHKAN DAFTAR KATEGORI (GENRE)
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
     * HELPER: Mengubah elemen HTML daftar film menjadi SearchResponse
     * (DIUPDATE UNTUK TEMA TAILWIND BARU)
     */
    private fun Element.toSearchResult(): SearchResponse? {
        // Abaikan elemen jika itu adalah iklan / banner
        if (this.hasClass("banner-card")) return null

        val url = this.attr("href")
        // Pastikan URL-nya valid dan mengarah ke podjav
        if (url.isBlank() || !url.startsWith("http")) return null

        // Ambil judul dari class card-title atau data-title
        val titleText = this.selectFirst(".card-title")?.text() 
            ?: this.attr("data-title") 
            ?: return null
        
        // Ambil gambar cover
        val posterUrl = this.selectFirst("img.thumb")?.attr("src")

        // Deteksi label UNCENSORED pada desain baru
        val isUncensored = this.selectFirst(".badge-uncen") != null || this.attr("data-genre").contains("uncensored", ignoreCase = true)
        val finalTitle = if (isUncensored) "🔥 [UNCENSORED] $titleText" else titleText

        return newMovieSearchResponse(finalTitle, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    /**
     * 1. HALAMAN UTAMA & KATEGORI
     * (DIUPDATE UNTUK TEMA TAILWIND BARU)
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val items = mutableListOf<HomePageList>()
        
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data == "$mainUrl/") "$mainUrl/page/$page/" else "${request.data}page/$page/"
        }
        
        val document = app.get(url).document

        if (request.name == "Baru Upload" && page == 1) {
            // Mengambil per-section di homepage (Misal: "Trending Jav", "Jav Terbaru")
            document.select("section").forEach { section ->
                val sectionTitle = section.selectFirst(".section-title")?.text() ?: return@forEach
                
                // Abaikan section yang bukan berisi film (seperti daftar Artis atau FAQ)
                if (sectionTitle.contains("Artis", ignoreCase = true) || 
                    sectionTitle.contains("TENTANG", ignoreCase = true) || 
                    sectionTitle.contains("FAQ", ignoreCase = true)) return@forEach
                
                val list = section.select("a.video-card").mapNotNull { it.toSearchResult() }
                if (list.isNotEmpty()) {
                    items.add(HomePageList(sectionTitle, list))
                }
            }
        } else {
            // Untuk pencarian di dalam Kategori/Genre & Halaman 2, 3 dst.
            val elements = document.select("a.video-card")
            val list = elements.mapNotNull { it.toSearchResult() }
            if (list.isNotEmpty()) {
                items.add(HomePageList(request.name, list))
            }
        }

        if (items.isEmpty()) return null
        return newHomePageResponse(items, hasNext = true)
    }

    /**
     * 2. PENCARIAN
     * (DIUPDATE UNTUK TEMA TAILWIND BARU)
     */
    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        // Menggunakan selector baru a.video-card
        return document.select("a.video-card").mapNotNull { it.toSearchResult() }
    }

    /**
     * 3. DETAIL FILM
     * (DIUPDATE UNTUK TEMA TAILWIND BARU)
     */
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val titleText = document.selectFirst("h1.video-info-title")?.text() ?: return null
        val posterUrl = document.selectFirst(".video-info-top img")?.attr("src")
        val plot = document.selectFirst("#tab-synopsis .text-sm p")?.text()
        
        // Ekstraksi Metadata dari Tabel Info Baru
        val tags = mutableListOf<String>()
        var year: Int? = null
        val actors = mutableListOf<ActorData>()

        document.select(".info-row-item").forEach { row ->
            val label = row.selectFirst(".info-label")?.text()?.trim() ?: ""
            val values = row.select(".info-value a").map { it.text().trim() }
            
            when {
                label.contains("Genre", true) -> tags.addAll(values)
                label.contains("Cast", true) -> values.forEach { actors.add(ActorData(Actor(it))) }
                label.contains("Tahun", true) -> year = values.firstOrNull()?.toIntOrNull()
            }
        }

        // Ekstraksi Video Rekomendasi di bawah Carousel
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

    /**
     * 4. EKSTRAKSI LINK VIDEO & SUBTITLE
     * (DIUPDATE UNTUK TEMA TAILWIND BARU)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Cari elemen video utama
        val videoElement = document.selectFirst("#podjavPlayer") ?: return false

        // 1. Ekstrak Link Video langsung dari JSON data-sources di HTML
        val dataSourcesRaw = videoElement.attr("data-sources")
        if (dataSourcesRaw.isNotBlank()) {
            val sources = AppUtils.parseJson<List<VideoSource>>(dataSourcesRaw)
            
            sources.forEach { source ->
                if (source.url.isNotBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = source.label ?: "HD Stream",
                            url = source.url,
                            referer = mainUrl,
                            quality = Qualities.P720.value,
                            type = if (source.type == "m3u8" || source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        }

        // 2. Ekstrak Subtitle langsung dari JSON data-subtitles di HTML
        val dataSubtitlesRaw = videoElement.attr("data-subtitles")
        if (dataSubtitlesRaw.isNotBlank()) {
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

        return true
    }
}

/**
 * DATA CLASS: Untuk parsing JSON dari data-sources dan data-subtitles di player baru Podjav
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
