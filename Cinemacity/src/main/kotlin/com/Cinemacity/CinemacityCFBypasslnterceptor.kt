package com.Cinemacity

import okhttp3.Interceptor
import okhttp3.Response

object CinemacityCFBypassInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        builder.removeHeader("X-Requested-With")
        builder.header("sec-ch-ua-mobile", "?1")
        builder.header("sec-ch-ua-platform", "\"Android\"")

        CinemacityPlugin.cfUserAgent
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("User-Agent", it) }

        CinemacityPlugin.cfCookies
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Cookie", it) }

        return chain.proceed(builder.build())
    }
}
