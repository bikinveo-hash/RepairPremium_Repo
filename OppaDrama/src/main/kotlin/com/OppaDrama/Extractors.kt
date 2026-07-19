package com.OppaDrama

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import android.util.Base64

/**
 * 1. EarnVids / Smoothpre Extractor
 * Alias backend extractor: smoothpre.com menggunakan arsitektur yang sama dengan vidhidepro.com.
 */
class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

/**
 * 2. BuzzServer Extractor (Local Plugin Overrider)
 * Memperbaiki kegagalan pembacaan hx-redirect statis huruf kecil pada core HubCloud.kt 
 * dengan menerapkan metode multi-headers fallback (hx-redirect, HX-Redirect, location, Location).
 */
class BuzzServer : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            // Bersihkan URL dari silsilah parameter /download ganda jika terlempar dari core
            val cleanUrl = if (url.endsWith("/download")) url.substringBeforeLast("/download") else url
            val host = URI(cleanUrl).let { "${it.scheme}://${it.host}" }

            val page = app.get(cleanUrl, referer = referer)
            // NB: `documentLarge` dipertahankan sesuai kode asli. Tidak ditemukan bukti di
            // ExtractorApi.kt/MainAPI.kt yang menunjukkan properti ini bermasalah, jadi tidak diubah.
            val qualityText = page.documentLarge.selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)

            // Ambil respons headers dengan mematikan auto-redirect
            val response = app.get(
                "$cleanUrl/download",
                referer = cleanUrl,
                allowRedirects = false,
            )

            // Header client HTTP umumnya sudah case-insensitive, tapi tetap dijaga untuk kompatibilitas
            val redirectUrl = response.headers["hx-redirect"]
                ?: response.headers["location"]

            if (!redirectUrl.isNullOrBlank()) {
                // FIX UTAMA: buzzheavier.com kerap mengembalikan hx-redirect berupa PATH RELATIF
                // (contoh: "/dl/abc123"), bukan URL absolut. Jika langsung dipakai sebagai `url`
                // pada ExtractorLink, pemutar akan gagal karena bukan URL valid.
                // Ini kemungkinan besar penyebab error "kadang jalan, kadang tidak" -
                // tergantung apakah node/CDN yang merespons memberi path relatif atau absolut.
                val finalUrl = if (redirectUrl.startsWith("http")) {
                    redirectUrl
                } else {
                    host + (if (redirectUrl.startsWith("/")) redirectUrl else "/$redirectUrl")
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "BuzzServer Direct",
                        url = finalUrl,
                    ) {
                        this.quality = quality
                        this.referer = "$mainUrl/"
                    }
                )
            } else {
                // Response code disertakan untuk membedakan: 403/503 (anti-bot/Cloudflare)
                // vs 200 dengan body yang berubah struktur (situs update markup).
                Log.w(
                    "BuzzServer",
                    "Bypass Failed: no redirect header found (code=${response.code}). " +
                        "Kemungkinan proteksi anti-bot mengintersep sebelum token redirect diberikan, " +
                        "atau host domain sudah berpindah dari $mainUrl."
                )
            }
        } catch (e: Exception) {
            Log.e("BuzzServer", "Failed to resolve $url: ${e.message}")
        }
    }
}

/**
 * 3. Emturbovid Extractor
 *
 * AKAR MASALAH (root cause) yang ditemukan:
 * File ExtractorApi.kt inti Cloudstream versi terbaru TERNYATA sudah memiliki
 * `com.lagradost.cloudstream3.extractors.EmturbovidExtractor` bawaan (built-in),
 * yang otomatis terdaftar di `extractorApis` global saat aplikasi start.
 *
 * Plugin ini mendaftarkan LAGI kelas `EmturbovidExtractor` versi lokal (nama sama,
 * package beda: com.OppaDrama) lewat `registerExtractorAPI(EmturbovidExtractor())`.
 * Karena `loadExtractor()` mengiterasi daftar `extractorApis` secara REVERSE
 * ("Iterate in reverse order so the new registered ExtractorApi takes priority"),
 * versi lokal plugin ini SELALU dicoba lebih dulu dan menutupi (shadow) versi bawaan.
 *
 * Ini masalah klasik: begitu tim Cloudstream memperbaiki extractor bawaan mereka
 * mengikuti perubahan situs emturbovid.com, perbaikan itu TIDAK PERNAH kepakai
 * karena salinan lokal yang sudah usang selalu menang duluan. Ini sangat cocok
 * dengan gejala "kadang error" - tergantung apakah situs sedang memakai struktur
 * lama (masih cocok dengan regex lokal) atau struktur baru (sudah diperbaiki di
 * core, tapi core tidak pernah kepanggil).
 *
 * FIX YANG DIREKOMENDASIKAN (dipakai di bawah): jangan reimplementasi ulang logic
 * ekstraksi. Ikuti pola yang sudah benar dipakai `Smoothpre : VidHidePro()` -
 * cukup delegasikan/subclass ke extractor inti yang sudah dirawat oleh maintainer
 * Cloudstream, supaya otomatis ikut ter-update setiap kali core diperbarui.
 *
 * Alternatif paling aman: HAPUS SAJA class ini + baris
 * `registerExtractorAPI(EmturbovidExtractor())` di Plugin.kt, karena versi bawaan
 * sudah otomatis aktif tanpa perlu didaftarkan manual sama sekali.
 */
