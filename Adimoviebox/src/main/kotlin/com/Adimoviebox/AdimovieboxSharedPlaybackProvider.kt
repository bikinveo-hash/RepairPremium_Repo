package com.Adimoviebox

import com.Adicinemax21.MovieBoxV2Shared
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor

/**
 * Standalone Adimoviebox provider with the exact same MovieBox playback engine
 * used by Adicinemax21 / AdiDrakor / AdiFilmSemi / AdiXtream.
 *
 * All catalog/search/detail behavior stays delegated to the original provider.
 */
class AdimovieboxSharedPlaybackProvider : MainAPI() {
    private val delegate = Adimoviebox()

    override var mainUrl = delegate.mainUrl
    override var name = delegate.name
    override var lang = delegate.lang
    override val supportedTypes = delegate.supportedTypes
    override var hasMainPage = delegate.hasMainPage
    override val hasQuickSearch = delegate.hasQuickSearch
    override val instantLinkLoading = false
    override val mainPage = delegate.mainPage

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = delegate.getMainPage(page, request)

    override suspend fun search(query: String): List<SearchResponse> =
        delegate.search(query)

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        delegate.quickSearch(query)

    override suspend fun load(url: String): LoadResponse? =
        delegate.load(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val media = try {
            parseJson<LoadData>(data)
        } catch (_: Exception) {
            return false
        }

        val detailPath = media.detailPath?.takeIf { it.isNotBlank() }
            ?: media.id?.takeIf { it.isNotBlank() }
            ?: return false

        // Reuse the existing detail implementation only to obtain the same title/year
        // that the proven shared MovieBox matcher expects. No catalog behavior changes.
        val detail = try {
            delegate.load("${delegate.mainUrl}/detail/$detailPath")
        } catch (_: Exception) {
            return false
        }

        val title = detail.name.takeIf { it.isNotBlank() } ?: return false
        val originals = mutableListOf<ExtractorLink>()

        MovieBoxV2Shared.invokeMoviebox(
            sourceTag = "Adimoviebox",
            title = title,
            year = detail.year,
            season = media.season,
            episode = media.episode,
            subtitleCallback = subtitleCallback,
            callback = { originals.add(it) }
        )

        for (link in originals) {
            callback(
                newExtractorLink(
                    source = name,
                    name = link.name.replaceFirst("MovieBox", name),
                    url = link.url,
                    type = link.type
                ) {
                    referer = link.referer
                    quality = link.quality
                    headers = link.headers
                }
            )
        }

        return originals.isNotEmpty()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val cookie = extractorLink.headers["Cookie"]
        if (cookie.isNullOrBlank()) return super.getVideoInterceptor(extractorLink)
        val userAgent = extractorLink.headers["User-Agent"]
        return Interceptor { chain ->
            val builder = chain.request().newBuilder().header("Cookie", cookie)
            if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
            chain.proceed(builder.build())
        }
    }
}
