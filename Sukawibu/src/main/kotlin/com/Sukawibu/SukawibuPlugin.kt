package com.Sukawibu

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SukawibuPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider/sumber website Sukawibu agar terbaca oleh aplikasi
        registerMainAPI(SukawibuProvider())
    }
}
