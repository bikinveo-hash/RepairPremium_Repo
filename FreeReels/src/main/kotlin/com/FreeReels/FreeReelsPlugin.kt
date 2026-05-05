package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FreeReelsPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider FreeReels
        registerMainAPI(FreeReels())
    }
}
