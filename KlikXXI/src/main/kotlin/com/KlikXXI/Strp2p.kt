package com.KlikXXI

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Strp2p : ExtractorApi() {
    override val name = "Strp2p"
    override val mainUrl = "https://klikxxi.strp2p.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Mengambil ID video dari URL (misal: /e/q5sc8o)
        val videoId = url.substringAfter("/e/").substringBefore("?")
        val videoApiUrl = "$mainUrl/api/v1/video?id=$videoId"
        
        // Melakukan request ke endpoint /api/v1/video
        val responseText = app.get(videoApiUrl, referer = referer ?: mainUrl).text.trim()

        if (responseText.isBlank() || responseText.startsWith("{")) {
            return
        }

        try {
            // Key dan IV statis dari hasil bypass DevTools
            val key = "kiemtienmua911ca".toByteArray()
            val iv = "1234567890oiuytr".toByteArray()

            // Proses dekripsi AES-CBC PKCS5Padding
            val decryptedJson = decryptAesCbc(responseText, key, iv)
            
            // Menangkap tautan m3u8 dari JSON yang terdekripsi
            val videoUrl = Regex(""""(?:url|file|link)"\s*:\s*"([^"]+)"""").find(decryptedJson)?.groupValues?.get(1)

            if (!videoUrl.isNullOrBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = this.name,
                        name = "KlikXXI HD",
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Konversi Hexadecimal String menjadi ByteArray
     */
    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string harus genap" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    /**
     * Dekripsi AES-CBC secara native
     */
    private fun decryptAesCbc(hexString: String, key: ByteArray, iv: ByteArray): String {
        val encryptedBytes = hexString.decodeHex()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(encryptedBytes))
    }
}
