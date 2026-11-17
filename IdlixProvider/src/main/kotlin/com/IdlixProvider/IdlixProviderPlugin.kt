package com.IdlixProvider

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class IdlixProviderPlugin: Plugin() {
    override fun load(context: Context) {
        IdlixPlayback.applicationContext = context.applicationContext
        registerMainAPI(IdlixProvider())
        registerExtractorAPI(Majorplay())
        IdlixPlayback.trace("plugin.loaded", IdlixPlayback.BUILD_MARKER)
    }
}
