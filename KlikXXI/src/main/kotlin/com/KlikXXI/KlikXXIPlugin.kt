package com.KlikXXI

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KlikXXIPlugin : Plugin() {
    override fun load(context: Context) {
        // Registrasi provider utama kita
        registerMainAPI(KlikXXI())
    }
}
