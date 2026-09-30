// use an integer for version numbers
version = 9

// Reuse ONLY the proven shared MovieBox playback engine from Adicinemax21.
// The standalone Adimoviebox catalog/search/detail implementation remains local.
android {
    sourceSets.getByName("main").java.apply {
        srcDir(rootProject.file("Adicinemax21/src/main/kotlin"))
        filter.include(
            "com/Adicinemax21/MovieBoxV2Shared.kt",
            "com/lagradost/cloudstream3/utils/MovieBoxSubtitleCompat.kt",
        )
    }
}

cloudstream {
    language = "id"
    authors = listOf("aldry84")
    status = 1
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
    )
    iconUrl = "https://moviebox.ph/favicon.ico"
}
