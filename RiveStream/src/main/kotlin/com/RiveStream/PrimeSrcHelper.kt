package com.RiveStream

import com.fasterxml.jackson.annotation.JsonProperty

// Model pemetaan data JSON untuk penampung daftar server hulu dari PrimeSrc
data class PrimeSrcResponse(
    @JsonProperty("servers") val servers: List<PrimeSrcServer>? = null
)

data class PrimeSrcServer(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("key") val key: String? = null
)

// Model pemetaan data JSON hasil penukaran token link otorisasi sukses
data class PrimeSrcDirectLink(
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("host") val host: String? = null
)
