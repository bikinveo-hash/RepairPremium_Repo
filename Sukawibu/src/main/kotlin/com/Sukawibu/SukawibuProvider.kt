package com.Sukawibu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SukawibuProvider : MainAPI() {
    override var name = "Sukawibu"
    override var mainUrl = "https://sukawibu.com"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    
    // Karena ini konten 18+, kita set tipenya ke NSFW
    override val supportedTypes = setOf(TvType.NSFW) 

    // Mendefinisikan tab/kategori yang akan muncul di halaman beranda aplikasi
    override val mainPage = mainPageOf(
        "$mainUrl/?filter=latest" to "Latest Videos",
        "$mainUrl/?filter=popular" to "Popular Videos",
        "$mainUrl/category/jav-sub-indo/" to "JAV Sub Indo",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo"
    )

    // ==========================================
    // BAGIAN 1: HALAMAN UTAMA (HOMEPAGE)
    // ==========================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Jika URL mengandung parameter filter, sisipkan nomor page sebelumnya
            if (request.data.contains("?filter=")) {
                request.data.replace("?", "page/$page/?")
            } else {
                "${request.data}page/$page/"
            }
        }

        val document = app.get(url).document
        
        // Menggunakan article.loop-video agar berlaku di beranda maupun di pencarian
        val home = document.select("article.loop-video").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    // ==========================================
    // BAGIAN 2: PENCARIAN (SEARCH)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        
        // Menggunakan article.loop-video sesuai perbaikan kita
        return document.select("article.loop-video").mapNotNull {
            it.toSearchResult()
        }
    }

    // ==========================================
    // FUNGSI BANTUAN EKSTRAK ITEM VIDEO
    // ==========================================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val posterUrl = this.attr("data-main-thumb") 
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==========================================
    // BAGIAN 3: DETAIL ANIME/VIDEO (LOAD)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.video-description div.desc p")?.text()
        val tags = document.select("div.tags-list a").map { it.text() }
        val actors = document.select("div#video-actors a").map { it.text() }

        // Parameter terakhir (url) dikirim sebagai 'data' ke loadLinks
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            addActors(actors)
        }
    }

    // ==========================================
    // BAGIAN 4: PEMUTARAN VIDEO (LOAD LINKS)
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' berisi URL halaman detail
        val document = app.get(data).document
        val serverButtons = document.select("div.server-nav button")
        
        serverButtons.forEach { btn ->
            val onClickAttr = btn.attr("onclick")
            val regex = """changeServer\('([^']+)'""".toRegex()
            val matchResult = regex.find(onClickAttr)
            
            if (matchResult != null) {
                var iframeUrl = matchResult.groupValues[1]
                
                // Pastikan URL memiliki awalan http/https
                if (iframeUrl.startsWith("//")) {
                    iframeUrl = "https:$iframeUrl"
                }

                // 1. Kunjungi halaman iframe pemutar
                val iframeHtml = app.get(iframeUrl).text
                
                // 2. Bongkar Javascript yang dikunci (JsUnpacker)
                val unpackedJs = JsUnpacker(iframeHtml).unpack()
                
                if (unpackedJs != null) {
                    // 3. Menangkap URL video (.m3u8)
                    val m3u8Regex = """(["'])([^"']+\.m3u8[^"']*)\1""".toRegex()
                    val m3u8Match = m3u8Regex.find(unpackedJs)
                    
                    if (m3u8Match != null) {
                        val videoLink = m3u8Match.groupValues[2]
                        
                        // Kirim link video ke pemutar aplikasi
                        callback.invoke(
                            ExtractorLink(
                                source = this.name,
                                name = "Sukawibu Server",
                                url = videoLink,
                                referer = iframeUrl, // Referer penting agar tidak error 403 Forbidden
                                quality = Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }

                    // 4. Menangkap URL Subtitle (.vtt)
                    val vttRegex = """(["'])([^"']+\.vtt[^"']*)\1""".toRegex()
                    vttRegex.findAll(unpackedJs).forEach { match ->
                        val subUrl = match.groupValues[2]
                        
                        // Kirim link subtitle ke pemutar aplikasi
                        subtitleCallback.invoke(
                            SubtitleFile(
                                "Indonesia", 
                                subUrl
                            )
                        )
                    }
                }
            }
        }
        
        return true
    }
}
