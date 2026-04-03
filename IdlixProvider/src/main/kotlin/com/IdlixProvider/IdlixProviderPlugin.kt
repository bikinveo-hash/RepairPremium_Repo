package com.michat88

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

// Anotasi ini memberi tahu sistem Cloudstream bahwa kelas ini adalah sebuah plugin
@CloudstreamPlugin
class IdlixProviderPlugin: BasePlugin() {
    
    // Fungsi load() adalah titik awal yang akan dieksekusi saat plugin dimuat oleh aplikasi
    override fun load() {
        // Mendaftarkan provider utama yang bertugas melakukan scraping data dari website (Idlix)
        registerMainAPI(IdlixProvider())
        
        // Mendaftarkan extractor yang bertugas mengambil link video langsung dari host (Jeniusplay)
        registerExtractorAPI(Jeniusplay())
    }
}
