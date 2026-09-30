package com.Adicinemax21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Adicinemax21Plugin : Plugin() {
    override fun load(context: Context) {
        Adicinemax21Extractor.attachContext(context)
        MovieBoxV2Shared.attachContext(context)
        Adicinemax21VidSrc.attachContext(context)

        // Tiga sumber berdiri sendiri di plugin ini: MovieBox, Idlix, VidSrc.
        registerMainAPI(Adicinemax21PlaybackFixedProvider())
    }
}
