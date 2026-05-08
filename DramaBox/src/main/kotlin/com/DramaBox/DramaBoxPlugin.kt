package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramaBoxPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan Provider yang sudah kita rakit tadi
        registerMainAPI(DramaBoxProvider())
    }
}
