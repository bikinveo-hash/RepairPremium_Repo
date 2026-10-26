package com.Adicinemax21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Adicinemax21Plugin : Plugin() {
    override fun beforeUnload() {
        com.Adicinemax21.LocalProxy.stop()
    }

    override fun load(context: Context) {
        Adicinemax21MovieboxEngine.attachContext(context)
        openSettings = { settingsContext -> Adicinemax21MovieboxDiagnostics.show(settingsContext) }
        registerMainAPI(Adicinemax21())
    }
}
