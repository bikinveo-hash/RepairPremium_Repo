package com.KlikXXI

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KlikXXIPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan MainAPI KlikXXI
        registerMainAPI(KlikXXI())
        
        // Mendaftarkan Extractor Strp2p yang baru kita buat
        registerExtractorAPI(Strp2p())
    }
}
