// =========================================================================
// EXTRACTOR 4: HYDRAX (ABYSS) — FIXED v5
// Fix build error: newExtractorLink (suspend) tidak boleh di dalam forEach lambda
// Solusi: pakai for loop biasa
// =========================================================================
open class HydraxExtractor : ExtractorApi() {
    override var name = "Hydrax"
    override var mainUrl = "https://abysscdn.com"
    override val requiresReferer = false

    private fun getMd5Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun qualityFromLabel(label: String): Int = when (label) {
        "360p"  -> Qualities.P360.value
        "480p"  -> Qualities.P480.value
        "720p"  -> Qualities.P720.value
        "1080p" -> Qualities.P1080.value
        else    -> Qualities.Unknown.value
    }

    private fun resIdToLabel(resId: String): String = when (resId) {
        "2" -> "360p"; "3" -> "480p"; "4" -> "720p"; "5" -> "1080p"
        else -> "HD"
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val sources = mutableListOf<ExtractorLink>()
        try {
            val videoId = when {
                url.contains("hydrax/") -> url.substringAfter("hydrax/").substringBefore("/")
                url.contains("?v=")     -> url.substringAfter("?v=").substringBefore("&")
                else                    -> url.substringAfterLast("/")
            }
            if (videoId.isBlank()) return null

            val html = app.get(
                "https://abysscdn.com/?v=$videoId",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
                ),
                referer = referer ?: "https://playeriframe.sbs/"
            ).text

            val base64Data = Regex("""datas\s*=\s*"([^"]+)"""")
                .find(html)?.groupValues?.get(1) ?: return null

            val jsonString = String(
                android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT),
                Charsets.ISO_8859_1
            )
            val jsonData = mapper.readValue(jsonString, Map::class.java)

            val slug     = jsonData["slug"]?.toString()    ?: return null
            val md5Id    = jsonData["md5_id"]?.toString()  ?: return null
            val userId   = jsonData["user_id"]?.toString() ?: return null
            val mediaStr = jsonData["media"] as? String    ?: return null

            // Key terbukti: MD5(userId:slug:md5Id).hexdigest().toByteArray() = 32 bytes
            val keyBytes = getMd5Hex("$userId:$slug:$md5Id").toByteArray(Charsets.UTF_8)
            val ivBytes  = keyBytes.copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )
            val mediaData = mapper.readValue(
                String(cipher.doFinal(mediaStr.toByteArray(Charsets.ISO_8859_1)), Charsets.UTF_8),
                Map::class.java
            )

            val hydraxHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Origin"     to "https://abysscdn.com",
                "Referer"    to "https://abysscdn.com/"
            )

            // ── Parse satu section (hls atau mp4) ──────────────────────
            suspend fun parseSection(section: Map<*, *>?, isHls: Boolean) {
                if (section == null) return

                val domains = (section["domains"] as? List<*>)
                    ?.mapNotNull { it?.toString() } ?: emptyList()

                @Suppress("UNCHECKED_CAST")
                val sourcesList = section["sources"] as? List<Map<String, *>> ?: emptyList()

                // -- sources utama --
                for (src in sourcesList) {
                    val status = src["status"] as? Boolean ?: true
                    if (!status) continue

                    val label  = src["label"]?.toString()  ?: "HD"
                    val resId  = src["res_id"]?.toString() ?: ""
                    val size   = src["size"]?.toString()   ?: ""
                    val srcUrl = src["url"]?.toString()    ?: ""
                    val path   = src["path"]?.toString()   ?: ""

                    // Prioritas URL sesuai struktur JSON nyata:
                    // 1. url + path  → "https://domain/path"
                    // 2. url saja    → "https://domain/slug/res_id/size?v=videoId"
                    // 3. domains[0]  → "https://domains[0]/slug/res_id/size?v=videoId"
                    val finalUrl = when {
                        srcUrl.isNotBlank() && path.isNotBlank() ->
                            "${srcUrl.trimEnd('/')}/$path"
                        srcUrl.isNotBlank() && resId.isNotBlank() && size.isNotBlank() ->
                            "${srcUrl.trimEnd('/')}/$slug/$resId/$size?v=$videoId"
                        domains.isNotEmpty() && resId.isNotBlank() && size.isNotBlank() ->
                            "https://${domains[0]}/$slug/$resId/$size?v=$videoId"
                        else -> continue
                    }

                    val linkType = when {
                        isHls || finalUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                        else -> ExtractorLinkType.VIDEO
                    }

                    sources.add(
                        newExtractorLink(
                            source = "Hydrax",
                            name   = "Hydrax $label",
                            url    = finalUrl,
                            type   = linkType
                        ) {
                            this.referer = "https://abysscdn.com/"
                            this.quality = qualityFromLabel(label)
                            this.headers = hydraxHeaders
                        }
                    )
                }

                // -- fristDatas: URL alternatif siap pakai, tambah jika label belum ada --
                @Suppress("UNCHECKED_CAST")
                val fristDatas = section["fristDatas"] as? List<Map<String, *>> ?: emptyList()

                for (src in fristDatas) {
                    val fUrl  = src["url"]?.toString()    ?: continue
                    val resId = src["res_id"]?.toString() ?: ""
                    val label = resIdToLabel(resId)

                    // Skip kalau resolusi ini sudah ada dari sources utama
                    if (sources.any { it.name == "Hydrax $label" }) continue

                    sources.add(
                        newExtractorLink(
                            source = "Hydrax",
                            name   = "Hydrax $label",
                            url    = fUrl,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://abysscdn.com/"
                            this.quality = qualityFromLabel(label)
                            this.headers = hydraxHeaders
                        }
                    )
                }
            }

            parseSection(mediaData["hls"] as? Map<*, *>, true)
            parseSection(mediaData["mp4"] as? Map<*, *>, false)

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return sources.ifEmpty { null }
    }
}
