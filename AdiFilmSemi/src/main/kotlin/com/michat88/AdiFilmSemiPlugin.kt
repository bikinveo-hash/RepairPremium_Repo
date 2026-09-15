package com.michat88

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun load(context: Context) {
        // Exact MovieBox runtime profile harus siap sebelum request playback pertama.
        AdiFilmSemiExtractor.attachContext(context)

        // Provider utama. Idlix memanggil Majorplay secara langsung dari
        // AdiFilmSemiIdlix.kt sehingga tidak membutuhkan registerExtractorAPI.
        registerMainAPI(AdiFilmSemi())
    }
}
