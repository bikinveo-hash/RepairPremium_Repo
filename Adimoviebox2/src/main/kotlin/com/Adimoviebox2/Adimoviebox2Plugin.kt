package com.Adimoviebox2

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Adimoviebox2Plugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider Adimoviebox2 agar bisa dibaca dan digunakan oleh aplikasi Cloudstream
        registerMainAPI(Adimoviebox2Provider())
    }
}
