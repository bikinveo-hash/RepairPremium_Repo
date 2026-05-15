package com.ReelShort

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ReelShortPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider ReelShort agar muncul di Cloudstream
        registerMainAPI(ReelShortProvider())
    }
}
