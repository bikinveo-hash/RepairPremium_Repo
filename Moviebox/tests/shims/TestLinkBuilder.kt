@file:JvmName("ExtractorApiKt")
package com.lagradost.cloudstream3.utils

// Match the real builder without initializing every unrelated CloudStream extractor in a JVM test.
// The real ExtractorLink class is retained; production is compiled separately without this shim.
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
suspend fun newExtractorLink(source: String, name: String, url: String,
    type: ExtractorLinkType? = null, initializer: suspend ExtractorLink.() -> Unit = {}): ExtractorLink {
    return ExtractorLink(source=source,name=name,url=url,referer="",quality=0,
        headers=emptyMap(),extractorData=null,type=type ?: ExtractorLinkType.VIDEO,audioTracks=emptyList()).apply { initializer() }
}
