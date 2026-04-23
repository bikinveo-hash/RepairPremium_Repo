// use an integer for version numbers
version = 8


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


    iconUrl = "https://raw.githubusercontent.com/michat88/Zaneta/aa97004f4d1d74ed69245e56da2c6ad3293b6106/Icons/Adi.png"

}
