package com.RiveStream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class PrimeSrcHelper {

    companion object {
        // Kumpulan Salt dari server
        private val SALT_ARRAY = listOf(
            "4Z7lUo", "gwIVSMD", "PLmz2elE2v", "Z4OFV0", "SZ6RZq6Zc", "zhJEFYxrz8", "FOm7b0", "axHS3q4KDq", "o9zuXQ", "4Aebt",
            "wgjjWwKKx", "rY4VIxqSN", "kfjbnSo", "2DyrFA1M", "YUixDM9B", "JQvgEj0", "mcuFx6JIek", "eoTKe26gL", "qaI9EVO1rB", "0xl33btZL",
            "1fszuAU", "a7jnHzst6P", "wQuJkX", "cBNhTJlEOf", "KNcFWhDvgT", "XipDGjST", "PCZJlbHoyt", "2AYnMZkqd", "HIpJh", "KH0C3iztrG",
            "W81hjts92", "rJhAT", "NON7LKoMQ", "NMdY3nsKzI", "t4En5v", "Qq5cOQ9H", "Y9nwrp", "VX5FYVfsf", "cE5SJG", "x1vj1",
            "HegbLe", "zJ3nmt4OA", "gt7rxW57dq", "clIE9b", "jyJ9g", "B5jXjMCSx", "cOzZBZTV", "FTXGy", "Dfh1q1", "ny9jqZ2POI",
            "X2NnMn", "MBtoyD", "qz4Ilys7wB", "68lbOMye", "3YUJnmxp", "1fv5Imona", "PlfvvXD7mA", "ZarKfHCaPR", "owORnX", "dQP1YU",
            "dVdkx", "qgiK0E", "cx9wQ", "5F9bGa", "7UjkKrp", "Yvhrj", "wYXez5Dg3", "pG4GMU", "MwMAu", "rFRD5wlM"
        )

        /**
         * Menggunakan UInt (Unsigned Integer) Kotlin agar bitwise operations (shl, shr)
         * perilakunya sama persis dengan JavaScript (>>> 0).
         */
        private fun executeInnerHash(input: String): String {
            var t = 0U
            for (n in input.indices) {
                val r = input[n].code.toUInt()
                t = (r + (t shl 6) + (t shl 16) - t)
                val i = (t shl (n % 5)) or (t shr (32 - (n % 5)))
                val rRot = ((r shl (n % 7)) or (r shr (8 - (n % 7)))) and 0xFFU
                t = t xor (i xor rRot)
                t = t + ((t shr 11) xor (t shl 3))
            }
            t = t xor (t shr 15)
            t = ((t and 0xFFFFU) * 49842U) + (((t shr 16) * 49842U) shl 16)
            t = t xor (t shr 13)
            t = ((t and 0xFFFFU) * 40503U) + (((t shr 16) * 40503U) shl 16)
            t = t xor (t shr 16)
            return t.toString(16).padStart(8, '0')
        }

        private fun executeOuterHash(input: String): String {
            var n = 3735928559U xor input.length.toUInt()
            for (e in input.indices) {
                val r = input[e].code.toUInt()
                val salt = (131U * e.toUInt() + 89U) xor (r shl (e % 5))
                n = ((n shl 7) or (n shr 25)) xor (r xor (salt and 0xFFU))
                n = ((n and 0xFFFFU) * 60205U) + (((n shr 16) * 60205U) shl 16)
                n = n xor (n shr 11)
            }
            n = n xor (n shr 15)
            n = ((n and 0xFFFFU) * 49842U) + (((n shr 16) * 49842U) shl 16)
            n = n xor (n shr 13)
            n = ((n and 0xFFFFU) * 40503U) + (((n shr 16) * 40503U) shl 16)
            n = n xor (n shr 16)
            n = ((n and 0xFFFFU) * 10196U) + (((n shr 16) * 10196U) shl 16)
            n = n xor (n shr 15)
            return n.toString(16).padStart(8, '0')
        }

        fun generateDynamicSecretKey(idStr: String): String {
            val numId = idStr.toIntOrNull() ?: 0
            val tWord = SALT_ARRAY[numId % SALT_ARRAY.size]
            val insertIdx = (numId % idStr.length) / 2
            val combinedStr = idStr.substring(0, insertIdx) + tWord + idStr.substring(insertIdx)
            
            val inner = executeInnerHash(combinedStr)
            val outer = executeOuterHash(inner)
            
            return Base64.encodeToString(outer.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }

        suspend fun getServers(
            tmdbId: String, 
            isMovie: Boolean, 
            season: Int? = null, 
            episode: Int? = null
        ): List<PrimeSrcServer>? {
            // JALUR PINTAS API: Menembak langsung ke API tersembunyi tanpa render iframe
            val type = if (isMovie) "movie" else "tv"
            var apiUrl = "https://primesrc.me/api/v1/s?tmdb=$tmdbId&type=$type"
            
            if (!isMovie && season != null && episode != null) {
                apiUrl += "&s=$season&e=$episode"
            }

            val response = app.get(apiUrl, referer = "https://www.rivestream.app/").text
            val parsed = tryParseJson<PrimeSrcServerResponse>(response)
            return parsed?.servers
        }

        suspend fun processBackendSource(
            source: BackendSource,
            providerName: String,
            displayName: String,
            mainUrl: String,
            service: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            var linksFound = 0
            val streamUrl = source.url ?: return false
            val qualityName = source.quality ?: "Unknown"

            // SUPER STRICT M3U8 CHECKER (Mencegah Player Crash karena salah injeksi HTML)
            val isDirectM3u8 = streamUrl.contains(".m3u8") || 
                (source.format?.lowercase() == "hls" && !streamUrl.contains("/e/") && !streamUrl.contains("/embed/"))

            try {
                if (isDirectM3u8) {
                    val link = newExtractorLink(
                        source = providerName,
                        name = displayName,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = getQualityFromName(qualityName)
                        this.referer = "$mainUrl/"
                        this.headers = mapOf("Origin" to mainUrl, "Accept" to "*/*")
                    }
                    callback(link)
                    synchronized(this) { linksFound++ }
                } else {
                    val targetReferer = if (service == "flowcast") "https://123movienow.cc/" else "$mainUrl/"
                    
                    // Lempar ke ExtractorApi (seperti TpeadExtractor, Streamwish, dll)
                    val isExtractorFound = loadExtractor(streamUrl, referer = targetReferer, subtitleCallback, callback)
                    
                    if (isExtractorFound) {
                        synchronized(this) { linksFound++ }
                    } else if (!streamUrl.contains("/e/") && !streamUrl.contains("/embed/")) {
                        // Fallback jika tidak ada extractor dan bentuknya link mp4 langsung
                        val link = newExtractorLink(source = providerName, name = displayName, url = streamUrl) {
                            this.quality = getQualityFromName(qualityName)
                            this.referer = targetReferer
                        }
                        callback(link)
                        synchronized(this) { linksFound++ }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return linksFound > 0
        }
    }
}

// ===== DATA CLASSES MODEL =====
data class BackendServicesResponse(@JsonProperty("data") val data: List<String>?)
data class BackendFetchResponse(@JsonProperty("data") val data: BackendData?)
data class BackendData(
    @JsonProperty("sources") val sources: List<BackendSource>?,
    @JsonProperty("captions") val captions: List<BackendCaption>? = null
)
data class BackendSource(
    @JsonProperty("quality") val quality: String?, 
    @JsonProperty("url") val url: String?,
    @JsonProperty("source") val source: String?,
    @JsonProperty("format") val format: String?
)
data class BackendCaption(
    @JsonProperty("label") val label: String?,
    @JsonProperty("file") val file: String?
)
data class PrimeSrcServerResponse(@JsonProperty("servers") val servers: List<PrimeSrcServer>?)
data class PrimeSrcServer(
    @JsonProperty("name") val name: String?,
    @JsonProperty("key") val key: String?
)
