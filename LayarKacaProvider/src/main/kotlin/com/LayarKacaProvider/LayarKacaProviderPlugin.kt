package com.LayarKacaProvider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LayarKacaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LayarKacaProvider())
        
        registerExtractorAPI(P2PExtractor())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(F16Extractor())
    }
}
