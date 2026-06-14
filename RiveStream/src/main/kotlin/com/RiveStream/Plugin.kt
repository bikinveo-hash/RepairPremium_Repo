package com.RiveStream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RiveStreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Daftarkan provider utama: scraper TMDB + PrimeSrc engine untuk load link
        registerMainAPI(RiveStreamProvider())
    }
}
