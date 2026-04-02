package com.lagradost.cloudstream3.plugins // Sesuaikan nama package dengan struktur folder proyekmu!

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IdlixProviderPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan API Website IDLIX
        registerMainAPI(IdlixProvider())
        
        // Mendaftarkan Extractor JeniusPlay
        registerExtractorAPI(IdlixExtractor())
    }
}
