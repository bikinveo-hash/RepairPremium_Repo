package com.OppaDrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OppaDramaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        // Main provider
        registerMainAPI(OppaDramaProvider())

        // Custom extractors (overrides/aliases for sites used by oppadrama)
        registerExtractorAPI(Smoothpre())        // alias for vidhidepro (EarnVids)
        registerExtractorAPI(BuzzServer())        // buzzheavier.com direct download
        registerExtractorAPI(EmturbovidExtractor()) // emturbovid.com m3u8 resolver
    }
}
