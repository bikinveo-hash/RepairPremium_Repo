package com.michat88

import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// KUNCI RAHASIA (KEY)
private const val KEY1 = "AmSmZVcH93UQUezi"
private const val KEY2 = "8056483646328763"
private const val KEY3 = "sWODXX04QRTkHdlZ"

// KUNCI RAHASIA (IV) - Terbongkar dari IntArray!
private const val IV1 = "ReBKWW8cqdjPEnF6"
private const val IV2 = "6852612370185273"
private const val IV3 = "8pwhapJeC4hrS9hO"

fun decrypt(encryptedB64: String): String {
    // Memasangkan Key dan IV masing-masing menjadi list siap pakai
    val keyIvPairs = listOf(
        Pair(KEY1.toByteArray(Charsets.UTF_8), IV1.toByteArray(Charsets.UTF_8)),
        Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray(Charsets.UTF_8)),
        Pair(KEY3.toByteArray(Charsets.UTF_8), IV3.toByteArray(Charsets.UTF_8))
    )

    // Decode Base64 dari subtitle yang terenkripsi
    val encryptedBytes = try {
        base64DecodeArray(encryptedB64) 
    } catch (e: Exception) {
        return "Decryption failed: Invalid Base64"
    }

    // Algoritma Brute-force: Coba semua kunci satu per satu sampai berhasil
    for ((keyBytes, ivBytes) in keyIvPairs) {
        try {
            return decryptWithKeyIv(keyBytes, ivBytes, encryptedBytes)
        } catch (ex: Exception) {
            // Jika gagal, abaikan error dan lanjut coba kunci berikutnya
            continue 
        }
    }
    return "Decryption failed: All keys/IVs failed"
}

// Mesin utama pembuka gembok AES
private fun decryptWithKeyIv(keyBytes: ByteArray, ivBytes: ByteArray, encryptedBytes: ByteArray): String {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
    return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
}
