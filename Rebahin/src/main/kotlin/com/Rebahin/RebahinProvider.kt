package com.Rebahin

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.autos"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay: Long = 800L
    override var sequentialMainPageScrollDelay: Long = 200L

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/country/indonesia/page/" to "Indonesia",
        "$mainUrl/genre/series-indonesia/page/" to "Series Indonesia",
        "$mainUrl/country/south-korea/page/" to "South Korea",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/adventure/page/" to "Adventure",
        "$mainUrl/genre/war/page/" to "War",
        "$mainUrl/genre/adult/page/" to "Adult",
        "$mainUrl/country/philippines/page/" to "Philippines"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data.removeSuffix("page/")
        } else {
            request.data + page
        }

        val document = app.get(url, referer = mainUrl).document
        val home = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }

        if (home.isEmpty() && page == 1) {
            throw ErrorLoadingException("Server membatasi request, silakan refresh kategori ini.")
        }

        return newHomePageResponse(request, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.ml-mask") ?: return null
        val title = a.attr("title")
        val href = fixUrlNull(a.attr("href")) ?: return null
        
        val img = this.selectFirst("img.mli-thumb")
        val posterUrl = fixUrlNull(img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
        
        val qualityStr = this.selectFirst("span.mli-quality")?.text()?.lowercase()
        val qualityResult = when {
            qualityStr == null -> null
            qualityStr.contains("fhd") || qualityStr.contains("hd") -> SearchQuality.HD
            qualityStr.contains("blu") -> SearchQuality.BlueRay
            qualityStr.contains("cam") -> SearchQuality.Cam
            qualityStr.contains("sd") -> SearchQuality.SD
            else -> null
        }
        
        val isTvSeries = href.contains("/series/") || href.contains("season")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = qualityResult
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = qualityResult
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h3[itemprop=name]")?.text()
            ?: return null
        
        val mviCover = document.selectFirst("a.mvi-cover")
        val background = fixUrlNull(mviCover?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\""))
        val playUrl = fixUrlNull(mviCover?.attr("href")) ?: if (url.contains("/series/")) "$url/watch" else "$url/play"

        val poster = fixUrlNull(document.selectFirst("meta[itemprop=image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.mvic-thumb")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.removeSurrounding("'")?.removeSurrounding("\""))

        val plot = document.selectFirst("div.sinopsis-indo, div.desc, div.rt-Text")?.text()?.replace("Nonton Film.*Sub Indo \\| REBAHIN".toRegex(RegexOption.IGNORE_CASE), "")?.trim()
        
        val ratingText = document.selectFirst("span.irank-voters, span.rating, div.btn-danger.averagerate")?.text()?.replace(",", ".")?.trim()
        val parsedScore = ratingText?.toFloatOrNull()?.let { Score.from10(it) }

        var year: Int? = null
        var duration: Int? = null

        document.select("div.mvic-info p").forEach { p ->
            val text = p.text()
            when {
                text.contains("Release Date:") -> {
                    year = p.selectFirst("meta[itemprop=datePublished]")?.attr("content")?.substringBefore("-")?.toIntOrNull()
                        ?: text.substringAfter("Release Date:").trim().substringBefore("-").toIntOrNull()
                }
                text.contains("Duration:") -> {
                    duration = text.replace(Regex("[^0-9]"), "").toIntOrNull()
                }
            }
        }

        val isTvSeries = url.contains("/series/")

        return if (isTvSeries) {
            val watchDocument = app.get(playUrl).document
            val episodes = mutableListOf<Episode>()
            val episodeButtons = watchDocument.select("div#list-eps a.btn-eps")
            
            episodeButtons.forEach { epElem ->
                val epName = epElem.text().trim()
                val base64Iframe = epElem.attr("data-iframe")
                val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()

                if (base64Iframe.isNotBlank()) {
                    episodes.add(newEpisode(base64Iframe) {
                        this.name = epName
                        this.episode = epNum
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.score = parsedScore 
                this.year = year
                this.duration = duration
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.score = parsedScore 
                this.year = year
                this.duration = duration
            }
        }
    }

    /**
     * Otak Dekripsi Native Pembalik Algoritma JuicyCodes (Bypass Obfuscation)
     */
    private fun decryptJuicyCodes(payload: String): String {
        if (payload.length < 3) return ""
        
        // 1. Ambil 3 karakter terakhir sebagai Salt, dan sisanya sebagai Ciphertext
        val salt = payload.takeLast(3)
        val ciphertext = payload.dropLast(3)

        // 2. Ekstrak Nilai Salt Utama (decodeSalt)
        var saltDigits = ""
        for (ch in salt) {
            saltDigits += (ch.code - 100).toString()
        }
        val saltValue = saltDigits.toIntOrNull() ?: return ""

        // 3. Decode Base64 URL-Safe Ciphertext
        val cleanCiphertext = ciphertext.replace("_", "+").replace("-", "/")
        val decodedBytes = Base64.decode(cleanCiphertext, Base64.DEFAULT)
        val decodedString = String(decodedBytes, Charsets.UTF_8)

        // 4. Petakan string simbol ke deretan angka berbasis index symbolMap
        val symbolMap = listOf("`", "%", "-", "+", "*", "$", "!", "_", "^", "=")
        var digitString = ""
        for (ch in decodedString) {
            val idx = symbolMap.indexOf(ch.toString())
            if (idx != -1) {
                digitString += idx.toString()
            }
        }

        // 5. Potong tiap 4 digit angka dan kalkulasi charCode teks aslinya
        val sb = java.lang.StringBuilder()
        val chunks = digitString.chunked(4)
        for (chunk in chunks) {
            if (chunk.length == 4) {
                val chunkInt = chunk.toIntOrNull() ?: continue
                val charCode = (chunkInt % 1000) - saltValue
                sb.append(charCode.toChar())
            }
        }
        return sb.toString()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val urlToExtract = mutableListOf<String>()

        // Mengurai alamat URL target awal
        if (!data.startsWith("http")) {
            try {
                val decodedUrl = String(Base64.decode(data, Base64.DEFAULT))
                if (decodedUrl.startsWith("http")) {
                    urlToExtract.add(decodedUrl)
                }
            } catch (e: Exception) {}
        } else {
            val document = app.get(data, referer = mainUrl).document
            
            document.select("[data-iframe]").forEach { element ->
                val encodedUrl = element.attr("data-iframe")
                if (encodedUrl.isNotBlank()) {
                    try {
                        val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                        if (decodedUrl.startsWith("http")) {
                            urlToExtract.add(decodedUrl)
                        }
                    } catch (e: Exception) {}
                }
            }
            
            document.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src")) ?: fixUrlNull(iframe.attr("data-src"))
                if (src != null && !src.contains("googleusercontent.com") && src.isNotBlank()) {
                    urlToExtract.add(src)
                }
            }
        }

        urlToExtract.distinct().forEach { rawUrl ->
            var targetUrl = rawUrl
            
            // Bypass halaman gateway iembed secara instan murni via Base64 lokal
            if (rawUrl.contains("/iembed/?source=")) {
                val base64 = rawUrl.substringAfter("source=")
                try {
                    targetUrl = String(Base64.decode(base64, Base64.DEFAULT))
                } catch(e: Exception) {}
            }

            if (targetUrl.contains("abyssplayer.com/")) {
                targetUrl = targetUrl.replace("abyssplayer.com/", "abysscdn.com/?v=")
            }

            // Jalankan loadExtractor Cloudstream standar terlebih dahulu
            val isExtractorLoaded = loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)

            // JIKA TIDAK TERSEDIA DI EXTRACTOR BAWAAN, JALANKAN PROSES NATIVE JUICYCODES BYPASS
            if (!isExtractorLoaded) {
                try {
                    // Step 1: Hit HTTP GET ke server embed & panen tiket kuki sesi utama
                    val response = app.get(targetUrl, referer = mainUrl)
                    val responseHtml = response.text
                    val setCookies = response.headers.values("Set-Cookie")
                    val cookieHeader = setCookies.map { it.substringBefore(";") }.joinToString("; ")

                    // Step 2: Ambil payload acak _juicycodes dan variabel metadata jwData
                    val payload = Regex("""_juicycodes\("([^"]+)"\)""").find(responseHtml)?.groupValues?.get(1) ?: ""
                    val juicyDataStr = Regex("""window\.juicyData\s*=\s*(\{.*?\});""").find(responseHtml)?.groupValues?.get(1) ?: ""

                    val token = Regex(""""token"\s*:\s*"([^"]+)"""").find(juicyDataStr)?.groupValues?.get(1) ?: ""
                    val pingRoute = Regex(""""ping"\s*:\s*"([^"]+)"""").find(juicyDataStr)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""

                    // Step 3: Luluskan Validasi Sesi API Handshake via POST /ping (Membuka gembok CDN)
                    if (pingRoute.isNotBlank() && token.isNotBlank()) {
                        val domain = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: targetUrl
                        val pingUrl = domain.removeSuffix("/") + pingRoute
                        val randomPingId = java.util.UUID.randomUUID().toString().replace("-", "")

                        app.post(
                            pingUrl,
                            headers = mapOf(
                                "Cookie"     to cookieHeader,
                                "Referer"    to targetUrl,
                                "User-Agent" to USER_AGENT
                            ),
                            json = mapOf(
                                "_token" to token,
                                "__type" to "dawn",
                                "pingID" to randomPingId
                            )
                        )
                    }

                    // Step 4: Jalankan dekripsi native dan saring tautan video final (.m3u8 / .mp4)
                    if (payload.isNotBlank()) {
                        val decryptedConfig = decryptJuicyCodes(payload)
                        val videoLinks = Regex("""(https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)""").findAll(decryptedConfig)

                        videoLinks.forEach { match ->
                            val finalVideoUrl = match.groupValues[1]
                            val isM3u8 = finalVideoUrl.contains(".m3u8") || finalVideoUrl.contains("m3u8")
                            val domain = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: targetUrl

                            callback.invoke(
                                ExtractorLink(
                                    source = "Rebahin VIP",
                                    name = "Rebahin VIP " + if (isM3u8) "(HLS)" else "(MP4)",
                                    url = finalVideoUrl,
                                    referer = "$domain/",
                                    quality = Qualities.Unknown.value,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Cookie"     to cookieHeader,
                                        "Referer"    to targetUrl
                                    )
                                )
                            )
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        return true
    }
}
