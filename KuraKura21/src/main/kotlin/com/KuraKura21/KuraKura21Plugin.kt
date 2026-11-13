package com.kurakura21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KuraKura21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KuraKura21Provider())
    }
}