class Emturbovid : com.lagradost.cloudstream3.extractors.EmturbovidExtractor() {
    // Override hanya jika OppaDrama memang memakai domain mirror yang berbeda
    // dari default core. Jika domainnya identik dengan core, class ini bahkan
    // tidak perlu didaftarkan sama sekali (lihat catatan di Plugin.kt).
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
}

/**
 * 4. Abyss / Hydrax Extractor
 * Mengurai kemurnian data Base64 "datas" dari player-v2 core bundle untuk mengambil otentikasi multi-token.
 *
 * AKAR MASALAH (root cause) yang ditemukan:
 * Respons endpoint `/api/player/v2` milik Hydrax/Abyss SUDAH BERUBAH SKEMA.
 * Berdasarkan sample respons terkini, setiap objek di `sources[]` TIDAK LAGI
 * berisi field "file" (URL video langsung). Field yang ada sekarang hanya
 * metadata kualitas: "label", "res_id", "size", "codec", "status", "type".
 * Data class `AbyssSource` yang lama (hanya punya file/label/type) sehingga
 * `source.file` SELALU null untuk skema baru -> tidak ada link yang pernah
 * berhasil di-emit dari server yang sudah pakai skema baru.
 *
 * Respons juga kini menyertakan "sessionId" di level root, dan (menurut riset
 * komunitas reverse-engineering publik) Hydrax sudah beralih ke pengiriman
 * video dalam bentuk SEGMENT TERENKRIPSI (AES-CTR, path di-double-base64,
 * kunci diturunkan dari kombinasi session/slug/md5_id/user_id) alih-alih URL
 * file utuh - ini pada dasarnya lapisan proteksi anti-download/anti-piracy.
 *
 * Karena tidak semua node/CDN Hydrax migrasi bersamaan, sebagian sesi/host
 * kadang masih membalas dengan "file" langsung (skema lama) dan kadang sudah
 * memakai skema baru bertoken - inilah yang membuat gejalanya "kadang error,
 * kadang jalan".
 *
 * PENTING: Saya TIDAK menambahkan implementasi untuk mendekripsi/menyusun
 * segment token tersebut, karena itu berarti membangun mekanisme untuk
 * menembus proteksi anti-piracy milik pihak ketiga - di luar apa yang bisa
 * saya bantu. Perbaikan di bawah hanya menyamakan skema data terbaru supaya
 * (a) source yang MASIH memberi "file" langsung tetap tertangkap dengan benar,
 * dan (b) ketika server sudah full token-based, extractor gagal secara jelas
 * dan ter-log (bukan diam-diam mengembalikan list kosong tanpa jejak).
 */
