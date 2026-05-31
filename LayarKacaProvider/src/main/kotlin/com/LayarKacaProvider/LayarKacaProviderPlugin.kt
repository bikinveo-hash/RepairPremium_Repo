package com.LayarKacaProvider

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LayarKacaPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan Main Provider (LayarKaca21)
        registerMainAPI(LayarKacaProvider())

        // Mendaftarkan Extractor tambahan agar terbaca oleh loadExtractor()
        registerExtractorAPI(Lk21TurboExtractor())
        registerExtractorAPI(HowNetworkExtractor())
    }
}
