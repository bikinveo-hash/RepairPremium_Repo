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
                                    name = source.label ?: "Server Bawaan (Podjav)",
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
                    if (sub.src.isNotBlank()) {
                        subtitleCallback.invoke(SubtitleFile(lang = sub.label ?: "Indonesia", url = sub.src))
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
