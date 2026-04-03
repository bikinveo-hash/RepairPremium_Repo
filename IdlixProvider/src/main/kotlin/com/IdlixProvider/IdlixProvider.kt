package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://tv12.idlixku.com"
    override var name = "IDLIX"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured",
        "$mainUrl/movie/" to "Film Terbaru",
        "$mainUrl/genre/drama-korea/" to "Drama Korea",
        "$mainUrl/genre/anime/" to "Anime",
        "$mainUrl/tvseries/" to "Serial TV"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 a")?.text() ?: this.selectFirst("img")?.attr("alt")
        var href = this.selectFirst("a")?.attr("href")
        
        if (title.isNullOrBlank() || href.isNullOrBlank()) return null
        
        href = fixUrl(href)
        val posterUrl = this.selectFirst("img")?.attr("src")
        val qualityStr = this.selectFirst(".quality")?.text()

        val isTvSeries = href.contains("/tvseries/") || href.contains("/season/") || this.hasClass("tvshows")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                qualityStr?.let { addQuality(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                qualityStr?.let { addQuality(it) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        
        val document = app.get(url).document
        val elements = document.select("article.item")
        val homePageList = elements.mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".sheader .data h1")?.text() ?: return null
        val poster = document.selectFirst(".sheader .poster img")?.attr("src")
        val plot = document.selectFirst("#info p")?.text()
        val isTvSeries = url.contains("/tvseries/")

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
          
            document.select(".se-c").forEach { seasonEl ->
                val seasonNum = seasonEl.selectFirst(".se-q .se-t")?.text()?.toIntOrNull()
                seasonEl.select(".episodios li").forEach { epEl ->
                    val epNum = epEl.selectFirst(".numerando")?.text()?.substringAfter("-")?.trim()?.toIntOrNull()
                    val epTitle = epEl.selectFirst(".episodiotitle a")?.text()
        
                    val epLink = epEl.selectFirst(".episodiotitle a")?.attr("href")
                    if (epLink != null) {
                        episodes.add(
                            newEpisode(epLink) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                            }
                        )
                    }
                }
            }
     
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    data class DooPlayAjaxResponse(
        @JsonProperty("embed_url") val embed_url: String?,
        @JsonProperty("type") val type: String?
    )

    private fun decryptCryptoJS(encryptedJsonStr: String, passphrase: String): String {
        try {
            val ct = Regex(""""ct"\s*:\s*"([^"]+)"""").find(encryptedJsonStr)?.groupValues?.get(1) ?: return ""
            val saltHex = Regex(""""s"\s*:\s*"([^"]+)"""").find(encryptedJsonStr)?.groupValues?.get(1) ?: return ""
            val ivHex = Regex(""""iv"\s*:\s*"([^"]+)"""").find(encryptedJsonStr)?.groupValues?.get(1)

            val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
           
            val ctBytes = base64DecodeArray(ct)

            var concatenatedHashes = byteArrayOf()
            var currentHash = byteArrayOf()
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            val md5 = MessageDigest.getInstance("MD5")

            while (concatenatedHashes.size < 48) {
                md5.reset()
    
                md5.update(currentHash)
                md5.update(passBytes)
                md5.update(salt)
                currentHash = md5.digest()
                concatenatedHashes += currentHash
            }

            val key = concatenatedHashes.copyOfRange(0, 32)
            val derivedIv = if (ivHex != null) {
                ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                concatenatedHashes.copyOfRange(32, 48)
            }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(derivedIv))

            return String(cipher.doFinal(ctBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val htmlRaw = document.html() // Ambil HTML mentah untuk menyedot Kunci Dinamis
        
        // [FITUR BARU] Menyedot password dinamis dari HTML secara otomatis!
        // IDLIX menaruhnya di variabel "key":"\x3d..." atau langsung diacak.
        var dynamicKey = Regex(""""key"\s*:\s*"([^"]+)"""").find(htmlRaw)?.groupValues?.get(1)
        
        if (dynamicKey.isNullOrBlank()) {
            // Jika luput, cari rentetan \x mentah secara brutal di HTML
            val doubleSlashMatch = Regex("""(?:\\\\x[0-9a-fA-F]{2}){10,}""").find(htmlRaw)?.value
            if (doubleSlashMatch != null) {
                dynamicKey = doubleSlashMatch.replace("\\\\", "\\")
            } else {
                dynamicKey = Regex("""(?:\\x[0-9a-fA-F]{2}){10,}""").find(htmlRaw)?.value
            }
        }
        
        val finalKey = dynamicKey ?: ""
        
        val servers = document.select("ul#playeroptionsul li.dooplay_player_option")

        servers.forEach { element ->
            val postId = element.attr("data-post")
            val type = element.attr("data-type")
            val nume = element.attr("data-nume")

            if (nume.equals("trailer", ignoreCase = true)) return@forEach

            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val ajaxResponse = app.post(
                url = ajaxUrl,
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to nume,
                    "type" to type
                ),
                referer = data,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<DooPlayAjaxResponse>()

            val embedEncrypted = ajaxResponse?.embed_url ?: return@forEach
            
            val embedHtml = if (embedEncrypted.contains("<iframe") || embedEncrypted.startsWith("http")) {
                embedEncrypted
            } else {
                // Gunakan kunci dinamis yang sudah berhasil disedot!
                val decrypted = decryptCryptoJS(embedEncrypted, finalKey)
                // [PENTING] Bersihkan backslash dan Jebakan Tanda Kutip (") dari hasil!
                decrypted.replace("\\/", "/").trim('"')
            }

            val iframeUrl = Jsoup.parse(embedHtml).select("iframe").attr("src").takeIf { it.isNotBlank() } ?: embedHtml

            if (iframeUrl.isNotBlank()) {
                if (iframeUrl.contains("jenius", ignoreCase = true) || 
                    iframeUrl.contains("player", ignoreCase = true) || 
                    iframeUrl.contains("idlix", ignoreCase = true)) {
                    try {
                        IdlixExtractor().getUrl(iframeUrl, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
