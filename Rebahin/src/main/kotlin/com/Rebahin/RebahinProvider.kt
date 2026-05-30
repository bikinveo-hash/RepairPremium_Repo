package com.Rebahin

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLDecoder

// ═══════════════════════════════════════════════════════════════
//  TAG untuk filter Logcat:
//  adb logcat -s "REBAHIN_DEBUG" -v time
//  atau di Android Studio: filter tag = REBAHIN_DEBUG
// ═══════════════════════════════════════════════════════════════
private const val TAG = "REBAHIN_DEBUG"

// Helper extension — log sekaligus ke Logcat
private fun logD(section: String, msg: String) =
    Log.d(TAG, "[$section] $msg")

private fun logE(section: String, msg: String) =
    Log.e(TAG, "[$section] ❌ $msg")

private fun logW(section: String, msg: String) =
    Log.w(TAG, "[$section] ⚠ $msg")

// ═══════════════════════════════════════════════════════════════

class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.autos"
    override var name    = "Rebahin"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 800L
    override var sequentialMainPageScrollDelay = 200L

    companion object {
        // Gunakan UA yang identik dengan WebView Android agar tidak ditolak
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    // ─────────────────────────────────────────────────────────────
    //  MAIN PAGE
    // ─────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/"                  to "Movies",
        "$mainUrl/country/indonesia/page/"       to "Indonesia",
        "$mainUrl/genre/series-indonesia/page/"  to "Series Indonesia",
        "$mainUrl/country/south-korea/page/"     to "South Korea",
        "$mainUrl/genre/drama-korea/page/"       to "Drama Korea",
        "$mainUrl/genre/action/page/"            to "Action",
        "$mainUrl/genre/adventure/page/"         to "Adventure",
        "$mainUrl/genre/war/page/"               to "War",
        "$mainUrl/genre/adult/page/"             to "Adult",
        "$mainUrl/country/philippines/page/"     to "Philippines"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data.removeSuffix("page/")
                  else           request.data + page

        val document = app.get(url, referer = mainUrl).document
        val home = document.select("div.ml-item").mapNotNull { it.toSearchResult() }

        if (home.isEmpty() && page == 1)
            throw ErrorLoadingException("Server membatasi request, silakan refresh kategori ini.")

        return newHomePageResponse(request, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a     = this.selectFirst("a.ml-mask") ?: return null
        val title = a.attr("title")
        val href  = fixUrlNull(a.attr("href"))    ?: return null

        val img        = this.selectFirst("img.mli-thumb")
        val posterUrl  = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        )

        val qualityStr    = this.selectFirst("span.mli-quality")?.text()?.lowercase()
        val qualityResult = when {
            qualityStr == null                                    -> null
            qualityStr.contains("fhd") || qualityStr.contains("hd") -> SearchQuality.HD
            qualityStr.contains("blu")                           -> SearchQuality.BlueRay
            qualityStr.contains("cam")                           -> SearchQuality.Cam
            qualityStr.contains("sd")                            -> SearchQuality.SD
            else                                                 -> null
        }

        val isTvSeries = href.contains("/series/") || href.contains("season")
        return if (isTvSeries)
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl; this.quality = qualityResult }
        else
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl; this.quality = qualityResult }
    }

    // ─────────────────────────────────────────────────────────────
    //  LOAD
    // ─────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h3[itemprop=name]")?.text()
            ?: return null

        val mviCover   = document.selectFirst("a.mvi-cover")
        val background = fixUrlNull(
            mviCover?.attr("style")
                ?.substringAfter("url(")?.substringBefore(")")
                ?.removeSurrounding("'")?.removeSurrounding("\"")
        )
        val playUrl = fixUrlNull(mviCover?.attr("href"))
            ?: if (url.contains("/series/")) "$url/watch" else "$url/play"

        val poster = fixUrlNull(document.selectFirst("meta[itemprop=image]")?.attr("content"))
            ?: fixUrlNull(
                document.selectFirst("div.mvic-thumb")?.attr("style")
                    ?.substringAfter("url(")?.substringBefore(")")
                    ?.removeSurrounding("'")?.removeSurrounding("\"")
            )

        val plot = document.selectFirst("div.sinopsis-indo, div.desc, div.rt-Text")
            ?.text()
            ?.replace("Nonton Film.*Sub Indo \\| REBAHIN".toRegex(RegexOption.IGNORE_CASE), "")
            ?.trim()

        val ratingText  = document.selectFirst(
            "span.irank-voters, span.rating, div.btn-danger.averagerate"
        )?.text()?.replace(",", ".")?.trim()
        val parsedScore = ratingText?.toFloatOrNull()?.let { Score.from10(it) }

        var year: Int? = null; var duration: Int? = null
        document.select("div.mvic-info p").forEach { p ->
            val text = p.text()
            when {
                text.contains("Release Date:") ->
                    year = p.selectFirst("meta[itemprop=datePublished]")
                        ?.attr("content")?.substringBefore("-")?.toIntOrNull()
                        ?: text.substringAfter("Release Date:").trim()
                            .substringBefore("-").toIntOrNull()
                text.contains("Duration:") ->
                    duration = text.replace(Regex("[^0-9]"), "").toIntOrNull()
            }
        }

        val isTvSeries = url.contains("/series/")
        return if (isTvSeries) {
            val watchDocument  = app.get(playUrl).document
            val episodes       = mutableListOf<Episode>()
            watchDocument.select("div#list-eps a.btn-eps").forEach { epElem ->
                val epName       = epElem.text().trim()
                val base64Iframe = epElem.attr("data-iframe")
                val epNum        = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                if (base64Iframe.isNotBlank())
                    episodes.add(newEpisode(base64Iframe) {
                        this.name = epName; this.episode = epNum })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.backgroundPosterUrl = background
                this.plot = plot; this.score = parsedScore
                this.year = year; this.duration = duration
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster; this.backgroundPosterUrl = background
                this.plot = plot; this.score = parsedScore
                this.year = year; this.duration = duration
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  LOAD LINKS  ← PATCH UTAMA ADA DI SINI
    // ─────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // ── Kumpulkan semua URL iframe yang akan diproses ──
        val urlToExtract = mutableListOf<String>()

        if (!data.startsWith("http")) {
            // data = base64 encoded iframe URL (dari episode TV series)
            try {
                val decodedUrl = String(Base64.decode(data, Base64.DEFAULT))
                logD("LOAD_LINKS", "Base64 decoded → $decodedUrl")
                if (decodedUrl.startsWith("http")) urlToExtract.add(decodedUrl)
            } catch (e: Exception) {
                logE("LOAD_LINKS", "Base64 decode gagal: ${e.message}")
            }
        } else {
            val document = app.get(data, referer = mainUrl).document

            // Cari data-iframe (base64 encoded)
            document.select("[data-iframe]").forEach { element ->
                val encodedUrl = element.attr("data-iframe")
                if (encodedUrl.isNotBlank()) {
                    try {
                        val decodedUrl = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                        logD("LOAD_LINKS", "data-iframe decoded → $decodedUrl")
                        if (decodedUrl.startsWith("http")) urlToExtract.add(decodedUrl)
                    } catch (e: Exception) {
                        logE("LOAD_LINKS", "data-iframe base64 gagal: ${e.message}")
                    }
                }
            }

            // Cari iframe src langsung
            document.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src"))
                    ?: fixUrlNull(iframe.attr("data-src"))
                if (src != null && !src.contains("googleusercontent.com") && src.isNotBlank()) {
                    logD("LOAD_LINKS", "iframe src ditemukan → $src")
                    urlToExtract.add(src)
                }
            }
        }

        logD("LOAD_LINKS", "Total URL untuk diproses: ${urlToExtract.distinct().size}")

        // ── Proses setiap URL ──
        urlToExtract.distinct().forEach { rawUrl ->
            var targetUrl = rawUrl
            logD("PROCESS", "━━━ Memproses: $rawUrl")

            // Normalisasi abyssplayer → abysscdn
            if (rawUrl.contains("/iembed/?source=")) {
                val base64 = rawUrl.substringAfter("source=")
                try {
                    targetUrl = String(Base64.decode(base64, Base64.DEFAULT))
                    logD("PROCESS", "iembed decoded → $targetUrl")
                } catch (e: Exception) {
                    logE("PROCESS", "iembed decode gagal: ${e.message}")
                }
            }
            if (targetUrl.contains("abyssplayer.com/")) {
                targetUrl = targetUrl.replace("abyssplayer.com/", "abysscdn.com/?v=")
                logD("PROCESS", "Abyss URL normalized → $targetUrl")
            }

            // Coba extractor bawaan Cloudstream dulu
            val isExtractorLoaded = loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)
            logD("PROCESS", "loadExtractor result = $isExtractorLoaded untuk $targetUrl")

            if (!isExtractorLoaded) {
                val domain       = Regex("""https?://[^/]+""").find(targetUrl)?.value ?: targetUrl
                val fixedReferer = if (targetUrl.contains("abyss")) "https://abysscdn.com/"
                                   else "$domain/"
                logD("PROCESS", "Referer yang akan dipakai: $fixedReferer")

                var linkFound = false

                // ═══════════════════════════════════════════════════
                //  WEBVIEW RESOLVER + COMPREHENSIVE DEBUG LOGGING
                // ═══════════════════════════════════════════════════
                val debugScript = """
                (function() {
                    if (window.__cs_debug_active) return;
                    window.__cs_debug_active = true;

                    // ── 1. Catat info halaman ──
                    console.log('[CS_DEBUG][PAGE] href=' + location.href);
                    console.log('[CS_DEBUG][PAGE] referrer=' + document.referrer);
                    console.log('[CS_DEBUG][PAGE] cookie=' + document.cookie);

                    // ── 2. Bypass FuckAdBlock (SEBELUM player init) ──
                    window.fuckAdBlock = {
                        onDetected:    function(){},
                        onNotDetected: function(){},
                        setOption:     function(){}
                    };
                    window.FuckAdBlock = window.fuckAdBlock;
                    console.log('[CS_DEBUG][BYPASS] FuckAdBlock dummy dipasang');

                    // ── 3. Monitor XHR — catat semua request ──
                    var _XHR = window.XMLHttpRequest;
                    window.XMLHttpRequest = function() {
                        var xhr = new _XHR();
                        var _open = xhr.open.bind(xhr);
                        xhr.open = function(method, url) {
                            console.log('[CS_DEBUG][XHR] ' + method + ' ' + url);
                            return _open.apply(xhr, arguments);
                        };
                        return xhr;
                    };
                    console.log('[CS_DEBUG][HOOK] XHR monitor aktif');

                    // ── 4. Monitor fetch — TANPA mengganti implementasi native ──
                    // Kita wrap tapi tetap panggil original agar anti-hijack tidak trigger
                    var _fetch = window.fetch;
                    window.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input : (input && input.url) || '';
                        console.log('[CS_DEBUG][FETCH] ' + (init && init.method || 'GET') + ' ' + url);
                        return _fetch.apply(this, arguments);
                    };
                    // Sembunyikan modifikasi dari anti-hijack check
                    // (server cek fetch.toString() tapi setelah page load, bukan live)
                    try {
                        Object.defineProperty(window.fetch, 'toString', {
                            value: function() { return _fetch.toString(); }
                        });
                    } catch(e) {}
                    console.log('[CS_DEBUG][HOOK] fetch monitor aktif');

                    // ── 5. Intercept Service Worker ── 
                    if ('serviceWorker' in navigator) {
                        navigator.serviceWorker.addEventListener('message', function(e) {
                            console.log('[CS_DEBUG][SW_MSG] ' + JSON.stringify(e.data));
                        });
                        navigator.serviceWorker.getRegistrations().then(function(regs) {
                            regs.forEach(function(r) {
                                console.log('[CS_DEBUG][SW_REG] scope=' + r.scope + ' state=' + (r.active && r.active.state));
                            });
                        });
                    }

                    // ── 6. Polling extraksi URL video ──
                    var _sent = false;
                    var _attempts = 0;
                    var _interval = setInterval(function() {
                        _attempts++;
                        if (_attempts > 120) {   // timeout 60 detik (x500ms)
                            clearInterval(_interval);
                            console.log('[CS_DEBUG][TIMEOUT] Player tidak merespons setelah 60 detik');
                            return;
                        }

                        // ── Auto-click semua tombol play yang mungkin ada ──
                        try {
                            var playTargets = [
                                document.getElementById('overlay'),
                                document.querySelector('.jw-icon-display'),
                                document.querySelector('.vjs-big-play-button'),
                                document.querySelector('[class*="play"]'),
                                document.querySelector('button.play'),
                            ];
                            playTargets.forEach(function(el) {
                                if (el) el.click();
                            });
                        } catch(e) {}

                        // ── Cek JWPlayer ──
                        try {
                            if (window.jwplayer && typeof window.jwplayer === 'function') {
                                var jw = window.jwplayer();

                                // Log state player
                                var state = jw.getState && jw.getState();
                                if (state) console.log('[CS_DEBUG][JW_STATE] ' + state);

                                // Cek playlist
                                var pl = jw.getPlaylist && jw.getPlaylist();
                                if (pl && pl.length > 0) {
                                    console.log('[CS_DEBUG][JW_PLAYLIST_RAW] ' + JSON.stringify(pl[0]));
                                    var file = pl[0].file || (pl[0].sources && pl[0].sources[0] && pl[0].sources[0].file);
                                    if (file && file.indexOf('blob:') === -1 && !_sent) {
                                        _sent = true;
                                        clearInterval(_interval);
                                        console.log('[CS_DEBUG][JW_FILE] FOUND=' + file);
                                        window.location.href = 'https://cloudstream.video.extracted/?url=' + encodeURIComponent(file);
                                        return;
                                    }
                                }

                                // Cek config sebagai fallback
                                var cfg = jw.getConfig && jw.getConfig();
                                if (cfg) {
                                    console.log('[CS_DEBUG][JW_CONFIG_RAW] ' + JSON.stringify(cfg).substring(0, 500));
                                    var src = cfg.file || (cfg.sources && cfg.sources[0] && cfg.sources[0].file);
                                    if (src && src.indexOf('blob:') === -1 && !_sent) {
                                        _sent = true;
                                        clearInterval(_interval);
                                        console.log('[CS_DEBUG][JW_CONFIG_FILE] FOUND=' + src);
                                        window.location.href = 'https://cloudstream.video.extracted/?url=' + encodeURIComponent(src);
                                        return;
                                    }
                                }
                            }
                        } catch(e) {
                            console.log('[CS_DEBUG][JW_ERR] ' + e.message);
                        }

                        // ── Cek DPlayer ──
                        try {
                            var dp = window.dp || window.DPlayer;
                            if (dp && dp.options && dp.options.video && dp.options.video.url) {
                                var dUrl = dp.options.video.url;
                                if (dUrl.indexOf('blob:') === -1 && !_sent) {
                                    _sent = true;
                                    clearInterval(_interval);
                                    console.log('[CS_DEBUG][DPLAYER_FILE] FOUND=' + dUrl);
                                    window.location.href = 'https://cloudstream.video.extracted/?url=' + encodeURIComponent(dUrl);
                                    return;
                                }
                            }
                        } catch(e) {}

                        // ── Cek <video> element ──
                        try {
                            var vids = document.querySelectorAll('video');
                            vids.forEach(function(v) {
                                if (v.src && v.src.indexOf('blob:') === -1 && v.src.startsWith('http') && !_sent) {
                                    _sent = true;
                                    clearInterval(_interval);
                                    console.log('[CS_DEBUG][VIDEO_ELEM] FOUND=' + v.src);
                                    window.location.href = 'https://cloudstream.video.extracted/?url=' + encodeURIComponent(v.src);
                                    return;
                                }
                            });
                        } catch(e) {}

                    }, 500);

                    console.log('[CS_DEBUG][INIT] Script siap, polling dimulai');
                })();
                """.trimIndent()

                try {
                    logD("WEBVIEW", "Memulai WebViewResolver untuk: $targetUrl")
                    logD("WEBVIEW", "Referer: $fixedReferer")

                    val webViewResolver = WebViewResolver(
                        interceptUrl = Regex(
                            // Intercept: redirect custom kita, m3u8, mp4, atau master dari googleapis
                            """(cloudstream\.video\.extracted/\?url=.*""" +
                            """|\.(m3u8|mp4)([?#]|${'$'})""" +
                            """|storage\.googleapis\.com/[^#\s]+)"""
                        ),
                        script = debugScript
                    )

                    val (request, _) = webViewResolver.resolveUsingWebView(
                        url     = targetUrl,
                        referer = mainUrl
                    )

                    // ── Log semua yang ada di WebView request ──
                    logD("WEBVIEW_RESULT", "Request object: $request")
                    logD("WEBVIEW_RESULT", "URL: ${request?.url}")
                    logD("WEBVIEW_RESULT", "Headers: ${request?.headers}")

                    request?.url?.toString()?.let { resolvedUrl ->
                        logD("WEBVIEW_RESULT", "Raw resolved URL: $resolvedUrl")

                        // Decode URL dari redirect custom kita
                        var finalUrl = if (resolvedUrl.contains("cloudstream.video.extracted")) {
                            val encodedPart = Regex("""\?url=(.*)""")
                                .find(resolvedUrl)?.groupValues?.get(1)
                            if (encodedPart != null) {
                                URLDecoder.decode(encodedPart, "UTF-8").also {
                                    logD("WEBVIEW_RESULT", "Decoded dari redirect: $it")
                                }
                            } else resolvedUrl
                        } else resolvedUrl

                        // Strip Service Worker fragment (#...)
                        val beforeStrip = finalUrl
                        finalUrl = finalUrl.substringBefore("#")
                        if (beforeStrip != finalUrl)
                            logD("WEBVIEW_RESULT", "SW fragment stripped: $beforeStrip → $finalUrl")

                        logD("WEBVIEW_RESULT", "Final URL untuk ExoPlayer: $finalUrl")

                        // ── Ambil cookies dari WebView ──
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.flush()

                        // Log SEMUA domain yang mungkin punya cookie
                        val domains = listOf(
                            "https://abysscdn.com",
                            "https://iamcdn.net",
                            "https://abyssplayer.com",
                            "https://199.87.210.226",
                            "https://rebahinxxi3.autos",
                            finalUrl   // domain dari URL video itu sendiri
                        )
                        val cookieMap = mutableMapOf<String, String>()
                        domains.forEach { domain ->
                            val cookie = cookieManager.getCookie(domain)
                            logD("COOKIES", "Domain: $domain → ${cookie ?: "(null)"}")
                            if (!cookie.isNullOrBlank()) cookieMap[domain] = cookie
                        }

                        // Gabungkan semua cookie (hapus duplikat key)
                        val mergedCookies = cookieMap.values
                            .flatMap { it.split(";") }
                            .map    { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinctBy { it.substringBefore("=").trim() }
                            .joinToString("; ")

                        logD("COOKIES", "Merged cookie string: $mergedCookies")

                        // ── Bangun reqHeaders ──
                        val reqHeaders = mapOf(
                            "User-Agent"      to USER_AGENT,
                            "Accept"          to "*/*",
                            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
                            "Origin"          to fixedReferer.removeSuffix("/"),
                            "Referer"         to fixedReferer,
                            "Range"           to "bytes=0-",
                            "Sec-Fetch-Site"  to "cross-site",
                            "Sec-Fetch-Mode"  to "cors",
                            "Sec-Fetch-Dest"  to "empty",
                            "Cookie"          to mergedCookies
                        )

                        // ── LOG FINAL — ini yang paling penting di Logcat ──
                        logD("FINAL_HEADERS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        logD("FINAL_HEADERS", "URL       : $finalUrl")
                        reqHeaders.forEach { (k, v) ->
                            logD("FINAL_HEADERS", "  $k: $v")
                        }
                        logD("FINAL_HEADERS", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                        val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)

                        callback.invoke(
                            ExtractorLink(
                                source  = this.name + " (Web)",
                                name    = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                                url     = finalUrl,
                                referer = fixedReferer,
                                quality = Qualities.Unknown.value,
                                type    = if (isM3u8) ExtractorLinkType.M3U8
                                          else        ExtractorLinkType.VIDEO,
                                headers = reqHeaders
                            )
                        )
                        linkFound = true
                        logD("PROCESS", "✓ Link berhasil dikirim ke callback")
                    } ?: run {
                        logE("WEBVIEW_RESULT", "request atau URL null — WebView tidak menangkap apapun")
                    }

                } catch (e: Exception) {
                    logE("WEBVIEW", "Exception: ${e::class.simpleName} — ${e.message}")
                    e.printStackTrace()
                }

                // ── Fallback: regex langsung di HTML (tanpa WebView) ──
                if (!linkFound) {
                    logW("FALLBACK", "WebView gagal, mencoba regex fallback...")
                    try {
                        val playerHtml   = app.get(targetUrl, referer = mainUrl).text
                        val unpackedHtml = getAndUnpack(playerHtml)
                        logD("FALLBACK", "HTML length: ${unpackedHtml.length}")

                        val videoLinks = Regex(
                            """(?:file|source|src)\s*[:=]\s*["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']"""
                        ).findAll(unpackedHtml)

                        videoLinks.forEach { match ->
                            val link   = match.groupValues[1]
                            val isM3u8 = link.contains(".m3u8")
                            logD("FALLBACK", "Ditemukan via regex: $link")
                            callback.invoke(
                                ExtractorLink(
                                    source  = this.name + " (Regex)",
                                    name    = this.name + if (isM3u8) " (HLS)" else " (MP4)",
                                    url     = link,
                                    referer = fixedReferer,
                                    quality = Qualities.Unknown.value,
                                    type    = if (isM3u8) ExtractorLinkType.M3U8
                                              else        ExtractorLinkType.VIDEO,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer"    to fixedReferer
                                    )
                                )
                            )
                        }
                    } catch (e: Exception) {
                        logE("FALLBACK", "Regex fallback error: ${e.message}")
                    }
                }
            }
        }

        return true
    }
}
