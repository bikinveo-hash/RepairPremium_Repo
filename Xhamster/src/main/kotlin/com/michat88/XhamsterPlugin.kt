package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XhamsterPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan kelas Xhamster agar dibaca oleh CloudStream
        registerMainAPI(Xhamster())
    }
}
