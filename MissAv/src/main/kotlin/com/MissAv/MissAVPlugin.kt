package com.MissAv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MissAvPlugin : Plugin() {
    override fun load(context: Context) {
        // Daftarkan Provider kita di sini
        registerMainAPI(MissAvProvider())
    }
}
