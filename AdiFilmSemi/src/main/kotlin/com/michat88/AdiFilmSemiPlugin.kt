package com.michat88

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun beforeUnload() {
        com.michat88.sources.LocalProxy.stop()
    }

    override fun load(context: Context) {
        AdiFilmSemiMovieboxEngine.attachContext(context)
        openSettings = { settingsContext -> AdiFilmSemiMovieboxDiagnostics.show(settingsContext) }
        registerMainAPI(AdiFilmSemi())
    }
}
