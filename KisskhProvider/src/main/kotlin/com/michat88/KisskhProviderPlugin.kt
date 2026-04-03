package com.michat88

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KisskhProviderPlugin: BasePlugin() {
    override fun load() {
        // Mendaftarkan provider Kisskh ke dalam sistem
        registerMainAPI(KisskhProvider())
    }
}
