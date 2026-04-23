package com.adixtream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AdiXtreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider VidSrcProvider agar dikenali oleh Cloudstream
        registerMainAPI(VidSrcProvider())
    }
}
