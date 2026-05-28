package com.KlikXXI

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extractor untuk server video Strp2p.
 *
 * ┌────────────────────────────────────────────────────────────────┐
 * │  DOMAIN YANG DITANGANI:                                        │
 * │   • klikxxi.strp2p.site  — domain utama                       │
 * │   • klikxxi.upns.one     — alias, API & crypto identik        │
 * ├────────────────────────────────────────────────────────────────┤
 * │  ALUR KERJA:                                                   │
 * │   1. Ekstrak baseDomain & videoId dari URL iframe              │
 * │   2. GET /api/v1/video?id=...  → Hex (application/octet-stream)│
 * │   3. Dekripsi AES-128-CBC                                      │
 * │   4. Parse JSON → link dengan prioritas:                       │
 * │      [1] "cf"             — Cloudflare, SSL valid, ekstensi    │
 * │                             .txt (M3U8 disamarkan)             │
 * │      [2] "source"         — raw IP, fallback                  │
 * │      [3] "hlsVideoTiktok" — path relatif, CDN base dari        │
 * │                             streamingConfig.adjust.Tiktok.domain│
 * └────────────────────────────────────────────────────────────────┘
 *
 * CHANGELOG v4:
 *  - Fix [6-CF]: Hapus filter ".m3u8" untuk field "cf".
 *    Server menyamarkan M3U8 sebagai .txt — isi file tetap valid.
 *    Contoh: https://s39.corporateoperations.sbs/v4/5c/{id}/cf-master.{ver}.txt
 *  - Fix [6-B]: CDN base TikTok diambil dari
 *    streamingConfig.adjust.Tiktok.domain (dinamis per-session),
 *    bukan hardcode. Fallback ke metric.cfDomain jika parse gagal.
 */
class Strp2p : ExtractorApi() {
    override val name            = "Strp2p"
    override val mainUrl         = "https://klikxxi.strp2p.site"
    override val requiresReferer = true

    private val AES_KEY = "kiemtienmua911ca"
    private val AES_IV  = "1234567890oiuytr"
    private val API_W   = "360"
    private val API_H   = "800"
    private val API_R   = "klikxxi.me"
    private val UA = "Mozilla/5.0 (Linux; Android 10; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    private val TAG = "Strp2p"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "══════════ STRP2P v4 START ══════════")
        Log.d(TAG, "Input URL : $url")

        // [1] Base domain
        val baseDomain = Regex("""^(https?://[^/#?]+)""")
            .find(url)?.groupValues?.get(1) ?: mainUrl
        Log.d(TAG, "✅ [1] baseDomain: $baseDomain")

        // [2] Video ID
        val videoId = Regex("""[/#]([A-Za-z0-9]{4,})$""")
            .find(url.substringBefore("?"))?.groupValues?.get(1)?.trim() ?: ""
        if (videoId.isBlank()) {
            Log.e(TAG, "❌ [2] Video ID kosong. url=$url"); return
        }
        Log.d(TAG, "✅ [2] videoId: '$videoId'")

        // [3] Request API
        val apiUrl = "$baseDomain/api/v1/video?id=$videoId&w=$API_W&h=$API_H&r=$API_R"
        Log.d(TAG, "✅ [3] apiUrl: $apiUrl")
        val resp = try {
            app.get(apiUrl, headers = mapOf(
                "Referer" to "$baseDomain/", "Accept" to "*/*", "User-Agent" to UA))
        } catch (e: Exception) {
            Log.e(TAG, "❌ [3] HTTP gagal: ${e.message}"); return
        }
        if (resp.code != 200) { Log.e(TAG, "❌ [3] HTTP ${resp.code}"); return }

        val hexStr = resp.text.trim()
        if (hexStr.isBlank()) { Log.e(TAG, "❌ [3] Respons kosong"); return }
        Log.d(TAG, "    HTTP 200 — ${hexStr.length} char hex")

        // [4] Validasi hex
        if (!hexStr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            || hexStr.length % 2 != 0) {
            Log.e(TAG, "❌ [4] Hex tidak valid"); return
        }

        // [5] Dekripsi
        val rawJson = try {
            decryptAesCbc(hexStr, AES_KEY.toByteArray(), AES_IV.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "❌ [5] Dekripsi gagal: ${e.message}"); return
        }
        Log.d(TAG, "✅ [5] JSON ${rawJson.length} char")
        Log.d(TAG, "    Preview: ${rawJson.take(200)}")

        // Parse JSON untuk field kompleks (streamingConfig)
        val jsonObj = try { JSONObject(rawJson) } catch (e: Exception) {
            Log.w(TAG, "⚠️  JSONObject parse gagal, lanjut regex"); null
        }

        var linkFound = false

