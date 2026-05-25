package com.JuraganFilm

import com.lagradost.cloudstream3.APIHolder

class JuraganFilmPlugin {
    init {
        // Daftarkan provider langsung ke APIHolder
        APIHolder.allProviders.add(JuraganFilmProvider())
    }
}
