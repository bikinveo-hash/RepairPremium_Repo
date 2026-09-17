package com.michat88

import android.content.Context
import com.Adicinemax21.Adicinemax21VidSrcShared
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun load(context: Context) {
        // Exact MovieBox runtime profile harus siap sebelum request playback pertama.
        AdiFilmSemiExtractor.attachContext(context)

        // Reuse exact VidSrc/WebView/WASM engine milik Adicinemax21.
        Adicinemax21VidSrcShared.attachContext(context)

        // Provider utama: MovieBox/VidSrc proven + Idlix prefetch/cache.
        registerMainAPI(AdiFilmSemiPrefetchProvider())
    }
}
