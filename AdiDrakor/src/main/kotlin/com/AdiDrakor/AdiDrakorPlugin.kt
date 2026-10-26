package com.AdiDrakor

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiDrakorPlugin : Plugin() {
    override fun beforeUnload() {
        com.AdiDrakor.LocalProxy.stop()
    }

    override fun load(context: Context) {
        AdiDrakorMovieboxEngine.attachContext(context)
        registerMainAPI(AdiDrakor())
    }
}
