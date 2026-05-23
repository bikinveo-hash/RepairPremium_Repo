package com.LayarKacaProvider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LayarKacaPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan Provider Utama
        registerMainAPI(LayarKacaProvider())
        
        // Mendaftarkan 3 Extractor Utama (Tanpa Hydrax)
        registerExtractorAPI(P2PExtractor())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(F16Extractor())
    }
}
