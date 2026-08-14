package com.Cinemacity

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Port v1 dari CinemacityCFBypassInterceptor.
 *
 * Semua perilaku di bawah [PROVEN] dari bytecode `intercept()`:
 *   - hapus header `X-Requested-With`
 *   - set `sec-ch-ua-mobile: ?1`
 *   - set `sec-ch-ua-platform: "Android"`
 *   - set `User-Agent`  <- cfUserAgent
 *   - set `Cookie`      <- cfCookies
 *
 * TIDAK diimplementasikan di v1 (di luar scope, statusnya TENTATIVE):
 *   - ekstraksi khusus nilai `cf_clearance` dari string cookie
 *   - dialog WebView penantang Cloudflare
 */
object CinemacityCFBypassInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // [PROVEN] header ini DIHAPUS; keberadaannya memicu Cloudflare
        builder.removeHeader("X-Requested-With")

        // [PROVEN] dua header client-hint selalu dipasang
        builder.header("sec-ch-ua-mobile", "?1")
        builder.header("sec-ch-ua-platform", "\"Android\"")

        // [PROVEN] UA dan Cookie diambil dari state plugin
        CinemacityPlugin.cfUserAgent
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("User-Agent", it) }

        CinemacityPlugin.cfCookies
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Cookie", it) }

        return chain.proceed(builder.build())
    }
}
