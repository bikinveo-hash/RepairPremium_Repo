package com.OppaDrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OppaDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // LocalProxy memotong prefix PNG (806) + padding 0xFF (135) = offset 941
        // pada segmen TurboVIP, dan menulis ulang URI segmen di playlist supaya
        // ikut lewat proxy. Offset dicari dinamis per segmen, tidak di-hardcode.
        //
        // start() dipanggil di dalam runCatching SEBELUM registrasi provider,
        // supaya kalau bind gagal, provider tetap terdaftar dan tidak ada fungsi
        // yang hilang. Kalau proxy mati, proxyUrl() mengembalikan null dan
        // provider otomatis jatuh kembali ke URL langsung.
        //
        // Catatan lifecycle (terukur di log 30 Jul): setiap reload plugin membuat
        // objek LocalProxy baru sementara socket lama masih memegang port, jadi
        // port bergeser 47821 -> 47822 -> ... Fallback 12 port menutupinya dan
        // playback tidak terpengaruh. Ini SENGAJA tidak diperbaiki: perbaikannya
        // menyentuh jalur streaming yang saat ini nol error.
        val ok = runCatching { LocalProxy.start() }.getOrElse { e ->
            LocalProxy.obs("START-THROW", "${e.javaClass.simpleName}: ${e.message}")
            false
        }
        LocalProxy.obs("PLUGIN-LOAD", "started=$ok port=${LocalProxy.port}")

        registerMainAPI(OppaDramaProvider())

        // Provider diagnostik SUDAH DIHAPUS dari registrasi. M1 dan M2 selesai
        // diverifikasi. Berkas OppaDramaDiagProvider.kt boleh ikut dihapus dari
        // project, atau disimpan tanpa didaftarkan kalau sewaktu-waktu perlu.
    }
}
