package com.Adimoviebox

import android.content.Context
import com.Adicinemax21.MovieBoxV2Shared
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdimovieboxProvider : Plugin() {
    override fun load(context: Context) {
        MovieBoxV2Shared.attachContext(context)
        registerMainAPI(AdimovieboxSharedPlaybackProvider())
    }
}
