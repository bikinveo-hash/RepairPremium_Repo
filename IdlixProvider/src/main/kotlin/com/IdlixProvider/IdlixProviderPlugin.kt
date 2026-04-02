package com.lagradost.cloudstream3.plugins // Sesuaikan nama package dengan struktur folder proyekmu!

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IdlixProviderPlugin : Plugin() {
    // Tuliskan android.content.Context secara langsung di sini tanpa melakukan import di atas
    override fun load(context: android.content.Context) {
        // Mendaftarkan API Website IDLIX
        registerMainAPI(IdlixProvider())
        
        // Mendaftarkan Extractor JeniusPlay
        registerExtractorAPI(IdlixExtractor())
    }
}
