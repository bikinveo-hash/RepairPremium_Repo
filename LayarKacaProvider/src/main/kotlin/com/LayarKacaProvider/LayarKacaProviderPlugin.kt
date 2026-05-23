package com.LayarKacaProvider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LayarKacaPlugin : Plugin() {
    override fun load(context: Context) {
        // Hanya provider
        registerMainAPI(LayarKacaProvider())
        
        // Hanya TurboVIP untuk pengujian
        registerExtractorAPI(Lk21TurboExtractor())
    }
}
