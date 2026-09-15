package com.adixtream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiXtreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Siapkan exact runtime profile MovieBox sebelum playback pertama.
        AdiXtreamExtractor.attachContext(context)

        // Idlix memanggil Majorplay secara langsung dari AdiXtreamIdlix.kt.
        registerMainAPI(AdiXtream())
    }
}
