package com.IdlixProvider

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class IdlixProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(IdlixProvider())
        registerExtractorAPI(Majorplay())
        IdlixPlayback.trace("plugin.loaded", IdlixPlayback.BUILD_MARKER)
    }
}
