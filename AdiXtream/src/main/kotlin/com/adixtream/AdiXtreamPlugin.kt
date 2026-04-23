package com.adixtream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AdiXtreamPlugin : Plugin() {
    
    /**
     * Fungsi 'load' ini adalah hal pertama yang dijalankan oleh Cloudstream 
     * saat ekstensi/plugin-mu berhasil dipasang.
     */
    override fun load(context: Context) {
        // Mendaftarkan provider VidSrcProvider yang sudah kita buat sebelumnya
        registerMainAPI(VidSrcProvider())
        
        // Catatan: Jika suatu saat nanti kamu membuat file provider lain 
        // (misalnya NontonGratisProvider.kt), kamu tinggal menambahkannya di bawah ini:
        // registerMainAPI(NontonGratisProvider())
    }
}
