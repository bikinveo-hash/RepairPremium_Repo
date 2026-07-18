package com.OppaDrama

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class OppaDramaPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider utama ke dalam ekosistem Cloudstream
        registerMainAPI(OppaDramaProvider())
    }
}
