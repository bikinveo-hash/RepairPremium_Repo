package com.Cinemacity

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemacityPlugin : Plugin() {

    companion object {
        @Volatile var cfCookies: String? = null
        @Volatile var cfCookieHost: String? = null
        @Volatile var cfUserAgent: String? = null
    }

    override fun load(context: Context) {
        // [REVISI]: Menggunakan nama class MainAPI yang benar
        registerMainAPI(Cinemacity())
    }
}
