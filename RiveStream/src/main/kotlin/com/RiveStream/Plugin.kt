package com.RiveStream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RiveStreamPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan API utama RiveStream ke dalam sistem core Cloudstream
        registerMainAPI(RiveStreamProvider())
        // Mendaftarkan mesin pemrosesan link (Extractor) Streamtape dan VOE
        registerExtractorAPI(TpeadExtractor())
        registerExtractorAPI(VoeExtractor())
    }
}
