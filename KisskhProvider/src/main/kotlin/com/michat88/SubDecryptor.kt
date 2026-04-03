package com.michat88

import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val KEY1 = "AmSmZVcH93UQUezi"
private const val KEY2 = "8056483646328763"
private const val KEY3 = "sWODXX04QRTkHdlZ"

// Rahasia IV yang gua bongkar dari IntArray di file JAR
private const val IV1 = "ReBKWW8cqdjPEnF6"
private const val IV2 = "6852612370185273"
private const val IV3 = "8pwhapJeC4hrS9hO"

fun decrypt(encryptedB64: String): String {
    val keyIvPairs = listOf(
        Pair(KEY1.toByteArray(), IV1.toByteArray()),
        Pair(KEY2.toByteArray(), IV2.toByteArray()),
        Pair(KEY3.toByteArray(), IV3.toByteArray())
    )

    val encryptedBytes = try { base64DecodeArray(encryptedB64) } catch (e: Exception) { return "" }

    for ((keyBytes, ivBytes) in keyIvPairs) {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
        } catch (ex: Exception) { continue }
    }
    return "Decryption failed"
}
