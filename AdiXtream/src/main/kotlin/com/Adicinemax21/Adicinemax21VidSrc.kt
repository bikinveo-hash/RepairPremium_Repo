package com.Adicinemax21

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Document
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * VidSrc source transplanted from Streamzy.
 *
 * Scope is intentionally narrow:
 * - receives TMDB id/season/episode already produced by Adicinemax21;
 * - builds only vidsrc.mov embed URLs;
 * - delegates playback resolution to the same VidSrc resolver used by Streamzy;
 * - does not alter TMDB, MovieBox, Idlix, metadata, search, or other provider logic.
 */
internal object Adicinemax21VidSrc {
    private const val TAG = "Adicinemax21VS"
    private const val STREAMZY_BASE = "https://streamzy.org"
    private const val VIDSRC_BASE = "https://vidsrc.mov"

    @Volatile
    private var applicationContext: Context? = null

    fun attachContext(context: Context) {
        applicationContext = context.applicationContext
    }

    private fun logMarker(message: String) {
        Log.i(TAG, message)
    }

    private fun safeHost(url: String?): String {
        if (url.isNullOrBlank()) return "-"
        return try {
            URI(url).host?.lowercase() ?: "invalid"
        } catch (_: Exception) {
            "invalid"
        }
    }

    private fun resolveHttpUrl(
        baseUrl: String,
        value: String?
    ): String? {
        val clean = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val uriSafe = clean.replace(" ", "%20")

        return try {
            val resolved = when {
                uriSafe.startsWith("//") -> "https:$uriSafe"
                uriSafe.startsWith("?") -> {
                    val base = URI(baseUrl)
                    URI(
                        base.scheme,
                        base.authority,
                        base.path,
                        null,
                        null
                    ).toString() + uriSafe
                }
                else -> URI(baseUrl).resolve(uriSafe).toString()
            }

            val parsed = URI(resolved)
            resolved.takeIf {
                parsed.host != null &&
                    (parsed.scheme.equals("https", true) || parsed.scheme.equals("http", true))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getIframeUrls(
        document: Document,
        baseUrl: String
    ): List<String> {
        return document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                val rawUrl = iframe
                    .attr("src")
                    .trim()
                    .ifBlank { iframe.attr("data-src").trim() }

                resolveHttpUrl(baseUrl, rawUrl)
            }
            .distinct()
    }

    private fun sha256Prefix(value: String): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(12)
    }

    private fun buildTarget(
        tmdbId: Int,
        type: String?,
        season: Int?,
        episode: Int?
    ): Pair<String, String>? {
        val isTv = type.equals("tv", ignoreCase = true) || season != null

        return if (isTv) {
            val se = season ?: return null
            val ep = episode ?: return null
            val path = "/tv/$tmdbId/$se/$ep"
            "$VIDSRC_BASE/embed$path" to "$STREAMZY_BASE/watch$path"
        } else {
            val path = "/movie/$tmdbId"
            "$VIDSRC_BASE/embed$path" to "$STREAMZY_BASE/watch$path"
        }
    }

    suspend fun invokeVidSrc(
        tmdbId: Int,
        type: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val context = applicationContext
        if (context == null) {
            Log.e(TAG, "VIDSRC|STAGE=init|RESULT=skip|REASON=context-not-attached")
            return
        }

        val target = buildTarget(tmdbId, type, season, episode)
        if (target == null) {
            Log.e(TAG, "VIDSRC|STAGE=target|RESULT=skip|REASON=missing-season-or-episode")
            return
        }

        val (embedUrl, refererUrl) = target

        logMarker(
            "VIDSRC|STAGE=start|TMDB=$tmdbId|" +
                "TYPE=${type ?: if (season != null) "tv" else "movie"}|" +
                "SEASON=${season ?: 0}|EPISODE=${episode ?: 0}|" +
                "HOST=${safeHost(embedUrl)}"
        )

        val resolver = Adicinemax21VidSrcResolver(
            applicationContext = context,
            sourceName = "VidSrc",
            logMarkerCallback = ::logMarker,
            safeHostCallback = ::safeHost,
            resolveHttpUrlCallback = ::resolveHttpUrl,
            getIframeUrlsCallback = ::getIframeUrls,
            sha256PrefixCallback = ::sha256Prefix
        )

        val emittedUrls = mutableSetOf<String>()
        var callbackCount = 0
        val forwardingCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                callbackCount += 1
                callback(link)
            }
        }

        try {
            val resolved = resolver.resolveValidatedPage(
                pageUrl = embedUrl,
                referer = refererUrl,
                callback = forwardingCallback
            )

            if (resolved && callbackCount > 0) {
                logMarker("VIDSRC|STAGE=done|CALLBACKS=$callbackCount|RESULT=success")
                return
            }

            resolver.tryExactExtractor(
                url = embedUrl,
                referer = refererUrl,
                subtitleCallback = subtitleCallback,
                callback = forwardingCallback
            )

            logMarker(
                "VIDSRC|STAGE=done|CALLBACKS=$callbackCount|" +
                    "RESULT=${if (callbackCount > 0) "success" else "no-links"}"
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(
                TAG,
                "VIDSRC|STAGE=done|CALLBACKS=$callbackCount|RESULT=exception|" +
                    "ERROR=${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }
}
