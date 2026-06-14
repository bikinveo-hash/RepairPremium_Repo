package com.RiveStream

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// =========================================================================
// 1. STREAMTAPE EXTRACTOR (TpeadExtractor) - ANTI-HONEYPOT ENGINE FINAL
// =========================================================================
class TpeadExtractor : ExtractorApi() {
    override val name = "Streamtape"
    override val mainUrl = "https://tpead.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Mengunduh isi dokumen player utama dengan taktik penyamaran mirror klien
            val response = app.get(
                url, 
                referer = "https://streamta.site/",
                headers = mapOf(
                    "Origin" to "https://streamta.site",
                    "User-Agent" to com.lagradost.cloudstream3.USER_AGENT
                )
            ).text
            
            // PERTAHANAN ANTI-HONEYPOT MUTLAK: Mengunci ekspresi reguler langsung ke ID elemen asli 'botlink' 
            // guna menghancurkan jebakan 'ideoolink' dan 'robotlink' yang sengaja dibuat server
            val botlinkRegex = Regex("""document\.getElementById\(['"]botlink['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(['"]([^'"]+)['"]\)\.substring\((\d+)\)""")
            val match = botlinkRegex.find(response)
            
            if (match != null) {
                val baseUrl = match.groupValues[1]   // Menangkap string pangkalan dasar '//streamta.sit'
                val rawToken = match.groupValues[2]  // Menangkap gugusan token sandi dinamis klien
                val cutIdx = match.groupValues[3].toIntOrNull() ?: 0 // Menangkap index batasan pemotong biner (.substring)
                
                if (cutIdx < rawToken.length) {
                    val token = rawToken.substring(cutIdx) // Menjalankan fungsi pemotongan string otorisasi asli
                    // Merakit link langsung menuju kluster penyimpanan objek CDN dengan parameter direct stream
                    val finalUrl = "https:$baseUrl$token&stream=1"
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Streamtape Premium (Direct CDN)",
                            url = finalUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://streamta.site/"
                            this.quality = Qualities.P2160.value // Mengunci status video ke kasta tertinggi 4K Ultra HD
                            
                            // AMUNISI HEADER EMAS: Mengirim stempel validasi browser agar Ceph RadosGW mengembalikan status 206 Partial Content
                            this.headers = mapOf(
                                "Origin" to "https://streamta.site",
                                "Referer" to "https://streamta.site/",
                                "Connection" to "keep-alive",
                                "Accept" to "*/*",
                                "Sec-Fetch-Dest" to "video",
                                "Sec-Fetch-Mode" to "cors",
                                "Sec-Fetch-Site" to "cross-site"
                            )
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// =========================================================================
// 2. VOE EXTRACTOR (VoeExtractor)
// =========================================================================
class VoeExtractor : ExtractorApi() {
    override val name = "VOE"
    override val mainUrl = "https://voe.un"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer).text
            
            // Mencari variabel manifes berkas video langsung di dalam variabel halaman hulu
            val videoMatch = Regex("""["']file["']\s*:\s*["'](https?://[^"']+)["']""").find(response)
            var directLink = videoMatch?.groupValues?.get(1)

            // Skenario Penyelamat: Mendekripsi payload biner Base64 jika target disembunyikan dalam lapisan skrip obf
            if (directLink == null) {
                val b64Match = Regex("""base64\s*,\s*([a-zA-Z0-9+/={}\s]+)""").find(response)
                b64Match?.groupValues?.get(1)?.let { b64Text ->
                    try {
                        val decoded = String(Base64.decode(b64Text.trim(), Base64.DEFAULT), Charsets.UTF_8)
                        val linkMatch = Regex("""(https?://[^\s"']+)""").find(decoded)
                        directLink = linkMatch?.groupValues?.get(1)
                    } catch (e: Exception) { 
                        e.printStackTrace() 
                    }
                }
            }

            directLink?.let { link ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = "VOE Engine",
                        url = link,
                        type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
