package com.Cinemacity

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemacityPlugin : Plugin() {

    companion object {
        /**
         * State Cloudflare. Di plugin asli ketiganya adalah properti
         * `CinemacityPlugin$Companion` yang ditulis oleh
         * `saveCookiesAndDismiss` (dialog WebView) dan dibaca oleh
         * interceptor serta `loadLinks`.
         *
         * Status bukti:
         *   cfCookies      [PROVEN]  ditulis saveCookiesAndDismiss, dibaca loadLinks @02ce
         *   cfCookieHost   [PROVEN]  ada; pembacanya belum ditemukan
         *   cfUserAgent    [TENTATIVE] getter/setter terbukti; ASAL NILAI belum
         *                  di-slice, jadi v1 TIDAK mengisinya otomatis.
         *
         * v1 hanya menyediakan penampungnya. Pengisian lewat WebView
         * challenge DITUNDA (lihat catatan di README).
         */
        @Volatile var cfCookies: String? = null
        @Volatile var cfCookieHost: String? = null
        @Volatile var cfUserAgent: String? = null
    }

    override fun load(context: Context) {
        registerMainAPI(CinemacityProvider())
    }
}
