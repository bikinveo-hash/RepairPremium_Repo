package com.Adicinemax21

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import java.text.SimpleDateFormat
import java.util.*

// ================== HANYA FUNGSI YANG MASIH DIPAKAI ==================

/**
 * Tetap dipertahankan karena dipakai di Adicinemax21.load()
 */
fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        unixTimeMS < dateTime
    } catch (t: Throwable) {
        logError(t)
        false
    }
}

/**
 * Dipakai untuk menggabungkan path relatif di sumber (Adimoviebox, dll)
 */
fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

/**
 * Dipakai untuk mendapatkan kualitas dari string (Adimoviebox, Adimoviebox2)
 */
fun getQualityFromName(qualityName: String?): Int {
    if (qualityName == null)
        return Qualities.Unknown.value

    val match = qualityName.lowercase().replace("p", "").trim()
    return when (match) {
        "4k" -> Qualities.P2160
        else -> null
    }?.value ?: match.toIntOrNull() ?: Qualities.Unknown.value
}

// Fungsi Base64 untuk Adimoviebox2Helper (ada di Extractor)
fun base64Decode(string: String): String {
    val bytes = Base64.decode(string, Base64.DEFAULT)
    return bytes.toString(Charsets.ISO_8859_1)  // Sesuai implementasi asli
}

fun base64Encode(array: ByteArray): String {
    return Base64.encodeToString(array, Base64.DEFAULT)
}

fun base64DecodeArray(string: String): ByteArray {
    return Base64.decode(string, Base64.DEFAULT)
}
