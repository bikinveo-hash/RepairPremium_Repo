package com.Moviebox

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MovieboxPlugin : Plugin() {
    override fun load(context: Context) {
        // Identity persisten per-instalasi disiapkan sebelum provider dipakai.
        MovieBoxProvider.attachContext(context)

        // Registrasi provider utama
        registerMainAPI(MovieBoxProvider())
    }
}
