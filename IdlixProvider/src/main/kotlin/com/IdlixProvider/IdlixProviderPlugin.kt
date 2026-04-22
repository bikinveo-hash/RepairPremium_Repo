package com.IdlixProvider

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class IdlixProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(IdlixProvider())
        [span_7](start_span)registerExtractorAPI(Jeniusplay()) // Tetap ada untuk film lama[span_7](end_span)
        registerExtractorAPI(Majorplay())  // Ekstraktor baru
    }
}
