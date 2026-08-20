package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun load(context: Context) {
        // Identity persisten MovieBox disiapkan sebelum request pertama.
        AdiFilmSemiExtractor.attachContext(context)

        // Hanya mendaftarkan provider utama
        registerMainAPI(AdiFilmSemi())
    }
}
