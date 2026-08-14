package com.Cinemacity

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemacityPlugin : Plugin() {

    companion object {
        /**
         * Cookie hasil tantangan Cloudflare.
         *
         * [PROVEN] plugin asli menyimpannya di Companion; dibaca oleh
         * loadLinks (@02ce) dan getCfHeaders.
         *
         * DITUNDA di port v1: pengisian otomatis lewat CloudflareWebViewDialog.
         */
        @Volatile
        var cfCookies: String = ""

        /**
         * [PROVEN] getter/setter cfUserAgent ada di Companion asli dan dipakai
         * getCfHeaders serta interceptor.
         *
         * [TENTATIVE] asalnya (dugaan WebSettings.getUserAgentString()) BELUM
         * terbukti dari bytecode, jadi tidak diasumsikan. Bila kosong,
         * interceptor tidak menimpa User-Agent.
         */
        @Volatile
        var cfUserAgent: String = ""

        /** [PROVEN] disimpan bersama cookie oleh saveCookiesAndDismiss. */
        @Volatile
        var cfCookieHost: String = ""

        /**
         * [PROVEN] getCfHeaders menyusun LinkedHashMap berisi User-Agent dan
         * Cookie, masing-masing hanya bila panjangnya > 0.
         */
        fun getCfHeaders(): Map<String, String> {
            val h = LinkedHashMap<String, String>()
            if (cfUserAgent.isNotEmpty()) h["User-Agent"] = cfUserAgent
            if (cfCookies.isNotEmpty()) h["Cookie"] = cfCookies
            return h
        }
    }

    override fun load(context: Context) {
        registerMainAPI(Cinemacity())
    }
}
