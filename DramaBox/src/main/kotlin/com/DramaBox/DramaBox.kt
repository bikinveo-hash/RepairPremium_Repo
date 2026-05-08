package com.DramaBox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DramaBox : MainAPI() {
    // URL utama dan API URL akan kita isi setelah ketemu
    override var mainUrl = "https://www.dramabox.com" 
    private val apiUrl = "https://api.dramabox.com" // Ini cuma tebakan awal
    
    override var name = "DramaBox"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    // Kategori beranda akan kita sesuaikan nanti
    override val mainPage = mainPageOf(
        "home" to "Beranda"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Logika sedot beranda akan kita taruh di sini
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        return null
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return false
    }
}
