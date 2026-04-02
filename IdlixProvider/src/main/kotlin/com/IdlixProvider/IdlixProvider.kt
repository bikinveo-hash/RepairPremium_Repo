package com.lagradost.cloudstream3.plugins // Sesuaikan packagenya

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
        "Featured" to "$mainUrl/",
        "Film Terbaru" to "$mainUrl/movie/",
        "Drama Korea" to "$mainUrl/genre/drama-korea/",
        "Anime" to "$mainUrl/genre/anime/",
        "Serial TV" to "$mainUrl/tvseries/"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        // [DIPERBAIKI] Fallback ke string kosong ("") agar Kotlin tidak menganggapnya Nullable
        val title = this.selectFirst("h3 a")?.text() ?: this.selectFirst("img")?.attr("alt") ?: ""
        val href = this.selectFirst("a")?.attr("href") ?: ""
        
        // Memastikan datanya valid
        if (title.isBlank() || href.isBlank()) return null
        
        val posterUrl = this.selectFirst("img")?.attr("src")
        val qualityStr = this.selectFirst(".quality")?.text()

        val isTvSeries = href.contains("/tvseries/") || href.contains("/season/") || this.hasClass("tvshows")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addQuality(qualityStr)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(qualityStr)
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

    // Data Class wadah JSON IDLIX
    data class DooPlayAjaxResponse(
        @JsonProperty("embed_url") val embed_url: String?,
        @JsonProperty("type") val type: String?
    )

    // [DIPERBAIKI] Fungsi mandiri pengganti AppUtils.cryptoJS yang tidak ada di versi ini
    private fun decryptCryptoJS(encryptedJsonStr: String, passphrase: String): String {
        try {
            // Parsing menggunakan regex untuk efisiensi di Android
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
            
            val embedHtml = if (embedEncrypted.contains("<iframe")) {
                embedEncrypted
            } else {
                // Kunci Rahasia IDLIX
                val key = "#dooplay-api-idlixkucom"
                decryptCryptoJS(embedEncrypted, key).replace("\\/", "/")
            }

            val iframeUrl = Jsoup.parse(embedHtml).select("iframe").attr("src").takeIf { it.isNotBlank() } ?: embedHtml

            // Redirect ke Extractor JeniusPlay
            if (iframeUrl.isNotBlank()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
