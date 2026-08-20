package com.AdiDrakor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AdiDrakorPlugin : Plugin() {
    override fun load(context: Context) {
        // Identity persisten MovieBox disiapkan sebelum request pertama.
        AdiDrakorExtractor.attachContext(context)

        // Hanya mendaftarkan provider utama
        registerMainAPI(AdiDrakor())
    }
}
