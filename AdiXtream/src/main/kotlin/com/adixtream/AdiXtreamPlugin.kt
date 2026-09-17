package com.adixtream

import android.content.Context
import com.Adicinemax21.Adicinemax21VidSrcShared
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiXtreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Siapkan exact runtime profile MovieBox sebelum playback pertama.
        AdiXtreamExtractor.attachContext(context)

        // Reuse exact VidSrc/WebView/WASM engine milik Adicinemax21.
        Adicinemax21VidSrcShared.attachContext(context)

        // Provider utama: MovieBox/VidSrc proven + Idlix prefetch/cache.
        registerMainAPI(AdiXtreamPrefetchProvider())
    }
}
