package com.Rebahin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RebahinPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan RebahinProvider agar terbaca oleh aplikasi Cloudstream
        registerMainAPI(RebahinProvider())
    }
}
