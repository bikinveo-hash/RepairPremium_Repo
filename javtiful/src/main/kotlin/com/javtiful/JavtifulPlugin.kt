package com.javtiful

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavtifulPlugin: Plugin() {
    override fun load(context: Context) {
        // Registrasi utama API provider agar muncul di daftar ekstensi
        registerMainAPI(JavtifulProvider())
    }
}