class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyss.to"
    override val requiresReferer = true

    private data class AbyssSource(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("res_id") val resId: Int?,
        @JsonProperty("status") val status: Boolean?
    )

    private data class AbyssResponse(
        @JsonProperty("sources") val sources: List<AbyssSource>?,
        @JsonProperty("domain") val domain: String?,
        @JsonProperty("sessionId") val sessionId: String?
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "http://45.11.57.192/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text
            val datasRaw = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            
            var slug = Regex("[?&]v=([^&#]+)").find(url)?.groupValues?.getOrNull(1)?.trim()
            var md5Id = ""
            var userId = ""

            if (!datasRaw.isNullOrBlank()) {
                val decodedDatas = String(Base64.decode(datasRaw, Base64.DEFAULT), Charsets.UTF_8)
                
                // Menggunakan format String escaping standar Kotlin untuk akurasi pembacaan Regex
                if (slug.isNullOrBlank()) {
                    slug = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim()
                }
                md5Id = Regex("\"md5_id\"\\s*:\\s*\"?(\\d+)\"?").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim() ?: ""
                userId = Regex("\"user_id\"\\s*:\\s*\"?(\\d+)\"?").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }

            if (slug.isNullOrBlank()) {
                slug = Regex("""(?:v|slug)\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)?.trim()
            }
            if (userId.isNullOrBlank()) {
                userId = Regex("""userID\s*:\s*["']?(\d+)["']?""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }

            if (slug.isNullOrBlank()) return

            val host = URI(url).host
            val apiUrl = "https://$host/api/player/v2"

            val apiResponse = app.post(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                    "Referer" to url,
                    "Origin" to "https://$host",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf<String, String>(
                    "slug" to slug,
                    "md5_id" to md5Id,
                    "user_id" to userId
                )
            ).parsedSafe<AbyssResponse>()

            if (apiResponse?.sources.isNullOrEmpty()) {
                Log.w("Abyss", "Tidak ada 'sources' pada respons player/v2 untuk $url")
            } else if (apiResponse?.sources?.all { it.file.isNullOrBlank() } == true) {
                Log.w(
                    "Abyss",
                    "Server ini sudah migrasi ke skema segment/token terenkripsi " +
                        "(sessionId=${apiResponse.sessionId}, domain=${apiResponse.domain}); " +
                        "field 'file' langsung sudah tidak tersedia sehingga tidak ada link yang " +
                        "bisa diambil tanpa reverse-engineering lebih lanjut terhadap protokol baru."
                )
            }

            apiResponse?.sources?.forEach { source ->
                val videoUrl = source.file
                if (!videoUrl.isNullOrBlank()) {
                    val labelText = source.label ?: "Unknown"
                    val isM3u8 = videoUrl.contains(".m3u8")
                    
                    val qualityValue = when {
                        labelText.contains("1080") -> Qualities.P1080.value
                        labelText.contains("720") -> Qualities.P720.value
                        labelText.contains("480") -> Qualities.P480.value
                        labelText.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $labelText",
                            url = videoUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://$host/"
                            this.quality = qualityValue
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }
}

/**
 * 5. Minochinos / VidHide Obfuscated Extractor
 *
 * AKAR MASALAH (root cause) yang ditemukan:
 * `getAndUnpack()` hanya bekerja jika HTML mengandung blok JS terobfuskasi format
 * Dean Edwards packer (`eval(function(p,a,c,k,e,...))`). Jika regex packer TIDAK
 * ketemu, `getAndUnpack()` diam-diam mengembalikan HTML ASLI tanpa error apa pun
 * (lihat ExtractorApi.kt: `JsUnpacker(packedText).unpack() ?: string`). Karena
 * minochinos.com kemungkinan tidak SELALU memakai packer ini untuk setiap video/
 * server (bisa berbeda tergantung versi player yang dipakai saat itu), maka pada
 * kasus tanpa packer, regex m3u8/mp4 di bawahnya mencari pola URL di HTML mentah
 * yang sebenarnya menyimpan URL di dalam variabel JS biasa (mis. `sources: [{file: "..."}]`),
 * sehingga regex gagal cocok dan `streamUrl` menjadi null. Karena seluruh proses
 * dibungkus `catch (_: Exception) {}` tanpa logging, kegagalan ini senyap dan
 * terlihat seperti "kadang error" tanpa jejak diagnostik.
 *
 * FIX: menambah pola pencarian fallback untuk format `sources`/`file:` JS biasa
 * (dipakai luas oleh banyak varian player VidHide), dan mengganti silent-catch
 * dengan logging supaya kegagalan bisa dilacak actual root cause-nya di masa depan
 * jika situs berubah lagi.
 */
class MinochinosExtractor : ExtractorApi() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "$mainUrl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text

            // getAndUnpack() no-op jika tidak ada blok eval-packer; pakai html asli
            // sebagai ruang pencarian tambahan supaya tidak kehilangan kecocokan.
            val unpackedHtml = getAndUnpack(html)
            val searchSpaces = listOf(unpackedHtml, html).distinct()

            var streamUrl: String? = null
            for (space in searchSpaces) {
                streamUrl = Regex("""https?://[^\s"'`<>]+?\.m3u8[^\s"'`<>]*""").find(space)?.value
                    ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(space)?.groupValues?.getOrNull(1)
                    // Fallback: format umum player non-packed, mis. sources:[{file:"..."}]
                    ?: Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(space)?.groupValues?.getOrNull(1)
                if (!streamUrl.isNullOrBlank()) break
            }

            if (!streamUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                Log.w("Minochinos", "Stream URL tidak ditemukan untuk $url (packer tidak terdeteksi & pola fallback tidak cocok - kemungkinan struktur player berubah lagi).")
            }
        } catch (e: Exception) {
            Log.e("Minochinos", "Gagal resolve $url: ${e.message}")
        }
    }
}
