package com.PODJAV

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PodjavPlugin : Plugin() {
    override fun load(context: Context) {
        // Fungsi ini digunakan untuk mendaftarkan provider kita ke dalam sistem Cloudstream
        // Nama class harus sesuai dengan yang ada di PodjavProvider.kt
        registerMainAPI(PodjavProvider())
    }
}
