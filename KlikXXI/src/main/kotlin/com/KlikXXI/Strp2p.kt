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
 * │   3. Dekripsi AES-128-CBC (key & IV tetap)                     │
 * │   4. Parse JSON → susun link berdasarkan prioritas:            │
 * │                                                                │
 * │      [1] "Cloudflare Swapped"                                  │
 * │          HOST dari field "cf"  +  PATH dari field "source"     │
 * │          → SSL valid (domain CF) + konten hidup (path source)  │
 * │          → SOLUSI utama ERR_CERT_AUTHORITY_INVALID (-202)      │
 * │                                                                │
 * │      [2] "Direct IP"                                           │
 * │          field "source" apa adanya (raw IP)                    │
 * │          → Fallback; mungkin SSL error di beberapa perangkat   │
 * │                                                                │
 * │      [3] "TikTok CDN"                                          │
 * │          field "hlsVideoTiktok" + CDN base dari streamingConfig│
 * └────────────────────────────────────────────────────────────────┘
 *
 * CHANGELOG v6 (Final):
 *  - Logika "Domain Swapping" dikonfirmasi lewat uji Termux v6:
 *      cf (langsung)   → HTTP 403 ❌
 *      source (raw IP) → HTTP 200 ✅ tapi SSL error di Android
 *      swapped         → HTTP 200 ✅ Content-Type: application/vnd.apple.mpegurl ✅
 *  - Header dikunci ketat di SEMUA callback: UA Chrome Mobile 120,
 *    Referer, dan Origin identik dengan request API.
 *  - field "cf" langsung TIDAK lagi dikirim ke callback karena
 *    terbukti 403. PATH-nya saja yang dipakai untuk swapping.
 *  - Urutan prioritas: [Swapped] → [Direct IP] → [TikTok CDN]
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

    // WAJIB: UA harus identik antara request API dan header player.
    // Server Strp2p memvalidasi konsistensi UA di kedua sisi.
    private val UA = "Mozilla/5.0 (Linux; Android 10; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val TAG = "Strp2p"

    // ─────────────────────────────────────────────────────────────────────────
    //  MAIN EXTRACTOR
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "══════════ STRP2P v6 START ══════════")
        Log.d(TAG, "Input URL : $url")

        // ── [1] Deteksi baseDomain secara dinamis ─────────────────────────────
        // Support: klikxxi.strp2p.site, klikxxi.upns.one, dan domain alias lain.
        val baseDomain = Regex("""^(https?://[^/#?]+)""")
            .find(url)?.groupValues?.get(1) ?: mainUrl
        Log.d(TAG, "✅ [1] baseDomain: $baseDomain")

        // ── [2] Ekstrak Video ID ──────────────────────────────────────────────
        val videoId = Regex("""[/#]([A-Za-z0-9]{4,})$""")
            .find(url.substringBefore("?"))?.groupValues?.get(1)?.trim() ?: ""
        if (videoId.isBlank()) {
            Log.e(TAG, "❌ [2] Video ID kosong. url=$url")
            return
        }
        Log.d(TAG, "✅ [2] videoId: '$videoId'")

        // ── [3] Request API ───────────────────────────────────────────────────
        val apiUrl = "$baseDomain/api/v1/video?id=$videoId&w=$API_W&h=$API_H&r=$API_R"
        Log.d(TAG, "✅ [3] apiUrl: $apiUrl")

        val resp = try {
            app.get(apiUrl, headers = mapOf(
                "User-Agent" to UA,
                "Referer"    to "$baseDomain/",
                "Origin"     to baseDomain,
                "Accept"     to "*/*"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "❌ [3] HTTP gagal: ${e.message}")
            return
        }

        if (resp.code != 200) {
            Log.e(TAG, "❌ [3] HTTP ${resp.code}")
            return
        }

        val hexStr = resp.text.trim()
        if (hexStr.isBlank()) {
            Log.e(TAG, "❌ [3] Respons kosong")
            return
        }
        Log.d(TAG, "    HTTP 200 — ${hexStr.length} char hex")

        // ── [4] Validasi hex ──────────────────────────────────────────────────
        if (!hexStr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            || hexStr.length % 2 != 0) {
            Log.e(TAG, "❌ [4] Hex tidak valid")
            return
        }

        // ── [5] Dekripsi AES-128-CBC ──────────────────────────────────────────
        val rawJson = try {
            decryptAesCbc(hexStr, AES_KEY.toByteArray(), AES_IV.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "❌ [5] Dekripsi gagal: ${e.message}")
            return
        }
        Log.d(TAG, "✅ [5] JSON ${rawJson.length} char")
        Log.d(TAG, "    Preview: ${rawJson.take(300)}")

        val jsonObj = try { JSONObject(rawJson) } catch (e: Exception) {
            Log.w(TAG, "⚠️  JSONObject parse gagal, lanjut regex")
            null
        }

        // ── [6] Ekstrak field mentah dari JSON ────────────────────────────────
        val cfRaw     = Regex(""""cf"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)?.unescapeJsonSlashes()
        val sourceRaw = Regex(""""source"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)?.unescapeJsonSlashes()

        Log.d(TAG, "    [6] cfRaw    : $cfRaw")
        Log.d(TAG, "    [6] sourceRaw: $sourceRaw")
        Log.d(TAG, "    [6] cf isRawIp    : ${cfRaw?.let { isRawIp(it) }}")
        Log.d(TAG, "    [6] source isRawIp: ${sourceRaw?.let { isRawIp(it) }}")

        var linkFound = false

        // ─────────────────────────────────────────────────────────────────────
        // [PRIORITAS 1] CLOUDFLARE SWAPPED
        //
        // Dikonfirmasi lewat uji Termux v6 pada film q5sc8o:
        //   cf (langsung)   → HTTP 403 ❌  (path cf diblokir untuk player)
        //   source (raw IP) → HTTP 200 ✅  (konten hidup, path terbuka)
        //   swapped         → HTTP 200 ✅  Content-Type: application/vnd.apple.mpegurl
        //
        // Caranya:
        //   cfHost   = https://soq.corporateoperations.sbs      ← dari field "cf"
        //   srcPath  = /v4/z7UU.../master.m3u8?v=...            ← dari field "source"
        //   swapped  = cfHost + srcPath                          ← SSL valid + hidup
        // ─────────────────────────────────────────────────────────────────────
        val cfHost  = cfRaw?.let { extractHost(it) }
        val srcPath = sourceRaw?.let { extractPath(it) }

        if (cfHost != null && srcPath != null && sourceRaw != null && isRawIp(sourceRaw)) {
            val swappedUrl = "$cfHost$srcPath"
            Log.d(TAG, "✅ [P1] Domain Swapping berhasil!")
            Log.d(TAG, "    cfHost    : $cfHost")
            Log.d(TAG, "    srcPath   : $srcPath")
            Log.d(TAG, "    swappedUrl: $swappedUrl")

            callback(newExtractorLink(
                source = name,
                name   = "Strp2p [Cloudflare Swapped]",
                url    = swappedUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer"    to "$baseDomain/",
                    "Origin"     to baseDomain
                )
            })
            linkFound = true
        } else {
            Log.w(TAG, "⚠️  [P1] Swap tidak dilakukan — " +
                    "cfHost=$cfHost | srcPath=${srcPath?.take(40)} | " +
                    "sourceIsIp=${sourceRaw?.let { isRawIp(it) }}")
        }

        // ─────────────────────────────────────────────────────────────────────
        // [PRIORITAS 2] DIRECT IP (Fallback)
        //
        // Field "source" dikirim apa adanya sebagai fallback.
        // HTTP 200 dikonfirmasi, tapi raw IP menyebabkan SSL error
        // ERR_CERT_AUTHORITY_INVALID (-202) di Android dengan Cronet/ExoPlayer.
        // Tetap dikirim karena:
        //   a) Beberapa perangkat/build mungkin lebih toleran terhadap SSL.
        //   b) Jika Swapped gagal karena perubahan struktur server, ini jadi
        //      jalur darurat yang masih menghasilkan data.
        // ─────────────────────────────────────────────────────────────────────
        val sourceUrl = sourceRaw
            ?.takeIf { it.startsWith("http") && ".m3u8" in it }

        if (sourceUrl != null) {
            Log.d(TAG, "✅ [P2] Direct IP fallback → $sourceUrl")
            Log.w(TAG, "    ⚠️  Raw IP — kemungkinan SSL error di Android Cronet")

            callback(newExtractorLink(
                source = name,
                name   = "Strp2p [Direct IP]",
                url    = sourceUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer"    to "$baseDomain/",
                    "Origin"     to baseDomain
                )
            })
            linkFound = true
        } else {
            Log.w(TAG, "⚠️  [P2] source tidak ada atau bukan .m3u8.")
        }

        // ─────────────────────────────────────────────────────────────────────
        // [PRIORITAS 3] TIKTOK CDN
        //
        // CDN base diambil secara dinamis dari streamingConfig.adjust.Tiktok.domain.
        // Uji Termux v6: HTTP 403 pada film q5sc8o — mungkin URL sudah expired.
        // Tetap dicoba karena valid untuk film/waktu akses lain.
        // ─────────────────────────────────────────────────────────────────────
        val tiktokPath = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
            ?.unescapeJsonSlashes()
            ?.takeIf { ".m3u8" in it }

        if (tiktokPath != null) {
            val cdnBase   = extractTiktokCdnBase(jsonObj, rawJson)
            val tiktokUrl = when {
                tiktokPath.startsWith("http") -> tiktokPath
                tiktokPath.startsWith("/")    -> "$cdnBase$tiktokPath"
                else                          -> "$cdnBase/$tiktokPath"
            }

            if (isRawIp(tiktokUrl)) {
                Log.w(TAG, "⚠️  [P3] TikTok URL raw IP — dilewati.")
            } else {
                Log.d(TAG, "✅ [P3] TikTok CDN → $tiktokUrl")
                Log.d(TAG, "    cdnBase: $cdnBase")

                callback(newExtractorLink(
                    source = name,
                    name   = "Strp2p [TikTok CDN]",
                    url    = tiktokUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$baseDomain/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Referer"    to "$baseDomain/",
                        "Origin"     to baseDomain
                    )
                })
                linkFound = true
            }
        } else {
            Log.w(TAG, "⚠️  [P3] hlsVideoTiktok tidak ada.")
        }

        if (!linkFound) {
            Log.e(TAG, "❌ Tidak ada link valid sama sekali. JSON:\n$rawJson")
        }
        Log.d(TAG, "══════════ STRP2P v6 END (found=$linkFound) ══════════")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DOMAIN SWAPPING HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ekstrak scheme + host dari URL.
     * "https://soq.corporateoperations.sbs/v4/..." → "https://soq.corporateoperations.sbs"
     */
    private fun extractHost(url: String): String? =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)

    /**
     * Ekstrak path + query string dari URL, mulai dari karakter '/' pertama
     * setelah authority (host:port).
     * "https://45.156.158.184/v4/abc/master.m3u8?v=123" → "/v4/abc/master.m3u8?v=123"
     */
    private fun extractPath(url: String): String? {
        val idx = url.indexOf("/", url.indexOf("://") + 3)
        return if (idx >= 0) url.substring(idx) else null
    }

    /**
     * Kembalikan true jika host URL adalah raw IPv4.
     * "https://185.237.107.50/..." → true
     * "https://soq.corporateoperations.sbs/..." → false
     */
    private fun isRawIp(url: String): Boolean {
        val host = Regex("""https?://([^/:]+)""").find(url)
            ?.groupValues?.get(1) ?: return false
        return host.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TIKTOK CDN BASE EXTRACTOR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ekstrak TikTok CDN base domain dari JSON secara dinamis.
     *
     * Urutan prioritas:
     *  1. streamingConfig (JSON-in-JSON string) → adjust.Tiktok.domain
     *  2. Regex langsung pada rawJson untuk field "Tiktok"."domain"
     *  3. metric.cfDomain → konstruksi "soq.{cfDomain}"
     *  4. Hardcode fallback: soq.corporateoperations.sbs
     *
     * Uji Termux v6 mengonfirmasi CDN base aktif:
     *   https://p16-ad-site-sign-sg.tiktokcdn.com
     */
    private fun extractTiktokCdnBase(jsonObj: JSONObject?, rawJson: String): String {
        // [1] streamingConfig → adjust.Tiktok.domain
        try {
            val scRaw = jsonObj?.optString("streamingConfig") ?: ""
            if (scRaw.isNotBlank()) {
                val sc = JSONObject(scRaw)
                val domain = sc.optJSONObject("adjust")
                    ?.optJSONObject("Tiktok")
                    ?.optString("domain", "") ?: ""
                if (domain.isNotBlank()) {
                    val base = domain.trimEnd('/')
                    Log.d(TAG, "    TikTok CDN dari streamingConfig: $base")
                    return if (base.startsWith("http")) base else "https://$base"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "streamingConfig parse gagal: ${e.message}")
        }

        // [2] Regex langsung
        val m = Regex(""""Tiktok"\s*:\s*\{[^}]*"domain"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
        if (!m.isNullOrBlank()) {
            val base = m.trimEnd('/')
            Log.d(TAG, "    TikTok CDN dari regex: $base")
            return if (base.startsWith("http")) base else "https://$base"
        }

        // [3] metric.cfDomain
        try {
            val cfDomain = jsonObj?.optJSONObject("metric")
                ?.optString("cfDomain", "") ?: ""
            if (cfDomain.isNotBlank()) {
                Log.d(TAG, "    TikTok CDN dari cfDomain: soq.$cfDomain")
                return "https://soq.$cfDomain"
            }
        } catch (e: Exception) { /* abaikan */ }

        // [4] Hardcode last resort
        Log.w(TAG, "    TikTok CDN: semua parse gagal, pakai hardcode fallback")
        return "https://soq.corporateoperations.sbs"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CRYPTO UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    private fun String.unescapeJsonSlashes() = replace("\\/", "/")

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex length ganjil: $length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun decryptAesCbc(hex: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(hex.decodeHex()), Charsets.UTF_8)
    }
}
