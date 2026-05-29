package com.AdiDrakor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AdiDrakorPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider AdiDrakor agar dikenali oleh Cloudstream
        registerMainAPI(AdiDrakor())
    }
}
