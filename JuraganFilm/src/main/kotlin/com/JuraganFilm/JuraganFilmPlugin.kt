package com.JuraganFilm

import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudStreamPlugin
class JuraganFilmPlugin : Plugin {
    override fun load() {
        // Daftarkan provider utama JuraganFilm
        registerMainAPI(JuraganFilmProvider())
    }
}
