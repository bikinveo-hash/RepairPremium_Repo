package com.JuraganFilm

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class JuraganFilmPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(JuraganFilmProvider())
        registerExtractorAPI(JuraganFilmExtractor())
    }
}
