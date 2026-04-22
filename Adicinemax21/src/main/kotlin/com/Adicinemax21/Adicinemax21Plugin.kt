package com.Adicinemax21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Adicinemax21Plugin : Plugin() {
    override fun load(context: Context) {
        // Register Main Provider
        registerMainAPI(Adicinemax21())
        
        // Register Extractors
        registerExtractorAPI(Majorplay())
    }
}
