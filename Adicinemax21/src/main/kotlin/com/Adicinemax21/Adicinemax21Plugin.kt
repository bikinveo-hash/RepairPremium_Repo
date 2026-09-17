package com.Adicinemax21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Adicinemax21Plugin : Plugin() {
    override fun load(context: Context) {
        // Identity persisten MovieBox disiapkan sebelum request pertama.
        Adicinemax21Extractor.attachContext(context)

        // Context hanya untuk engine VidSrc (WebView/WASM resolver dari Streamzy).
        Adicinemax21VidSrc.attachContext(context)

        // Provider utama: MovieBox/VidSrc proven + Idlix prefetch/cache.
        registerMainAPI(Adicinemax21PrefetchProvider())
    }
}
