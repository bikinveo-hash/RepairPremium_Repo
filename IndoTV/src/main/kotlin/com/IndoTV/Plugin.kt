package com.IndoTV

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IndoTVPlugin: Plugin() {
    override fun load(context: Context) {
        // Fungsi ini wajib ada untuk mendaftarkan provider IndoTV 
        // agar muncul di daftar ekstensi aplikasi.
        registerMainAPI(IndoTV())
    }
}