        // ── [6-CF] field "cf" — Cloudflare, ekstensi .txt ──────────────────
        // KRITIS: Jangan filter berdasarkan ".m3u8"!
        // Server Strp2p menyamarkan M3U8 sebagai .txt untuk menghindari blokir.
        // URL contoh: https://s39.corporateoperations.sbs/v4/5c/{id}/cf-master.{ver}.txt
        // ExoPlayer/Media3 bisa memutar .txt berisi M3U8 selama
        // kita set MimeType = APPLICATION_M3U8 via ExtractorLinkType.M3U8.
        val cfUrl = Regex(""""cf"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { it.startsWith("http") && !isRawIp(it) }

        if (cfUrl != null) {
            Log.d(TAG, "✅ [6-CF] cf → $cfUrl")
            callback(newExtractorLink(
                source = name, name = "Strp2p [CF]",
                url = cfUrl, type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
            })
            linkFound = true
        } else {
            Log.w(TAG, "⚠️  [6-CF] cf tidak ada atau raw IP.")
        }

        // ── [6-A] field "source" — raw IP fallback ─────────────────────────
        val sourceUrl = Regex(""""source"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { it.startsWith("http") && ".m3u8" in it }

        if (sourceUrl != null) {
            val isIp = isRawIp(sourceUrl)
            Log.d(TAG, if (isIp) "⚠️  [6-A] source (raw IP) → $sourceUrl"
                       else      "✅ [6-A] source → $sourceUrl")
            callback(newExtractorLink(
                source = name, name = "Strp2p (IP)",
                url = sourceUrl, type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
            })
            linkFound = true
        } else {
            Log.w(TAG, "⚠️  [6-A] source tidak ada.")
        }

        // ── [6-B] field "hlsVideoTiktok" — TikTok CDN ──────────────────────
        // CDN base diambil dari streamingConfig.adjust.Tiktok.domain (dinamis).
        // Fallback 1: metric.cfDomain → "soq.{cfDomain}"
        // Fallback 2: hardcode soq.corporateoperations.sbs
        val tiktokPath = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { ".m3u8" in it }

        if (tiktokPath != null) {
            val cdnBase = extractTiktokCdnBase(jsonObj, rawJson)
            Log.d(TAG, "    [6-B] TikTok CDN base: $cdnBase")

            val tiktokUrl = when {
                tiktokPath.startsWith("http") -> tiktokPath
                tiktokPath.startsWith("/")    -> "$cdnBase$tiktokPath"
                else                          -> "$cdnBase/$tiktokPath"
            }

            if (isRawIp(tiktokUrl)) {
                Log.w(TAG, "⚠️  [6-B] TikTok URL raw IP — dilewati.")
            } else {
                Log.d(TAG, "✅ [6-B] hlsVideoTiktok → $tiktokUrl")
                callback(newExtractorLink(
                    source = name, name = "Strp2p [TikTok CDN]",
                    url = tiktokUrl, type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$baseDomain/"
                    this.quality = Qualities.Unknown.value
                })
                linkFound = true
            }
        } else {
            Log.w(TAG, "⚠️  [6-B] hlsVideoTiktok tidak ada.")
        }

        if (!linkFound) Log.e(TAG, "❌ [6] Tidak ada link. JSON:\n$rawJson")
        Log.d(TAG, "══════════ STRP2P v4 END (found=$linkFound) ══════════")
    }

    /**
     * Ekstrak TikTok CDN base domain dari JSON.
     *
     * Strategi (urutan prioritas):
     *  1. streamingConfig (string JSON-in-JSON) → adjust.Tiktok.domain
     *  2. metric.cfDomain → "soq.{cfDomain}"
     *  3. Hardcode fallback
     *
     * Contoh streamingConfig:
     *   {"order":["Tiktok","Google","Cloudflare","In-House"],
     *    "adjust":{"Tiktok":{"disabled":false,"domain":"soq.corporateoperations.sbs"}}}
     */
    private fun extractTiktokCdnBase(jsonObj: JSONObject?, rawJson: String): String {
        // [1] Parse streamingConfig
        try {
            val scRaw = jsonObj?.optString("streamingConfig") ?: ""
            if (scRaw.isNotBlank()) {
                val sc = JSONObject(scRaw)
                val domain = sc.optJSONObject("adjust")
                    ?.optJSONObject("Tiktok")
                    ?.optString("domain", "") ?: ""
                if (domain.isNotBlank()) {
                    val base = domain.trimEnd('/')
                    return if (base.startsWith("http")) base else "https://$base"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "streamingConfig parse gagal: ${e.message}")
        }

        // [2] Fallback: regex langsung pada rawJson
        val m = Regex(""""Tiktok"\s*:\s*\{[^}]*"domain"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
        if (!m.isNullOrBlank()) {
            val base = m.trimEnd('/')
            return if (base.startsWith("http")) base else "https://$base"
        }

        // [3] Fallback: metric.cfDomain → "soq.{cfDomain}"
        try {
            val cfDomain = jsonObj?.optJSONObject("metric")
                ?.optString("cfDomain", "") ?: ""
            if (cfDomain.isNotBlank()) {
                return "https://soq.$cfDomain"
            }
        } catch (e: Exception) { /* ignore */ }

        // [4] Hardcode last resort
        Log.w(TAG, "Semua parse CDN base gagal — pakai hardcode fallback.")
        return "https://soq.corporateoperations.sbs"
    }

    /** Cek apakah host URL adalah raw IPv4 */
    private fun isRawIp(url: String): Boolean {
        val host = Regex("""https?://([^/]+)""").find(url)
            ?.groupValues?.get(1)?.substringBefore(":") ?: return false
        return host.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))
    }

    private fun String.unescapeJsonSlashes() = replace("\\/", "/")

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex ganjil: $length" }
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun decryptAesCbc(hex: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(hex.decodeHex()), Charsets.UTF_8)
    }
}
