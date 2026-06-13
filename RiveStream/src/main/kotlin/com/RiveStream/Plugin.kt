package com.RiveStream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RiveStreamPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider utama RiveStream ke sistem core
        registerMainAPI(RiveStreamProvider())
        
        // Mendaftarkan custom extractor super dewa untuk bypass Streamtape (tpead.net)
        registerExtractorAPI(TpeadExtractor())
    }
}
