package com.RiveStream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RiveStreamPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider utama RiveStream ke sistem core
        registerMainAPI(RiveStreamProvider())
        
        // Mendaftarkan seluruh custom extractor yang ada di dalam berkas Extractor.kt
        registerExtractorAPI(TpeadExtractor())
        registerExtractorAPI(VoeExtractor())
    }
}
