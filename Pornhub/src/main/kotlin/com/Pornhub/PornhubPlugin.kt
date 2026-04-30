package com.Pornhub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PornhubPlugin: Plugin() {
    override fun load(context: Context) {
        // Daftarkan provider utama kita
        registerMainAPI(PornhubProvider())
    }
}
