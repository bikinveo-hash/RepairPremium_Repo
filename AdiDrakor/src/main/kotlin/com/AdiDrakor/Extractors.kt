package com.AdiDrakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack

// ==============================
// UPDATED JENIUSPLAY EXTRACTOR
// ==============================

class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val hash = url.split("/").last().substringAfter("data=")

        // 1. Mengambil sumber video langsung dari server dan memperbaiki ekstensi file
        val response = app.post(
            url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
            data = mapOf("hash" to hash, "r" to "$referer"),
            referer = referer,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<ResponseSource>()

        val m3uLink = response?.videoSource?.replace(".txt", ".m3u8")

        // 2. Jika link M3U8 ditemukan, pecah menjadi berbagai resolusi (multi-quality) menggunakan M3u8Helper
        if (m3uLink != null) {
            generateM3u8(
                name,
                m3uLink,
                mainUrl
            ).forEach(callback)
        }

        // 3. Mencari script di halaman web yang berisi data subtitle tersembunyi
        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                
                // Membongkar (unpack) data JSON yang berisi daftar subtitle
                val subData = getAndUnpack(script.data())
                    .substringAfter("\"tracks\":[")
                    .substringBefore("],")
                
                AppUtils.tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                    // Mengirimkan data subtitle yang ditemukan ke pemutar video
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            getLanguage(subtitle.label ?: ""),
                            subtitle.file
                        )
                    )
                }
            }
        }
    }

    // Fungsi pembantu untuk menerjemahkan label bahasa
    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }

    // Model data (POJO) - Menggunakan tipe nullable (?) agar tidak error jika ada data yang kosong dari server
    data class ResponseSource(
        @JsonProperty("hls") val hls: Boolean?,
        @JsonProperty("videoSource") val videoSource: String?,
        @JsonProperty("securedLink") val securedLink: String?,
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
    )
}
