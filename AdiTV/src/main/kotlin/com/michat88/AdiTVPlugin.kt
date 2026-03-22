package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AdiTVPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider AdiTV agar bisa dibaca oleh aplikasi
        registerMainAPI(AdiTV())
    }
}
