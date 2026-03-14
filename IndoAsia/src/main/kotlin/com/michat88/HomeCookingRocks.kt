package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HomeCookingRocks : MainAPI() {
    
    override var name = "Home Cooking Rocks"
    override var mainUrl = "https://homecookingrocks.com"
    // DITAMBAHKAN LABEL NSFW DI SINI 👇
    override var supportedTypes = setOf(TvType.NSFW) 
    override var lang = "id"
    override val hasMainPage = true
    
    override val mainPage = mainPageOf(
        "$mainUrl/category/asia-m/" to "Asia",
        "$mainUrl/category/vivamax/" to "VivaMax",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/kelas-bintang/" to "Kelas Bintang",
        "$mainUrl/category/semi-barat/" to "Barat Punya",
        "$mainUrl/category/bokep-indo/" to "Indo Punya",
        "$mainUrl/category/bokep-vietnam/" to "Vietnam Punya"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val elements = document.select("#gmr-main-load article")
        
        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            
            // 👇 PERBAIKAN: Logika deteksi gambar Lazy Loading
            val imgElement = element.selectFirst("img")
            val image = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("src")
            
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = image
            }
        }
       
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("#gmr-main-load article").mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val url = titleElement.attr("href")
            
            // 👇 PERBAIKAN: Diterapkan juga di search agar seragam dan aman
            val imgElement = element.selectFirst("img")
            val image = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: imgElement?.attr("src")
            
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".entry-content p").joinToString("\n") { it.text() }.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun:) a")?.text()?.toIntOrNull()
        val ratingString = document.selectFirst(".gmr-meta-rating span[itemprop=ratingValue]")?.text()
        val tags = document.select(".gmr-moviedata:contains(Genre:) a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(ratingString)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Bypass Jsoup document, ambil string murni agar super ringan
        val html = app.get(data).text

        val tabsBlockMatch = Regex("""class=["'][^"']*muvipro-player-tabs[^"']*["'][^>]*>(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL).find(html)
        
        val rawServerUrls = if (tabsBlockMatch != null) {
            Regex("""href=["']([^"']+)["']""").findAll(tabsBlockMatch.groupValues[1])
                .map { fixUrl(it.groupValues[1]) }
                .distinct()
                .toList()
        } else {
            listOf(data)
        }

        // Prioritaskan link dari halaman ini di urutan pertama (agar tidak perlu GET ulang html-nya)
        val sortedUrls = rawServerUrls.sortedBy { url ->
            if (url == data) 0 else 1
        }

        // Proses Paralel Asinkronus Kecepatan Tinggi!
        coroutineScope {
            // 👇 PERBAIKAN: Gunakan forEachIndexed untuk mendapatkan nomor urut server
            sortedUrls.forEachIndexed { index, serverUrl ->
                launch(Dispatchers.IO) {
                    try {
                        // 1. Ambil HTML (Sertakan referer agar aman dari block server)
                        val serverHtml = if (serverUrl == data) html else app.get(serverUrl, referer = data).text
                        
                        // 2. REGEX SAPU BERSIH: Kebal huruf besar/kecil & kebal baris baru (newline)!
                        val iframeRegex = Regex(
                            """<iframe[^>]+src=["']([^"']+)["']""", 
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL) 
                        )
                        val allIframes = iframeRegex.findAll(serverHtml).map { it.groupValues[1] }.toList()

                        // 3. Filter pintar: Prioritaskan iframe yang mengandung kata kunci server kita
                        var iframeSrc = allIframes.firstOrNull { src ->
                            src.contains("pyrox", ignoreCase = true) || 
                            src.contains("4meplayer", ignoreCase = true) || 
                            src.contains("imaxstreams", ignoreCase = true)
                        } ?: allIframes.firstOrNull()

                        if (iframeSrc != null) {
                            
                            // 👇 PERBAIKAN: Pastikan link iframe menggunakan protokol lengkap (https:)
                            if (iframeSrc.startsWith("//")) {
                                iframeSrc = "https:$iframeSrc"
                            }
                            
                            // Penamaan dinamis untuk ExtractorLink
                            val serverName = "Server ${index + 1}"

                            // ==========================================
                            // SERVER: Pyrox
                            // ==========================================
                            if (iframeSrc.contains("embedpyrox") || iframeSrc.contains("pyrox")) {
                                val iframeId = iframeSrc.substringAfterLast("/")
                                val host = java.net.URI(iframeSrc).host
                                val apiUrl = "https://$host/player/index.php?data=$iframeId&do=getVideo"

                                val response = app.post(
                                    url = apiUrl,
                                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to iframeSrc),
                                    data = mapOf("hash" to iframeId, "r" to data)
                                ).text

                                val m3u8Url = Regex("""(https:\\?\/\\?\/[^"]+(?:master\.txt|\.m3u8))""")
                                    .find(response)?.groupValues?.get(1)?.replace("\\/", "/")

                                if (m3u8Url != null) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "$serverName (Pyrox)",
                                            url = m3u8Url,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = iframeSrc
                                            this.quality = Qualities.Unknown.value
                                            this.headers = mapOf(
                                                "Origin" to "https://$host",
                                                "Accept" to "*/*"
                                            )
                                        }
                                    )
                                }
                            } 
                            // ==========================================
                            // SERVER: 4MePlayer (Bypass AES Terupdate)
                            // ==========================================
                            else if (iframeSrc.contains("4meplayer")) {
                                val videoId = iframeSrc.substringAfterLast("#")
                                if (videoId.isNotEmpty() && videoId != iframeSrc) {
                                    val host = java.net.URI(iframeSrc).host
                                    
                                    // 👇 PERBAIKAN: Parameter API baru dengan referer dan resolusi
                                    val apiUrl = "https://$host/api/v1/video?id=$videoId&w=360&h=800&r=$serverUrl"
                                        
                                    try {
                                        // 👇 PERBAIKAN: Menyisipkan User-Agent Android
                                        val hexResponse = app.get(
                                            apiUrl, 
                                            referer = iframeSrc,
                                            headers = mapOf(
                                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                                            )
                                        ).text.trim()
                                        
                                        if (hexResponse.isNotEmpty() && hexResponse.matches(Regex("^[0-9a-fA-F]+$"))) {
                                            val secretKey = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
                                            val ivBytes = ByteArray(16)
                                            for (i in 0..8) ivBytes[i] = i.toByte() 
                                            for (i in 9..15) ivBytes[i] = 32.toByte() 
                                            
                                            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                                            val secretKeySpec = SecretKeySpec(secretKey, "AES")
                                            val ivParameterSpec = IvParameterSpec(ivBytes)
                                            
                                            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
                                            
                                            val decodedHex = hexResponse.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                            val decryptedBytes = cipher.doFinal(decodedHex)
                                            val decryptedText = String(decryptedBytes, Charsets.UTF_8)
                                            
                                            // 👇 PERBAIKAN: Regex baru agar dapat menagkap single & double quote
                                            val m3u8Regex = """["']([^"']+\.m3u8[^"']*)["']""".toRegex()
                                            val match = m3u8Regex.find(decryptedText)
                                            
                                            if (match != null) {
                                                var m3u8Url = match.groupValues[1].replace("\\/", "/")
                                                if (m3u8Url.startsWith("/")) {
                                                    m3u8Url = "https://$host$m3u8Url"
                                                }
                                                
                                                callback.invoke(
                                                    newExtractorLink(
                                                        source = name,
                                                        name = "$serverName (4MePlayer)",
                                                        url = m3u8Url,
                                                        type = ExtractorLinkType.M3U8
                                                    ) {
                                                        this.referer = iframeSrc
                                                        this.quality = Qualities.Unknown.value
                                                    }
                                                )
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Abaikan error pada endpoint ini
                                    }
                                }
                            }
                            // ==========================================
                            // SERVER: ImaxStreams (Bypass Packed JS Terupdate)
                            // ==========================================
                            else if (iframeSrc.contains("imaxstreams", ignoreCase = true)) {
                                // Tambahkan Referer agar tidak di-block oleh server
                                val iframeHtml = app.get(iframeSrc, referer = serverUrl).text
                                
                                // Bongkar JS Packer (eval(function...))
                                val unpackedText = getAndUnpack(iframeHtml)
                                
                                // 👇 PERBAIKAN: Regex baru yang mewajibkan struktur URL lengkap (http/https)
                                val m3u8Regex = """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
                                val match = m3u8Regex.find(unpackedText) ?: m3u8Regex.find(iframeHtml)
                                
                                if (match != null) {
                                    val m3u8Url = match.groupValues[1].replace("\\/", "/")
                                    
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "$serverName (ImaxStreams)",
                                            url = m3u8Url,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = iframeSrc
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                            }
                            // ==========================================
                            // DEFAULT: Ekstraktor Bawaan CloudStream
                            // ==========================================
                            else {
                                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return true
    }
}
