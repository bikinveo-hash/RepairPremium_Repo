package com.RiveStream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RiveStreamPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan API utama RiveStream (Scraper TMDB & Core Engine) ke Cloudstream
        registerMainAPI(RiveStreamProvider())
        
        // Mendaftarkan Custom Extractor Streamtape (Tpead) yang sudah kita lengkapi dengan Anti-Honeypot
        registerExtractorAPI(TpeadExtractor())
    }
}
