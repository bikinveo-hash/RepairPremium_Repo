package com.michat88

import com.michat88.IdlixProvider.ResponseSource
import com.michat88.IdlixProvider.Tracks
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack

// Kelas ini bertugas sebagai jembatan untuk mengekstrak video dari server Jeniusplay
class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    // Fungsi utama untuk memproses URL, mencari link video (m3u8), dan mencari subtitle
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val hash = url.split("/").last().substringAfter("data=")

        // Mengirim request POST untuk mendapatkan sumber video langsung dari server
        val m3uLink = app.post(
            url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
            data = mapOf("hash" to hash, "r" to "$referer"),
            referer = referer,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsed<ResponseSource>().videoSource.replace(".txt",".m3u8")

        // Memecah dan menghasilkan link M3U8 agar bisa diputar di aplikasi
        generateM3u8(name,
            m3uLink,
            mainUrl,
        ).forEach(callback)

        // Mencari script di halaman web yang berisi data subtitle
        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                // Mengekstrak dan membongkar (unpack) data JSON yang berisi daftar subtitle
                val subData =
                    getAndUnpack(script.data()).substringAfter("\"tracks\":[").substringBefore("],")
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

    // Fungsi pembantu untuk menerjemahkan label bahasa dari web menjadi format bahasa Cloudstream
    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str
                .contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }
}
