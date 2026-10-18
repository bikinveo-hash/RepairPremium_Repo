package com.michat88

import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import java.text.SimpleDateFormat
import java.util.Locale

// ================== HANYA FUNGSI YANG MASIH DIPAKAI ==================
// File ini sekarang hanya menyimpan helper yang dibutuhkan katalog TMDB dan MovieBox.

/** Dipakai di AdiFilmSemi.load() untuk menandai episode/film yang belum rilis. */
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
