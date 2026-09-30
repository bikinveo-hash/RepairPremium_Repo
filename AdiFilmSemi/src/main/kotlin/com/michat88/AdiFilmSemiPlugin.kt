package com.michat88

import android.content.Context
import com.Adicinemax21.Adicinemax21VidSrc
import com.Adicinemax21.MovieBoxV2Shared
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun load(context: Context) {
        AdiFilmSemiExtractor.attachContext(context)
        MovieBoxV2Shared.attachContext(context)
        Adicinemax21VidSrc.attachContext(context)

        // Tiga sumber berdiri sendiri di plugin ini: MovieBox, Idlix, VidSrc.
        registerMainAPI(AdiFilmSemiPlaybackFixedProvider())
    }
}
