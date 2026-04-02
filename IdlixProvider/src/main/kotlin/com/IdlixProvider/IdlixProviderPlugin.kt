package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixProviderPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        // Mendaftarkan API Website IDLIX
        registerMainAPI(IdlixProvider())
        
        // Mendaftarkan Extractor JeniusPlay
        registerExtractorAPI(IdlixExtractor())
    }
}
