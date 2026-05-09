package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.*

class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

    // HEADERS PENTING (JANGAN DIUBAH)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
    )

    // ==============================
    // 1. MAIN PAGE CONFIGURATION (UPDATED)
    // ==============================
    override val mainPage = mainPageOf(
        // Kategori Utama
        "$mainUrl/trending/" to "Trending",
        "$mainUrl/ratings/" to "Top Rated",
        "$mainUrl/genres/porn-movies/" to "Porn Movies",
        "$mainUrl/xxxclips/" to "XXX Clips",
        
        // Kategori Tambahan
        "$mainUrl/genre/18-teens/" to "18+ Teens",
        "$mainUrl/genre/all-girl/" to "All Girl",
        "$mainUrl/genre/all-sex/" to "All Sex",
        "$mainUrl/genre/asian/" to "Asian",
        "$mainUrl/genre/bbc/" to "BBC",
        "$mainUrl/genre/bbw/" to "BBW",
        "$mainUrl/genre/big-boobs/" to "Big Boobs",
        "$mainUrl/genre/big-butt/" to "Big Butt",
        "$mainUrl/genre/big-cocks/" to "Big Cocks",
        "$mainUrl/genre/blondes/" to "Blondes",
        "$mainUrl/genre/blowjobs/" to "Blowjobs",
        "$mainUrl/genre/cuckolds/" to "Cuckolds",
        "$mainUrl/genre/cumshots/" to "Cumshots",
        "$mainUrl/genre/deep-throat/" to "Deep Throat",
        "$mainUrl/genre/facials/" to "Facials"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        val document = app.get(url, headers = headers).document
        
        val items = document.select("article.item").mapNotNull {
            toSearchResult(it)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h3 > a") 
            ?: element.selectFirst("div.title > a")
            ?: element.selectFirst("div.image > a")
            ?: return null

        val title = titleElement.text().trim()
        val url = fixUrl(titleElement.attr("href"))
    
        val imgElement = element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        val fixedQuery = query.replace(" ", "+")
        val url = "$mainUrl/?s=$fixedQuery"
        
        val document = app.get(url, headers = headers).document
        
        return document.select("div.result-item, article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==============================
    // 3. LOAD DETAIL
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        
        val imgElement = document.selectFirst("div.poster img")
        val poster = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        val tags = document.select(".sgeneros a, .persons a[href*='/genre/']").map { it.text() }
        
        val year = document.selectFirst(".textco a[href*='/year/']")?.text()?.toIntOrNull()
        
        val actors = document.select("#cast .persons a[href*='/pornstar/']").map { 
            ActorData(Actor(it.text(), null)) 
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.actors = actors
        }
    }

    // ==============================
    // 4. LOAD LINKS
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        val potentialLinks = mutableListOf<String>()

        document.select("#playeroptionsul li a").forEach { link ->
            val href = fixUrl(link.attr("href"))
            if (href.startsWith("http")) potentialLinks.add(href)
        }

        document.select("#playcontainer iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.startsWith("http")) potentialLinks.add(src)
        }

        fun getServerPriority(url: String): Int {
            return when {
                url.contains("dood") || url.contains("playmogo") -> 0
                url.contains("luluvid") -> 1
                url.contains("rpmplay") -> 2 
                url.contains("upns") -> 3
                url.contains("easyvidplayer") -> 4
                url.contains("embedseek") -> 5
                url.contains("seekplayer") -> 6
                url.contains("streamtape") -> 7
                url.contains("voe.sx") -> 8
                url.contains("vidhide") -> 9
                url.contains("filemoon") -> 10
                url.contains("mixdrop") -> 11
                url.contains("streamsb") -> 12
                else -> 20
            }
        }

        if (potentialLinks.isNotEmpty()) {
            val sortedLinks = potentialLinks.sortedBy { getServerPriority(it) }

            coroutineScope {
                sortedLinks.map { link ->
                    launch(Dispatchers.IO) {
                        try {
                            var finalUrl = link
                            
                            // Bypass Doodstream Clone (Playmogo)
                            if (finalUrl.contains("playmogo.com")) {
                                finalUrl = finalUrl.replace("playmogo.com", "dood.to")
                            }
                            
                            // Route link ke Custom Extractor yang sudah kita buat (memakai getSafeUrl agar tidak crash)
                            if (finalUrl.contains("luluvid.com")) {
                                Luluvid().getSafeUrl(finalUrl, data, subtitleCallback, callback)
                            } else if (finalUrl.contains("rpmplay.online")) {
                                RpmPlay().getSafeUrl(finalUrl, data, subtitleCallback, callback)
                            } else if (finalUrl.contains("upns.online")) {
                                UpnsOnline().getSafeUrl(finalUrl, data, subtitleCallback, callback)
                            } else if (finalUrl.contains("easyvidplayer.com")) {
                                EasyVidPlayer().getSafeUrl(finalUrl, data, subtitleCallback, callback)
                            } else if (finalUrl.contains("embedseek.online")) {
                                EmbedSeek().getSafeUrl(finalUrl, data, subtitleCallback, callback)
                            } else if (finalUrl.contains("seekplayer.vip")) {
                                SeekPlayer().getSafeUrl(finalUrl, data, subtitleCallback, callback)
                            } else if (finalUrl.contains("streamtape.com")) {
                                StreamtapeCustom().getSafeUrl(finalUrl, data, subtitleCallback, callback)
                            } else {
                                // Eksekutor bawaan Cloudstream untuk server-server lain
                                loadExtractor(finalUrl, data, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            // Abaikan error di satu server agar server lain tetap dicoba
                        }
                    }
                }
            }
            return true
        }

        return false
    }
}

// ==========================================
// KUMPULAN CUSTOM EXTRACTOR
// ==========================================

class Luluvid : ExtractorApi() {
    override var name = "Luluvid"
    override var mainUrl = "https://luluvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer).text
        val m3u8Regex = Regex("file\"?\\s*:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"")
        var match = m3u8Regex.find(response)
        
        if (match == null) {
            val unpacked = getAndUnpack(response)
            match = m3u8Regex.find(unpacked)
        }

        if (match != null) {
            val m3u8Url = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

class StreamtapeCustom : ExtractorApi() {
    override var name = "Streamtape"
    override var mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url).text
        val part1 = Regex("""innerHTML\s*=\s*['"](//[^'"]+get_video[^'"]+)['"]""").find(response)?.groupValues?.get(1)
        val part2Regex = Regex("""\+\s*(?:\([^)]+\)\s*\+\s*)?['"]([^'"]+)['"]""")
        val part2Matches = part2Regex.findAll(response).toList()
        val part2 = if (part2Matches.isNotEmpty()) part2Matches.last().groupValues[1] else ""

        if (part1 != null) {
            var videoUrl = "https:$part1$part2"
            if (!videoUrl.contains("&stream=1")) {
                videoUrl += "&stream=1"
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

// Pasukan Kloningan Vidhide (Menggunakan VidhideExtractor dengan var)
class RpmPlay : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "RpmPlay"
    override var mainUrl = "https://my.rpmplay.online"
}

class UpnsOnline : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "UpnsOnline"
    override var mainUrl = "https://my.upns.online"
}

class EasyVidPlayer : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "EasyVidPlayer"
    override var mainUrl = "https://p.easyvidplayer.com"
}

class EmbedSeek : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "EmbedSeek"
    override var mainUrl = "https://my.embedseek.online"
}

class SeekPlayer : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "SeekPlayer"
    override var mainUrl = "https://vip.seekplayer.vip"
}
