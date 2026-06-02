package com.LayarKacaProvider

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LayarKacaPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan Main Provider (LayarKaca21)
        registerMainAPI(LayarKacaProvider())

        // Mendaftarkan semua Extractor tambahan agar terbaca oleh sistem CloudStream
        registerExtractorAPI(Lk21TurboExtractor())
        registerExtractorAPI(HowNetworkExtractor())
        registerExtractorAPI(CastExtractor())
        
        // Mendaftarkan Extractor Hydrax (Abyss) Native
        registerExtractorAPI(AbyssExtractor())
    }
}
