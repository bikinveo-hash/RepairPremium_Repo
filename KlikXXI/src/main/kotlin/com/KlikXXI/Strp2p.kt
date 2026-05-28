package com.KlikXXI

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extractor untuk server video Strp2p (klikxxi.strp2p.site).
 *
 * Alur kerja (dikonfirmasi dari hasil debug Python):
 *  1. Ambil Video ID dari URL iframe  →  /e/{videoId}
 *  2. GET /api/v1/video?id={videoId}&w=360&h=800&r=klikxxi.me
 *     dengan header Referer & Accept yang wajib.
 *  3. Respons berupa string Hexadecimal (application/octet-stream).
 *  4. Dekripsi AES-128-CBC / PKCS5Padding dengan key & IV statis.
 *  5. Parse JSON → key utama adalah "source" (URL M3U8 absolut).
 *     Fallback: "hlsVideoTiktok" (path relatif, perlu CDN base domain).
 *
 * Kriptografi yang dikonfirmasi:
 *   Key : "kiemtienmua911ca"   (16 byte UTF-8)
 *   IV  : "1234567890oiuytr"   (16 byte UTF-8)
 */
class Strp2p : ExtractorApi() {
    override val name            = "Strp2p"
    override val mainUrl         = "https://klikxxi.strp2p.site"
    override val requiresReferer = true

    // ── Konstanta Kriptografi ────────────────────────────────────────────────
    private val AES_KEY = "kiemtienmua911ca"   // 16 karakter = 128-bit
    private val AES_IV  = "1234567890oiuytr"   // 16 karakter

    // ── Konstanta Request ────────────────────────────────────────────────────
    private val API_W   = "360"
    private val API_H   = "800"
    private val API_R   = "klikxxi.me"

    // ── CDN base untuk path relatif hlsVideoTiktok ───────────────────────────
    // Nilai cfDomain dari JSON respons: "corporateoperations.sbs"
    // Subdomain CDN yang ditemukan di field "cf": "soq.corporateoperations.sbs"
    private val CDN_BASE = "https://soq.corporateoperations.sbs"

    // ── Tag untuk Logcat ─────────────────────────────────────────────────────
    private val TAG = "Strp2p"

