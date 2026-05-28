package com.KlikXXI

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
 * Alur kerja (dikonfirmasi dari hasil debug):
 *  1. Ambil Video ID dari URL iframe  →  /e/{videoId}
 *  2. GET /api/v1/video?id={videoId}&w=360&h=800&r=klikxxi.me
 *     dengan header Referer & Accept yang wajib.
 *  3. Respons berupa string Hexadecimal.
 *  4. Dekripsi AES-CBC (PKCS5Padding) dengan key & IV statis.
 *  5. Parse JSON → key utama adalah "source" (URL M3U8 absolut).
 *     Fallback: "hlsVideoTiktok" (path relatif, perlu base domain).
 *
 * Struktur JSON yang dikonfirmasi:
 *   "source"        : "https://185.237.107.189/v4/.../master.m3u8?v=..."  ← PRIORITAS
 *   "hlsVideoTiktok": "/hls/.../master.m3u8"                              ← FALLBACK
 *
 * Kriptografi:
 *   Key : kiemtienmua911ca   (hex: 6b69656d7469656e6d75613931316361)
 *   IV  : 1234567890oiuytr   (hex: 313233343536373839306f6975797472)
 */
class Strp2p : ExtractorApi() {
    override val name            = "Strp2p"
    override val mainUrl         = "https://klikxxi.strp2p.site"
    override val requiresReferer = true

    // ── Konstanta Kriptografi ────────────────────────────────────────────
    private val AES_KEY = "kiemtienmua911ca"
    private val AES_IV  = "1234567890oiuytr"

    // ── Konstanta Request ────────────────────────────────────────────────
    private val API_W = "360"
    private val API_H = "800"
    private val API_R = "klikxxi.me"

    // ── Base domain untuk path relatif hlsVideoTiktok ────────────────────
    // Diambil dari field "cfDomain" di JSON: "corporateoperations.sbs"
    // Prefix CDN Tiktok yang ditemukan di JSON: "soq.corporateoperations.sbs"
    private val CDN_BASE = "https://soq.corporateoperations.sbs"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // ── Langkah 1: Ekstrak Video ID ──────────────────────────────────
        // Input: https://klikxxi.strp2p.site/e/q5sc8o
        // Output: q5sc8o
        val videoId = url
            .substringAfter("/e/")
            .substringBefore("?")
            .substringBefore("/")
            .trim()

        if (videoId.isBlank()) return

        // ── Langkah 2: Bangun URL API ────────────────────────────────────
        val apiUrl = "$mainUrl/api/v1/video" +
                "?id=$videoId" +
                "&w=$API_W" +
                "&h=$API_H" +
                "&r=$API_R"

        // ── Langkah 3: HTTP GET ke endpoint API ──────────────────────────
        val hexResponse = try {
            app.get(
                url     = apiUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Accept"  to "*/*"
                )
            ).text.trim()
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        if (hexResponse.isBlank() || !hexResponse.matches(Regex("[0-9a-fA-F]+"))) return

        // ── Langkah 4: Dekripsi AES-CBC ──────────────────────────────────
        val rawJson = try {
            decryptAesCbc(
                hexString = hexResponse,
                key       = AES_KEY.toByteArray(Charsets.UTF_8),
                iv        = AES_IV.toByteArray(Charsets.UTF_8)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        // ── Langkah 5: Ekstrak URL dari JSON ─────────────────────────────
        // PRIORITAS 1: key "source" → URL absolut M3U8
        // Contoh nilai: "https:\/\/185.237.107.189\/v4\/...\/master.m3u8?v=..."
        val sourceUrl = Regex(""""source"\s*:\s*"([^"]+)"""")
            .find(rawJson)
            ?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { it.startsWith("http") && it.contains(".m3u8") }

        if (sourceUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "KlikXXI [Strp2p]",
                    url    = sourceUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // PRIORITAS 2: key "hlsVideoTiktok" → path relatif, gabung dengan CDN base
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
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "KlikXXI [Strp2p-TT]",
                    url    = tiktokUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  HELPER
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Hapus backslash escape dari JSON string.
     * Contoh: "https:\/\/example.com\/path" → "https://example.com/path"
     */
    private fun String.unescapeJsonSlashes(): String =
        this.replace("\\/", "/")

    /**
     * Konversi Hexadecimal String ke ByteArray.
     */
    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Hex string harus memiliki panjang genap: $length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Dekripsi AES-128-CBC dengan PKCS5Padding.
     */
    private fun decryptAesCbc(hexString: String, key: ByteArray, iv: ByteArray): String {
        val encryptedBytes = hexString.trim().decodeHex()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    }
}
