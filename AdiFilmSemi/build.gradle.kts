// use an integer for version numbers
version = 14


// Reuse ONLY the shared playback engine source files from Adicinemax21.
// Do not depend on the whole Adicinemax21 project because that also packages
// Adicinemax21Plugin (@CloudstreamPlugin) and can hijack plugin discovery.
android {
    sourceSets.getByName("main").java.apply {
        srcDir(rootProject.file("Adicinemax21/src/main/kotlin"))
        filter.include(
            "com/Adicinemax21/Adicinemax21VidSrc.kt",
            "com/Adicinemax21/Adicinemax21VidSrcResolver.kt",
            "com/Adicinemax21/Adicinemax21VidSrcShared.kt",
            "com/Adicinemax21/Adicinemax21IdlixShared.kt",
        )
    }
}

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

    // description = "Lorem Ipsum"
    authors = listOf("aldry84")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
    )


    iconUrl = "https://klikxxi.me/wp-content/uploads/2024/02/cropped-site-icon.png"

}
