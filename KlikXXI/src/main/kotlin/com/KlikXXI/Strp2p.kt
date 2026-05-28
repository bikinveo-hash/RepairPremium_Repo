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
 * Extractor untuk server video Strp2p.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  DOMAIN YANG DITANGANI (dikonfirmasi dari debug live):          │
 * │   • klikxxi.strp2p.site  — domain utama Strp2p                 │
 * │   • klikxxi.upns.one     — domain alias, API & crypto identik  │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  FORMAT URL IFRAME (dua format aktif):                          │
 * │   Format A: https://klikxxi.strp2p.site/e/q5sc8o   (path /e/) │
 * │   Format B: https://klikxxi.strp2p.site/#q5sc8o    (hash #)   │
 * │   Format C: https://klikxxi.upns.one/#ikaftn        (hash #)   │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  ALUR KERJA:                                                    │
 * │   1. Ekstrak video ID dari URL → regex [/#]([A-Za-z0-9]{4,})$  │
 * │   2. GET {baseDomain}/api/v1/video?id={id}&w=360&h=800&r=...   │
 * │      Header wajib: Referer = {baseDomain}/                     │
 * │   3. Respons: Hex string (application/octet-stream)            │
 * │   4. Dekripsi AES-128-CBC / PKCS5Padding                       │
 * │      Key: "kiemtienmua911ca"  IV: "1234567890oiuytr"           │
 * │   5. Parse JSON → ekstrak "source" dan "hlsVideoTiktok"        │
 * └─────────────────────────────────────────────────────────────────┘
 */
class Strp2p : ExtractorApi() {
    override val name            = "Strp2p"
    override val mainUrl         = "https://klikxxi.strp2p.site"
    override val requiresReferer = true

    // ── Konstanta Kriptografi (AES-128-CBC, dikonfirmasi dari debug) ─────────
    private val AES_KEY = "kiemtienmua911ca"   // 16 bytes → AES-128
    private val AES_IV  = "1234567890oiuytr"   // 16 bytes

    // ── Parameter API ────────────────────────────────────────────────────────
    private val API_W = "360"
    private val API_H = "800"
    private val API_R = "klikxxi.me"

    // ── CDN base untuk path relatif dari field "hlsVideoTiktok" ─────────────
    // Dikonfirmasi dari JSON: "soq.corporateoperations.sbs"
    private val CDN_BASE = "https://soq.corporateoperations.sbs"

    // ── User-Agent yang dikonfirmasi berhasil di debug ───────────────────────
    private val UA = "Mozilla/5.0 (Linux; Android 10; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val TAG = "Strp2p"

    // ─────────────────────────────────────────────────────────────────────────
    //  getUrl — Entry point (dipanggil via getSafeUrl() dari KlikXXI.kt)
    // ─────────────────────────────────────────────────────────────────────────
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "══════════ STRP2P START ══════════")
        Log.d(TAG, "Input URL : $url")
        Log.d(TAG, "Referer   : $referer")

        // ── Langkah 1: Deteksi base domain dari URL iframe ───────────────────
        // PENTING: harus pakai domain dari URL input (bukan this.mainUrl),
        // karena upns.one dan strp2p.site punya API endpoint masing-masing.
        // Contoh: "https://klikxxi.upns.one/#ikaftn" → baseDomain = "https://klikxxi.upns.one"
        val baseDomain = Regex("""^(https?://[^/#?]+)""")
            .find(url)?.groupValues?.get(1) ?: mainUrl
        Log.d(TAG, "✅ [1] Base domain: $baseDomain")

        // ── Langkah 2: Ekstrak Video ID ──────────────────────────────────────
        // Regex universal menangkap token setelah '/' atau '#' di akhir URL:
        //   /e/q5sc8o  →  q5sc8o
        //   /#q5sc8o   →  q5sc8o
        //   /#ikaftn   →  ikaftn
        val videoId = Regex("""[/#]([A-Za-z0-9]{4,})$""")
            .find(url.substringBefore("?"))
            ?.groupValues?.get(1)
            ?.trim() ?: ""

        if (videoId.isBlank()) {
            Log.e(TAG, "❌ [2] Video ID kosong — URL tidak cocok pola [/#]{id}. url=$url")
            return
        }
        Log.d(TAG, "✅ [2] Video ID: '$videoId'")

        // ── Langkah 3: Request ke API ────────────────────────────────────────
        val apiUrl = "$baseDomain/api/v1/video?id=$videoId&w=$API_W&h=$API_H&r=$API_R"
        Log.d(TAG, "✅ [3] API URL: $apiUrl")

        val rawResponse = try {
            app.get(
                url     = apiUrl,
                headers = mapOf(
                    "Referer"    to "$baseDomain/",
                    "Accept"     to "*/*",
                    "User-Agent" to UA
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ [3] HTTP request gagal: ${e.message}")
            return
        }

        val httpCode    = rawResponse.code
        val hexResponse = rawResponse.text.trim()
        Log.d(TAG, "    HTTP $httpCode — respons ${hexResponse.length} char")

        if (httpCode != 200) {
            Log.e(TAG, "❌ [3] HTTP $httpCode bukan 200.")
            return
        }
        if (hexResponse.isBlank()) {
            Log.e(TAG, "❌ [3] Respons kosong.")
            return
        }

        // ── Langkah 4: Validasi Hex ──────────────────────────────────────────
        // Pakai all{} bukan matches() — lebih robust terhadap whitespace sisa.
        if (!hexResponse.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            Log.e(TAG, "❌ [4] Bukan hex valid. Preview: '${hexResponse.take(80)}'")
            return
        }
        if (hexResponse.length % 2 != 0) {
            Log.e(TAG, "❌ [4] Panjang hex ganjil (${hexResponse.length}).")
            return
        }
        Log.d(TAG, "✅ [4] Hex valid — ${hexResponse.length} char = ${hexResponse.length / 2} bytes")

        // ── Langkah 5: Dekripsi AES-128-CBC ──────────────────────────────────
        val rawJson = try {
            decryptAesCbc(hexResponse, AES_KEY.toByteArray(), AES_IV.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "❌ [5] Dekripsi AES gagal: ${e.message}")
            return
        }
        Log.d(TAG, "✅ [5] Dekripsi OK — JSON ${rawJson.length} char")
        Log.d(TAG, "    Preview: ${rawJson.take(200)}")

        // ── Langkah 6: Ekstrak URL dari JSON ─────────────────────────────────
        var linkFound = false

        // ── SUMBER 1: field "source" — URL absolut M3U8 langsung ke CDN ──────
        // Contoh: "https:\/\/185.237.107.188\/v4\/.../master.m3u8?v=..."
        val sourceUrl = Regex(""""source"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { it.startsWith("http") && ".m3u8" in it }

        if (sourceUrl != null) {
            Log.d(TAG, "✅ [6-A] source → $sourceUrl")
            callback(newExtractorLink(
                source = name,
                name   = "Strp2p",
                url    = sourceUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
            })
            linkFound = true
        } else {
            Log.w(TAG, "⚠️  [6-A] field 'source' tidak ada / bukan M3U8.")
        }

        // ── SUMBER 2: field "hlsVideoTiktok" — path relatif via TikTok CDN ───
        // Contoh: "\/hls\/gRbL4ZlMf8lo6fMc3yECcw\/5c\/...\/master.m3u8"
        val tiktokPath = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { ".m3u8" in it }

        if (tiktokPath != null) {
            val tiktokUrl = when {
                tiktokPath.startsWith("http") -> tiktokPath
                tiktokPath.startsWith("/")    -> "$CDN_BASE$tiktokPath"
                else                          -> "$CDN_BASE/$tiktokPath"
            }
            Log.d(TAG, "✅ [6-B] hlsVideoTiktok → $tiktokUrl")
            callback(newExtractorLink(
                source = name,
                name   = "Strp2p [TikTok CDN]",
                url    = tiktokUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
            })
            linkFound = true
        } else {
            Log.w(TAG, "⚠️  [6-B] field 'hlsVideoTiktok' tidak ada / bukan M3U8.")
        }

        if (!linkFound) {
            Log.e(TAG, "❌ [6] Tidak ada link ditemukan. JSON dump:\n$rawJson")
        }
        Log.d(TAG, "══════════ STRP2P END (found=$linkFound) ══════════")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Unescape JSON slash: "\/" → "/" */
    private fun String.unescapeJsonSlashes() = replace("\\/", "/")

    /** Hex string → ByteArray */
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex length ganjil: $length" }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** AES-128-CBC / PKCS5Padding decrypt */
    private fun decryptAesCbc(hex: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(hex.decodeHex()), Charsets.UTF_8)
    }
}
