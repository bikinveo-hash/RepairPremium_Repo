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

class Strp2p : ExtractorApi() {
    override val name            = "Strp2p Premium"
    override val mainUrl         = "klikxxi.shop"
    override val requiresReferer = true

    // Hasil Dekripsi Operasi Kriptografi Berkas index-*.js Aktual
    private val AES_KEY = "efkegitfknmua911ca"
    private val AES_IV  = "1234567890oiuytr"
    
    private val UA = "Mozilla/5.0 (Linux; Android 10; K) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/139.0.0.0 Mobile Safari/537.36"

    private val TAG = "Strp2p_SPA_Crypt"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "══════════ STRP2P DECRYPTION CORE START ══════════")
        val baseDomain = Regex("""^(https?://[^/#?]+)""").find(url)?.groupValues?.get(1) ?: "https://klikxxi.strp2p.site"
        val videoId = Regex("""[/#]([A-Za-z0-9]{4,})$""").find(url.substringBefore("?"))?.groupValues?.get(1)?.trim() ?: return

        // Penentuan Host Referer Asli secara Dinamis
        val parentHost = referer?.let { Regex("""https?://([^/]+)""").find(it)?.groupValues?.get(1) } ?: "klikxxi.shop"
        val apiUrl = "$baseDomain/api/v1/video?id=$videoId&w=360&h=800&r=$parentHost"
        
        val playerHeaders = mapOf(
            "User-Agent" to UA, 
            "Referer" to "$baseDomain/", 
            "Origin" to baseDomain,
            "Accept" to "*/*"
        )

        Log.d(TAG, "[+] Menghubungi API Endpoint: $apiUrl")
        val resp = try { app.get(apiUrl, headers = playerHeaders) } catch (e: Exception) { return }
        if (resp.code != 200) {
            Log.e(TAG, "[-] Gagal mendapatkan respon valid dari API. HTTP Code: ${resp.code}")
            return
        }

        val hexStr = resp.text.trim()
        if (hexStr.isBlank() || hexStr.length % 2 != 0) {
            Log.e(TAG, "[-] Payload data bukan struktur Hex String yang valid.")
            return
        }

        // Eksekusi Pembongkaran Payload Berbasis Kunci Hasil Reverse Engineering
        val rawJson = try { 
            decryptAesCbc(hexStr, AES_KEY.toByteArray(), AES_IV.toByteArray()) 
        } catch (e: Exception) { 
            Log.e(TAG, "[-] Gagal melakukan dekripsi AES-CBC: ${e.message}")
            return 
        }
        
        Log.d(TAG, "[+] Sukses Mendekripsi Payload JSON.")
        val jsonObj = try { JSONObject(rawJson) } catch (e: Exception) { null }

        // ── SERVER UTAMA: DOMAIN SWAPPING ROUTINE (Cloudflare Edge) ──
        val cfRaw     = Regex(""""cf"\s*:\s*"([^"]+)"""").find(rawJson)?.groupValues?.get(1)?.unescapeJsonSlashes()
        val sourceRaw = Regex(""""source"\s*:\s*"([^"]+)"""").find(rawJson)?.groupValues?.get(1)?.unescapeJsonSlashes()

        val cfHost  = cfRaw?.let { Regex("""^(https?://[^/]+)""").find(it)?.groupValues?.get(1) }
        val srcPath = sourceRaw?.let { 
            val idx = it.indexOf("/", it.indexOf("://") + 3)
            if (idx >= 0) it.substring(idx) else null
        }

        val swappedUrl = if (cfHost != null && srcPath != null && sourceRaw != null && isRawIp(sourceRaw)) {
            "$cfHost$srcPath"
        } else cfRaw // Fallback langsung ke cf jika tidak mengandung IP mentah

        if (!swappedUrl.isNullOrBlank() && cfHost != null) {
            try {
                Log.d(TAG, "[+] Membaca Master Playlist HLS: $swappedUrl")
                val masterContent = app.get(swappedUrl, headers = playerHeaders).text
                
                if (masterContent.contains("#EXT-X-STREAM-INF")) {
                    var pendingH = -1
                    masterContent.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                            pendingH = Regex("""RESOLUTION=\d+x(\d+)""").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                        } else if (!trimmed.startsWith("#") && trimmed.isNotBlank() && pendingH != -1) {
                            val variantUrl = resolveAndRewrite(trimmed, swappedUrl, cfHost)
                            val label = if (pendingH > 0) "${pendingH}p" else "Auto"
                            
                            callback(newExtractorLink(name, "Strp2p - $label", variantUrl, ExtractorLinkType.M3U8) {
                                this.referer = "$baseDomain/"
                                this.quality = if (pendingH > 0) pendingH else Qualities.Unknown.value
                                this.headers = playerHeaders
                            })
                            pendingH = -1
                        }
                    }
                } else {
                    callback(newExtractorLink(name, "Strp2p - Direct", swappedUrl, ExtractorLinkType.M3U8) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "[-] Gagal parsing master HLS: ${e.message}")
            }
        }

        // ── SERVER CADANGAN 1: GOOGLE DRIVE FALLBACK HLS ──
        val googleUrl = Regex(""""hlsVideoGoogle"\s*:\s*"([^"]+)"""").find(rawJson)?.groupValues?.get(1)?.unescapeJsonSlashes()
        if (!googleUrl.isNullOrBlank()) {
            Log.d(TAG, "[+] Menemukan Jalur Google Drive Fallback HLS")
            callback(newExtractorLink(name, "Strp2p - Google Backup", googleUrl, ExtractorLinkType.M3U8) {
                this.referer = "$baseDomain/"
                this.quality = Qualities.Unknown.value
                this.headers = playerHeaders
            })
        }

        // ── SERVER CADANGAN 2: TIKTOK CDN BACKUP ──
        val tiktokPath = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""").find(rawJson)?.groupValues?.get(1)?.unescapeJsonSlashes()?.takeIf { ".m3u8" in it }
        if (tiktokPath != null) {
            val cdnBase = extractTiktokCdnBase(jsonObj, rawJson)
            val tiktokUrl = when {
                tiktokPath.startsWith("http") -> tiktokPath
                tiktokPath.startsWith("/")    -> "$cdnBase$tiktokPath"
                else                          -> "$cdnBase/$tiktokPath"
            }
            if (!isRawIp(tiktokUrl)) {
                Log.d(TAG, "[+] Menemukan Jalur TikTok CDN Fallback")
                callback(newExtractorLink(name, "Strp2p - TikTok CDN", tiktokUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$baseDomain/"
                    this.quality = Qualities.Unknown.value
                    this.headers = playerHeaders
                })
            }
        }
        Log.d(TAG, "══════════ STRP2P DECRYPTION CORE END ══════════")
    }

    private fun resolveAndRewrite(url: String, baseUrl: String, cfHost: String): String {
        val fullUrl = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val host = Regex("""^(https?://[^/]+)""").find(baseUrl)?.groupValues?.get(1) ?: cfHost
                "$host$url"
            }
            else -> "${baseUrl.substringBeforeLast("/")}/$url"
        }
        if (isRawIp(fullUrl)) {
            val path = fullUrl.substringAfter("://").substringAfter("/", "")
            return "$cfHost/$path"
        }
        return fullUrl
    }

    private fun isRawIp(url: String): Boolean {
        val host = Regex("""https?://([^/:]+)""").find(url)?.groupValues?.get(1) ?: return false
        return host.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))
    }

    private fun extractTiktokCdnBase(jsonObj: JSONObject?, rawJson: String): String {
        try {
            val scRaw = jsonObj?.optString("streamingConfig") ?: ""
            if (scRaw.isNotBlank()) {
                val domain = JSONObject(scRaw).optJSONObject("adjust")?.optJSONObject("Tiktok")?.optString("domain", "") ?: ""
                if (domain.isNotBlank()) return if (domain.startsWith("http")) domain.trimEnd('/') else "https://${domain.trimEnd('/')}"
            }
        } catch (_: Exception) {}

        val m = Regex(""""Tiktok"\s*:\s*\{[^}]*"domain"\s*:\s*"([^"]+)"""").find(rawJson)?.groupValues?.get(1)
        if (!m.isNullOrBlank()) return if (m.startsWith("http")) m.trimEnd('/') else "https://${m.trimEnd('/')}"

        return "https://soq.corporateoperations.sbs"
    }

    private fun String.unescapeJsonSlashes() = replace("\\/", "/")

    private fun String.decodeHex(): ByteArray {
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun decryptAesCbc(hex: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(hex.decodeHex()), Charsets.UTF_8)
    }
}
