package com.Moviebox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieboxPlugin : Plugin() {
    override fun load(context: Context) {
        MovieBoxProvider.attachContext(context)
        registerMainAPI(MovieBoxProvider())
    }
}
