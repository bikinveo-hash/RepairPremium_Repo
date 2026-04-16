package com.yflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class YflixPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider Yflix ke Cloudstream
        registerMainAPI(Yflix())
    }
}
