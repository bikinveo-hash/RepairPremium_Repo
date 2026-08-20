package com.michat88

import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.Qualities
import java.text.SimpleDateFormat
import java.util.Locale

// ================== HANYA FUNGSI YANG MASIH DIPAKAI ==================
//
// DIHAPUS pada migrasi Adimoviebox/Adimoviebox2 -> MovieboxProvider:
//   - fixUrl()            : 0 pemanggil (dead code sejak awal)
//   - base64Decode()      : hanya dipakai Adimoviebox2Helper
//   - base64Encode()      : hanya dipakai Adimoviebox2Helper (Base64.DEFAULT, bikin newline)
//   - base64DecodeArray() : sudah ter-shadow versi privat di Adimoviebox2Helper
//
// Engine Moviebox yang baru memakai android.util.Base64 secara langsung
// dengan flag NO_WRAP, jadi tidak butuh wrapper di sini.

/**
 * Dipakai di AdiFilmSemi.load() untuk menandai episode/film yang belum rilis.
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
 * Dipakai oleh source Moviebox untuk memetakan field "resolutions" ke Qualities.
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
