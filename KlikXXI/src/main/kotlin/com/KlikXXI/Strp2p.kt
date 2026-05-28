package com.KlikXXI

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  Strp2p Extractor v7 — "Pre-fetch & Rewrite M3U8"                      ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  DOMAIN YANG DITANGANI:                                                  ║
 * ║   • klikxxi.strp2p.site  — domain utama                                 ║
 * ║   • klikxxi.upns.one     — alias, API & crypto identik                  ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║  ALUR LENGKAP v7:                                                        ║
 * ║   1. Decrypt hex → JSON                                                  ║
 * ║   2. Build swappedUrl (cfHost + srcPath)                                 ║
 * ║   3. app.get(swappedUrl) → M3U8 string (SSL bypass oleh OkHttp CS3)     ║
 * ║   4. Jika master playlist → ambil semua variants, urutkan kualitas       ║
 * ║   5. Untuk setiap variant → app.get(variantUrl) → segment list           ║
 * ║   6. rewriteSegmentUrl: raw IP → cfHost, relatif → absolutif             ║
 * ║   7. Emit ExtractorLinkPlayList per kualitas                             ║
 * ║   8. Fallback: kirim swappedUrl langsung (v6 behavior)                  ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
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

    // UA HARUS konsisten antara API request dan player headers.
    private val UA = "Mozilla/5.0 (Linux; Android 10; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val TAG = "Strp2p_v7"

    // =========================================================================
    //  MAIN EXTRACTOR
    // =========================================================================

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "══════════ STRP2P v7 START ══════════")
        Log.d(TAG, "Input URL : $url")

        // ── [1] Detect baseDomain ─────────────────────────────────────────────
        val baseDomain = Regex("""^(https?://[^/#?]+)""")
            .find(url)?.groupValues?.get(1) ?: mainUrl
        Log.d(TAG, "✅ [1] baseDomain: $baseDomain")

        // ── [2] Extract Video ID ──────────────────────────────────────────────
        val videoId = Regex("""[/#]([A-Za-z0-9]{4,})$""")
            .find(url.substringBefore("?"))?.groupValues?.get(1)?.trim() ?: ""
        if (videoId.isBlank()) {
            Log.e(TAG, "❌ [2] Video ID kosong. url=$url")
            return
        }
        Log.d(TAG, "✅ [2] videoId: '$videoId'")

        // ── [3] API Request ───────────────────────────────────────────────────
        val apiUrl = "$baseDomain/api/v1/video?id=$videoId&w=$API_W&h=$API_H&r=$API_R"
        val apiHeaders = mapOf(
            "User-Agent" to UA,
            "Referer"    to "$baseDomain/",
            "Origin"     to baseDomain,
            "Accept"     to "*/*"
        )

        val resp = try {
            app.get(apiUrl, headers = apiHeaders)
        } catch (e: Exception) {
            Log.e(TAG, "❌ [3] HTTP gagal: ${e.message}")
            return
        }

        if (resp.code != 200) {
            Log.e(TAG, "❌ [3] HTTP ${resp.code}")
            return
        }

        val hexStr = resp.text.trim()
        if (hexStr.isBlank()) { Log.e(TAG, "❌ [3] Respons kosong"); return }
        Log.d(TAG, "    HTTP 200 — ${hexStr.length} char hex")

        // ── [4] Validate hex ──────────────────────────────────────────────────
        if (!hexStr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            || hexStr.length % 2 != 0) {
            Log.e(TAG, "❌ [4] Hex tidak valid")
            return
        }

        // ── [5] AES-128-CBC Decrypt ───────────────────────────────────────────
        val rawJson = try {
            decryptAesCbc(hexStr, AES_KEY.toByteArray(), AES_IV.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "❌ [5] Dekripsi gagal: ${e.message}")
            return
        }
        Log.d(TAG, "✅ [5] JSON ${rawJson.length} char")

        val jsonObj = try { JSONObject(rawJson) } catch (e: Exception) { null }

        // ── [6] Extract raw fields ────────────────────────────────────────────
        val cfRaw     = Regex(""""cf"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)?.unescapeJsonSlashes()
        val sourceRaw = Regex(""""source"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)?.unescapeJsonSlashes()

        Log.d(TAG, "    [6] cfRaw    : $cfRaw")
        Log.d(TAG, "    [6] sourceRaw: $sourceRaw")

        // ── [7] Build swapped URL ─────────────────────────────────────────────
        val cfHost     = cfRaw?.let { extractHost(it) }
        val srcPath    = sourceRaw?.let { extractPath(it) }
        val swappedUrl = if (cfHost != null && srcPath != null
            && sourceRaw != null && isRawIp(sourceRaw)) {
            "$cfHost$srcPath"
        } else null

        Log.d(TAG, "    [7] cfHost    : $cfHost")
        Log.d(TAG, "    [7] swappedUrl: $swappedUrl")

        val playerHeaders = mapOf(
            "User-Agent" to UA,
            "Referer"    to "$baseDomain/",
            "Origin"     to baseDomain
        )

        // =========================================================================
        // ██  STRATEGI v7: PRE-FETCH & REWRITE M3U8  ██
        // =========================================================================
        if (swappedUrl != null && cfHost != null) {
            Log.d(TAG, "▶▶▶ [v7] Memulai Pre-fetch & Rewrite engine...")

            val success = runPrefetchRewriteEngine(
                masterUrl     = swappedUrl,
                cfHost        = cfHost,
                baseDomain    = baseDomain,
                playerHeaders = playerHeaders,
                callback      = callback
            )

            if (success) {
                Log.d(TAG, "✅ [v7] Engine berhasil — playlist rewritten dikirim ke player")
                Log.d(TAG, "══════════ STRP2P v7 END (success) ══════════")
                return
            } else {
                Log.w(TAG, "⚠️  [v7] Engine gagal, jatuh ke fallback v6...")
            }
        }

        // =========================================================================
        // ██  FALLBACK: kirim link langsung (v6 behavior)  ██
        // =========================================================================
        var linkFound = false

        // Fallback P1: Swapped URL
        if (swappedUrl != null) {
            Log.d(TAG, "✅ [FB-P1] Swapped URL: $swappedUrl")
            callback(newExtractorLink(
                source = name,
                name   = "Strp2p [CF Swapped]",
                url    = swappedUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
                this.headers = playerHeaders
            })
            linkFound = true
        }

        // Fallback P2: Direct IP
        val sourceUrl = sourceRaw?.takeIf { it.startsWith("http") && ".m3u8" in it }
        if (sourceUrl != null) {
            Log.w(TAG, "✅ [FB-P2] Direct IP (kemungkinan SSL error): $sourceUrl")
            callback(newExtractorLink(
                source = name,
                name   = "Strp2p [Direct IP]",
                url    = sourceUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
                this.headers = playerHeaders
            })
            linkFound = true
        }

        // Fallback P3: TikTok CDN
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
            if (!isRawIp(tiktokUrl)) {
                Log.d(TAG, "✅ [FB-P3] TikTok CDN: $tiktokUrl")
                callback(newExtractorLink(
                    source = name,
                    name   = "Strp2p [TikTok CDN]",
                    url    = tiktokUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$baseDomain/"
                    this.quality = Qualities.Unknown.value
                    this.headers = playerHeaders
                })
                linkFound = true
            }
        }

        if (!linkFound) Log.e(TAG, "❌ Tidak ada link valid.")
        Log.d(TAG, "══════════ STRP2P v7 END (fallback=$linkFound) ══════════")
    }

    // =========================================================================
    //  [v7 CORE] PRE-FETCH & REWRITE ENGINE
    // =========================================================================

    /**
     * Engine utama v7.
     */
    @Suppress("DEPRECATION")
    private suspend fun runPrefetchRewriteEngine(
        masterUrl     : String,
        cfHost        : String,
        baseDomain    : String,
        playerHeaders : Map<String, String>,
        callback      : (ExtractorLink) -> Unit
    ): Boolean {

        // ── Step 1: Download master M3U8 ─────────────────────────────────────
        val masterContent = fetchM3u8Content(masterUrl, playerHeaders)
        if (masterContent == null) {
            Log.e(TAG, "    [PF-1] ❌ Gagal download master M3U8")
            return false
        }

        var emitted = false

        // ── Step 2: Tentukan tipe playlist ───────────────────────────────────
        when {
            // KASUS A: Master playlist dengan multiple variants
            masterContent.contains("#EXT-X-STREAM-INF") -> {
                Log.d(TAG, "    [PF-2] Tipe: MASTER PLAYLIST")
                val variants = parseMasterVariants(masterContent, masterUrl, cfHost)

                for ((height, variantUrl) in variants) {
                    val label = if (height > 0) "${height}p" else "Auto"
                    
                    val variantContent = fetchM3u8Content(variantUrl, playerHeaders)
                    if (variantContent == null) continue

                    val segments = parseAndRewriteSegments(variantContent, variantUrl, cfHost)
                    if (segments.isEmpty()) continue

                    emitPlayList(segments, height, label, baseDomain, playerHeaders, callback)
                    emitted = true
                }
            }

            // KASUS B: Langsung variant playlist (tidak ada #EXT-X-STREAM-INF)
            masterContent.contains("#EXTINF") -> {
                Log.d(TAG, "    [PF-2] Tipe: VARIANT PLAYLIST (langsung)")
                val segments = parseAndRewriteSegments(masterContent, masterUrl, cfHost)
                if (segments.isNotEmpty()) {
                    emitPlayList(segments, -1, "Auto", baseDomain, playerHeaders, callback)
                    emitted = true
                }
            }

            else -> {
                Log.e(TAG, "    [PF-2] ❌ Konten tidak dikenal (bukan M3U8 valid)")
            }
        }

        return emitted
    }

    // =========================================================================
    //  M3U8 PARSERS
    // =========================================================================

    private suspend fun fetchM3u8Content(
        url     : String,
        headers : Map<String, String>
    ): String? {
        return try {
            val resp = app.get(url, headers = headers, timeout = 20)
            when {
                resp.code != 200 -> null
                else -> {
                    val text = resp.text.trim()
                    if (text.startsWith("#EXTM3U")) text else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "    fetch [$url] error: ${e.message}")
            null
        }
    }

    private fun parseMasterVariants(
        masterContent : String,
        masterUrl     : String,
        cfHost        : String
    ): List<Pair<Int, String>> {
        val result    = mutableListOf<Pair<Int, String>>()
        val lines     = masterContent.lines()
        var pendingH  = -1

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingH = Regex("""RESOLUTION=\d+x(\d+)""")
                        .find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                }
                !trimmed.startsWith("#") && trimmed.isNotBlank() && pendingH != -1 -> {
                    val absUrl = resolveUrl(trimmed, masterUrl, cfHost)
                    result.add(Pair(pendingH, absUrl))
                    pendingH = -1
                }
                else -> if (!trimmed.startsWith("#")) pendingH = -1
            }
        }

        return result.sortedByDescending { it.first }
    }

    private fun parseAndRewriteSegments(
        variantContent : String,
        variantUrl     : String,
        cfHost         : String
    ): List<PlayListItem> {
        val segments        = mutableListOf<PlayListItem>()
        val lines           = variantContent.lines()
        var pendingDuration = 0.0

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXTINF:") -> {
                    pendingDuration = trimmed
                        .substringAfter("#EXTINF:")
                        .substringBefore(",")
                        .trim()
                        .toDoubleOrNull() ?: 0.0
                }
                !trimmed.startsWith("#") && trimmed.isNotBlank() && pendingDuration > 0.0 -> {
                    val absUrl = resolveUrl(trimmed, variantUrl, cfHost)
                    val rewrittenUrl = rewriteSegmentUrl(absUrl, cfHost)
                    val durationUs = (pendingDuration * 1_000_000L).toLong()

                    segments.add(PlayListItem(rewrittenUrl, durationUs))
                    pendingDuration = 0.0
                }
                !trimmed.startsWith("#") && trimmed.isNotBlank() -> {
                    if (trimmed.endsWith(".ts") || trimmed.contains(".ts?")) {
                        val absUrl      = resolveUrl(trimmed, variantUrl, cfHost)
                        val rewritten   = rewriteSegmentUrl(absUrl, cfHost)
                        segments.add(PlayListItem(rewritten, 0L))
                    }
                }
            }
        }

        return segments
    }

    // =========================================================================
    //  REWRITE ENGINE — FUNGSI UTAMA
    // =========================================================================

    private fun rewriteSegmentUrl(url: String, cfHost: String): String {
        if (!isRawIp(url)) return url
        val path      = extractPath(url) ?: return url
        return "$cfHost$path"
    }

    // =========================================================================
    //  URL RESOLVER
    // =========================================================================

    private fun resolveUrl(url: String, baseUrl: String, cfHost: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val baseHost    = extractHost(baseUrl) ?: cfHost
                val hostToUse   = if (isRawIp(baseHost)) cfHost else baseHost
                "$hostToUse$url"
            }
            else -> {
                val baseDir = baseUrl.substringBeforeLast("/")
                "$baseDir/$url"
            }
        }
    }

    // =========================================================================
    //  EMIT PLAYLIST
    // =========================================================================

    @Suppress("DEPRECATION")
    private fun emitPlayList(
        segments      : List<PlayListItem>,
        quality       : Int,
        label         : String,
        baseDomain    : String,
        playerHeaders : Map<String, String>,
        callback      : (ExtractorLink) -> Unit
    ) {
        val qualityValue = if (quality > 0) quality else Qualities.Unknown.value
        val linkName     = "Strp2p [Rewritten $label]"

        callback(
            ExtractorLinkPlayList(
                source   = name,
                name     = linkName,
                playlist = segments,
                referer  = "$baseDomain/",
                quality  = qualityValue,
                isM3u8   = false,
                headers  = playerHeaders
            )
        )
    }

    // =========================================================================
    //  DOMAIN HELPERS
    // =========================================================================

    private fun extractHost(url: String): String? =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)

    private fun extractPath(url: String): String? {
        val idx = url.indexOf("/", url.indexOf("://") + 3)
        return if (idx >= 0) url.substring(idx) else null
    }

    private fun isRawIp(url: String): Boolean {
        val host = Regex("""https?://([^/:]+)""").find(url)
            ?.groupValues?.get(1) ?: return false
        return host.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))
    }

    // =========================================================================
    //  TIKTOK CDN BASE EXTRACTOR
    // =========================================================================

    private fun extractTiktokCdnBase(jsonObj: JSONObject?, rawJson: String): String {
        try {
            val scRaw = jsonObj?.optString("streamingConfig") ?: ""
            if (scRaw.isNotBlank()) {
                val sc     = JSONObject(scRaw)
                val domain = sc.optJSONObject("adjust")
                    ?.optJSONObject("Tiktok")
                    ?.optString("domain", "") ?: ""
                if (domain.isNotBlank()) {
                    val base = domain.trimEnd('/')
                    return if (base.startsWith("http")) base else "https://$base"
                }
            }
        } catch (_: Exception) {}

        val m = Regex(""""Tiktok"\s*:\s*\{[^}]*"domain"\s*:\s*"([^"]+)"""")
            .find(rawJson)?.groupValues?.get(1)
        if (!m.isNullOrBlank()) {
            val base = m.trimEnd('/')
            return if (base.startsWith("http")) base else "https://$base"
        }

        try {
            val cfDomain = jsonObj?.optJSONObject("metric")
                ?.optString("cfDomain", "") ?: ""
            if (cfDomain.isNotBlank()) {
                return "https://soq.$cfDomain"
            }
        } catch (_: Exception) {}

        return "https://soq.corporateoperations.sbs"
    }

    // =========================================================================
    //  CRYPTO UTILITIES
    // =========================================================================

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
