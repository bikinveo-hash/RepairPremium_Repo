package com.Moviebox

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MovieboxPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider Moviebox agar terbaca oleh Cloudstream
        registerMainAPI(MovieboxProvider())
    }
}
