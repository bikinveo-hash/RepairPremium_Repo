package com.Sukawibu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

class SukawibuProvider : MainAPI() {
    override var name = "Sukawibu"
    override var mainUrl = "https://sukawibu.com"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    
    // Karena ini konten 18+, kita set tipenya ke NSFW
    override val supportedTypes = setOf(TvType.NSFW) 

    override val mainPage = mainPageOf(
        "$mainUrl/?filter=latest" to "Latest Videos",
        "$mainUrl/?filter=popular" to "Popular Videos",
        "$mainUrl/category/jav-sub-indo/" to "JAV Sub Indo",
        "$mainUrl/category/bokep-indo/" to "Bokep Indo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?filter=")) {
                request.data.replace("?", "page/$page/?")
            } else {
                "${request.data}page/$page/"
            }
        }
        val document = app.get(url).document
        val home = document.select("article.loop-video").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.loop-video").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val posterUrl = this.attr("data-main-thumb") 
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.video-description div.desc p")?.text()
        val tags = document.select("div.tags-list a").map { it.text() }
        val actors = document.select("div#video-actors a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.actors = actors.map { ActorData(Actor(it)) } 
        }
    }

    // ==========================================
    // BAGIAN 4: PEMUTARAN VIDEO (LOAD LINKS) - VERSI SUPER ROBUST
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // 1. Kumpulkan semua sumber Iframe ke dalam wadah List (Nama Server, URL)
        val iframeSources = mutableListOf<Pair<String, String>>()
        
        // Coba cari dari tombol Server (Cara Pertama)
        val serverButtons = document.select("div.server-nav button")
        serverButtons.forEach { btn ->
            val serverName = btn.text().trim()
            val onClickAttr = btn.attr("onclick")
            val matchResult = """changeServer\('([^']+)'""".toRegex().find(onClickAttr)
            if (matchResult != null) {
                iframeSources.add(serverName to matchResult.groupValues[1])
            }
        }
        
        // JIKA tombol tidak ada, cari langsung tag Iframe di halaman (Cara Kedua - Fallback)
        if (iframeSources.isEmpty()) {
            document.select("iframe").forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && (src.contains("minochinos") || src.contains("masukestin") || src.contains("hgcloud"))) {
                    iframeSources.add("Server ${index + 1}" to src)
                }
            }
        }

        // Wadah pencegah subtitle ganda
        val addedSubtitles = mutableSetOf<String>()
        
        // 2. Eksekusi semua iframe yang ditemukan
        iframeSources.forEach { (serverName, url) ->
            var iframeUrl = url
            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            }

            // Gunakan Try-Catch agar jika satu server gagal, server lain tetap dieksekusi
            try {
                val iframeHtml = app.get(iframeUrl).text
                
                // Jika script dikemas, unpack. Jika tidak, gunakan HTML aslinya.
                val unpackedJs = JsUnpacker(iframeHtml).unpack() ?: iframeHtml
                
                // Menangkap Video
                val m3u8Regex = """(["'])([^"']+\.m3u8[^"']*)\1""".toRegex()
                val m3u8Match = m3u8Regex.find(unpackedJs)
                
                if (m3u8Match != null) {
                    val extractedVideo = m3u8Match.groupValues[2]
                    val videoLink = if (extractedVideo.startsWith("http")) {
                        extractedVideo
                    } else {
                        URI(iframeUrl).resolve(extractedVideo).toString()
                    }
                    
                    // Memanggil newExtractorLink sesuai ExtractorApi.kt
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = if (serverName.isNotEmpty()) "Sukawibu - $serverName" else "Sukawibu Server",
                            url = videoLink,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = iframeUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

                // Menangkap Subtitle
                val vttRegex = """(["'])([^"']+\.vtt[^"']*)\1""".toRegex()
                vttRegex.findAll(unpackedJs).forEach { match ->
                    val extractedSub = match.groupValues[2]
                    val subUrl = if (extractedSub.startsWith("http")) {
                        extractedSub
                    } else {
                        URI(iframeUrl).resolve(extractedSub).toString()
                    }
                    
                    if (addedSubtitles.add(subUrl)) {
                        subtitleCallback.invoke(
                            SubtitleFile(
                                "Indonesia", 
                                subUrl
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Abaikan error pada server ini, lanjutkan loop ke server berikutnya
                e.printStackTrace()
            }
        }
        
        return true
    }
}
