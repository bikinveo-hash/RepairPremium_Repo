package com.Cinemacity

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Port v1 dari com.Cinemacity.CinemacityCFBypassInterceptor.
 *
 * [PROVEN] dari bytecode intercept():
 *   - hapus header  X-Requested-With
 *   - set  sec-ch-ua-mobile   : ?1
 *   - set  sec-ch-ua-platform : "Android"
 *   - set  User-Agent         <- cfUserAgent
 *   - set  Cookie             <- cfCookies
 *
 * DITUNDA di v1 (statusnya TENTATIVE, tidak dijadikan logika):
 *   - ekstraksi khusus nilai cf_clearance dari string cookie
 *   - CloudflareWebViewDialog yang mengisi cfCookies/cfUserAgent
 */
object CinemacityCFBypassInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // [PROVEN] header ini DIHAPUS. Keberadaannya menandai request sebagai
        // AJAX dan memicu Cloudflare.
        builder.removeHeader("X-Requested-With")

        // [PROVEN] dua client-hint selalu dipasang
        builder.header("sec-ch-ua-mobile", "?1")
        builder.header("sec-ch-ua-platform", "\"Android\"")

        // [PROVEN] hanya dipasang bila terisi (getCfHeaders memakai syarat
        // panjang > 0 yang sama)
        val ua = CinemacityPlugin.cfUserAgent
        if (ua.isNotEmpty()) builder.header("User-Agent", ua)

        val cookies = CinemacityPlugin.cfCookies
        if (cookies.isNotEmpty()) builder.header("Cookie", cookies)

        return chain.proceed(builder.build())
    }
}
