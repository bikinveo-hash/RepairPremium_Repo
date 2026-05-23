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
        registerExtractorApi(P2PExtractor())
        registerExtractorApi(EmturbovidExtractor())
        registerExtractorApi(F16Extractor())
    }
}
