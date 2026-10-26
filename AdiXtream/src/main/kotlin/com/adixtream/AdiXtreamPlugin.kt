package com.adixtream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiXtreamPlugin : Plugin() {
    override fun beforeUnload() {
        com.adixtream.LocalProxy.stop()
    }

    override fun load(context: Context) {
        AdiXtreamMovieboxEngine.attachContext(context)
        openSettings = { settingsContext -> AdiXtreamMovieboxDiagnostics.show(settingsContext) }
        registerMainAPI(AdiXtream())
    }
}
