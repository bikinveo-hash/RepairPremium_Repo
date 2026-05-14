package com.LayarKacaProvider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LayarKacaPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan Provider Utama
        registerMainAPI(LayarKacaProvider())
        
        // Mendaftarkan Semua Extractor Sesuai Urutan
        registerExtractorAPI(P2PExtractor())        // Server P2P
        registerExtractorAPI(EmturbovidExtractor()) // Server Turbovip
        registerExtractorAPI(F16Extractor())        // Server Cast
        registerExtractorAPI(HydraxExtractor())     // Server Hydrax (Abysscdn)
    }
}
