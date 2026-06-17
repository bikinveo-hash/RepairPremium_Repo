package com.RiveStream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RiveStreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Provider utama: scraper TMDB + PrimeSrc engine
        registerMainAPI(RiveStreamProvider())

        // Extractor untuk embed URL yang datang dari loadExtractor()
        registerExtractorAPI(RiveVidara())       // vidara.so/e/...
        registerExtractorAPI(RiveVidsST())       // vids.st/e/...
        registerExtractorAPI(RiveSavefiles())    // savefiles.com/e/...
        registerExtractorAPI(RiveLizer())        // lizer123.site/getm3u8/... ← NEW
    }
}