    // ─────────────────────────────────────────────────────────────────────────
    //  getUrl — Entry point utama (dipanggil dari KlikXXI.kt)
    //  PERBAIKAN: Semua langkah di-log agar kegagalan terlihat di logcat.
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "─────────── STRP2P START ───────────")
        Log.d(TAG, "Input URL : $url")
        Log.d(TAG, "Referer   : $referer")

        // ── Langkah 1: Ekstrak Video ID ──────────────────────────────────────
        // Contoh input : https://klikxxi.strp2p.site/e/q5sc8o
        // Hasil yang diharapkan: q5sc8o
        val videoId = url
            .substringAfter("/e/", missingDelimiterValue = "")
            .substringBefore("?")
            .substringBefore("/")
            .trim()

        if (videoId.isBlank()) {
            Log.e(TAG, "❌ GAGAL Langkah 1: Video ID kosong. URL tidak mengandung '/e/{id}'. URL=$url")
            return
        }
        Log.d(TAG, "✅ Langkah 1 OK — Video ID: '$videoId'")

        // ── Langkah 2: Bangun URL API ─────────────────────────────────────────
        val apiUrl = "$mainUrl/api/v1/video?id=$videoId&w=$API_W&h=$API_H&r=$API_R"
        Log.d(TAG, "✅ Langkah 2 OK — API URL: $apiUrl")

        // ── Langkah 3: HTTP GET ke endpoint API ──────────────────────────────
        val rawResponse = try {
            app.get(
                url     = apiUrl,
                headers = mapOf(
                    "Referer"    to "$mainUrl/",
                    "Accept"     to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ GAGAL Langkah 3: HTTP request error. ${e.message}")
            e.printStackTrace()
            return
        }

        val httpCode    = rawResponse.code
        val hexResponse = rawResponse.text.trim()

        Log.d(TAG, "✅ Langkah 3 OK — HTTP $httpCode, panjang respons: ${hexResponse.length} karakter")

        if (httpCode != 200) {
            Log.e(TAG, "❌ GAGAL Langkah 3: HTTP status bukan 200. Status=$httpCode")
            return
        }

        if (hexResponse.isBlank()) {
            Log.e(TAG, "❌ GAGAL Langkah 3: Respons kosong/blank.")
            return
        }

        // ── Langkah 4: Validasi format Hex ───────────────────────────────────
        // PERBAIKAN: Gunakan all{} bukan matches(Regex(...)) agar tidak gagal
        // karena karakter whitespace tersembunyi atau newline di akhir.
        val isValidHex = hexResponse.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (!isValidHex) {
            Log.e(TAG, "❌ GAGAL Langkah 4: Respons bukan Hex valid. 80 char pertama: '${hexResponse.take(80)}'")
            return
        }
        if (hexResponse.length % 2 != 0) {
            Log.e(TAG, "❌ GAGAL Langkah 4: Panjang Hex ganjil (${hexResponse.length}), tidak bisa di-decode.")
            return
        }
        Log.d(TAG, "✅ Langkah 4 OK — Hex valid (${hexResponse.length} char = ${hexResponse.length / 2} bytes)")

        // ── Langkah 5: Dekripsi AES-128-CBC ──────────────────────────────────
        val rawJson = try {
            decryptAesCbc(
                hexString = hexResponse,
                key       = AES_KEY.toByteArray(Charsets.UTF_8),
                iv        = AES_IV.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ GAGAL Langkah 5: Dekripsi AES gagal. ${e.message}")
            e.printStackTrace()
            return
        }
        Log.d(TAG, "✅ Langkah 5 OK — Dekripsi berhasil, panjang JSON: ${rawJson.length}")
        Log.d(TAG, "   JSON preview (200 char): ${rawJson.take(200)}")

        // ── Langkah 6: Ekstrak URL dari JSON ─────────────────────────────────
        var linkFound = false

        // PRIORITAS 1: field "source" → URL absolut M3U8 (IP langsung)
        // Contoh nilai: "https:\/\/45.156.158.184\/v4\/.../master.m3u8?v=..."
        val sourceUrl = Regex(""""source"\s*:\s*"([^"]+)"""")
            .find(rawJson)
            ?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { it.startsWith("http") && it.contains(".m3u8") }

        if (sourceUrl != null) {
            Log.d(TAG, "✅ Langkah 6 OK — source URL: $sourceUrl")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "Strp2p",
                    url    = sourceUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            linkFound = true
        } else {
            Log.w(TAG, "⚠️  Langkah 6: field 'source' tidak ditemukan atau bukan URL M3U8 valid.")
        }

        // PRIORITAS 2: field "hlsVideoTiktok" → path relatif, gabung dengan CDN base
        // Contoh nilai: "/hls/gRbL4ZlMf8lo6fMc3yECcw/5c/wybszhqp/8gtjjh/tt/master.m3u8"
        val tiktokPath = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""")
            .find(rawJson)
            ?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { it.contains(".m3u8") }

        if (tiktokPath != null) {
            val tiktokUrl = when {
                tiktokPath.startsWith("http") -> tiktokPath
                tiktokPath.startsWith("/")    -> "$CDN_BASE$tiktokPath"
                else                          -> "$CDN_BASE/$tiktokPath"
            }
            Log.d(TAG, "✅ Langkah 6 OK — hlsVideoTiktok URL: $tiktokUrl")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "Strp2p [TikTok CDN]",
                    url    = tiktokUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            linkFound = true
        } else {
            Log.w(TAG, "⚠️  Langkah 6: field 'hlsVideoTiktok' tidak ditemukan atau bukan path M3U8 valid.")
        }

        if (!linkFound) {
            Log.e(TAG, "❌ Langkah 6 GAGAL TOTAL: Tidak ada link video yang berhasil diekstrak dari JSON.")
            Log.e(TAG, "   JSON lengkap:\n$rawJson")
        }

        Log.d(TAG, "─────────── STRP2P END (linkFound=$linkFound) ───────────")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPER FUNCTIONS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hapus backslash escape dari JSON string (JSON slash escaping).
     * Contoh: "https:\/\/example.com\/path" → "https://example.com/path"
     */
    private fun String.unescapeJsonSlashes(): String =
        this.replace("\\/", "/")

    /**
     * Konversi Hexadecimal String ke ByteArray.
     * Panjang string HARUS genap; sudah divalidasi di atas.
     */
    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex string harus panjang genap: $length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Dekripsi AES-128-CBC dengan PKCS5Padding.
     *
     * @param hexString  Ciphertext dalam format Hexadecimal.
     * @param key        16-byte key (AES-128).
     * @param iv         16-byte Initialization Vector.
     * @return           Plaintext hasil dekripsi (UTF-8).
     */
    private fun decryptAesCbc(hexString: String, key: ByteArray, iv: ByteArray): String {
        val encryptedBytes = hexString.decodeHex()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }
}
