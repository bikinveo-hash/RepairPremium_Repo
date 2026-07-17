package com.OppaDrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OppaDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider utama kita saat plugin dimuat oleh aplikasi
        registerMainAPI(OppaDrama())
    }
}
