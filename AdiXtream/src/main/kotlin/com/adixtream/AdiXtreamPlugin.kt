package com.adixtream

import android.content.Context
import com.Adicinemax21.Adicinemax21VidSrc
import com.Adicinemax21.MovieBoxV2Shared
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiXtreamPlugin : Plugin() {
    override fun load(context: Context) {
        AdiXtreamExtractor.attachContext(context)
        MovieBoxV2Shared.attachContext(context)
        Adicinemax21VidSrc.attachContext(context)

        // Tiga sumber berdiri sendiri di plugin ini: MovieBox, Idlix, VidSrc.
        registerMainAPI(AdiXtreamPlaybackFixedProvider())
    }
}
