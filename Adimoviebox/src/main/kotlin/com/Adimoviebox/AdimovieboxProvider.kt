package com.Adimoviebox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AdimovieboxProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(AdimovieboxPlaybackV2Provider())
    }
}
