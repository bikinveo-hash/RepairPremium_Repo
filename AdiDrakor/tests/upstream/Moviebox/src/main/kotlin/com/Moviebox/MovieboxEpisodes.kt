package com.Moviebox

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.Score
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Episode display metadata only. Never builds or changes playback data or episode indices. */
internal object MovieboxEpisodes {
    data class Metadata(val season: Int, val episode: Int, val name: String?, val poster: String?,
        val description: String?, val rating: Double?, val date: Long?)

    private val audioSuffix = Regex(
        "(?i)\\s*[\\[(](?:(?:original|asli|indonesian|indonesia|english|inggris)(?:\\s+(?:audio|dub|dubbed|sub|subbed|subtitle))?)[\\])]\\s*$")
    private val seasonSuffix = Regex("(?i)\\s+S\\d+(?:\\s*[-–]\\s*S?\\d+)?\\s*$")

    fun lookupTitle(title: String, year: Int?): String {
        var clean=title.trim()
        repeat(2) {
            clean=clean.replace(audioSuffix,"").replace(seasonSuffix,"").trim()
            if(year!=null) clean=clean.removeSuffix("($year)").trim()
        }
        return clean
    }
    private fun normalized(title: String): String = Normalizer.normalize(title,Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    private fun text(o: JSONObject, key: String): String? =
        if(o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() }

    fun seriesId(raw: String, title: String, year: Int): Int? = try {
        val wanted=normalized(title)
        val results=JSONObject(raw).optJSONArray("results")
        val matches=(0 until (results?.length() ?: 0)).mapNotNull { results?.optJSONObject(it) }
            .filter { item ->
                val actualYear=text(item,"first_air_date")?.take(4)?.toIntOrNull()
                actualYear==year && wanted.isNotEmpty() &&
                    listOfNotNull(text(item,"name"),text(item,"original_name")).any { normalized(it)==wanted }
            }.map { it.optInt("id",0) }.filter { it>0 }.distinct()
        matches.singleOrNull()
    } catch (_: Exception) { null }

    private fun date(raw: String?): Long? = try {
        if(raw==null || !Regex("\\d{4}-\\d{2}-\\d{2}").matches(raw)) null
        else SimpleDateFormat("yyyy-MM-dd",Locale.US).apply {
            isLenient=false;timeZone=TimeZone.getTimeZone("UTC")
        }.parse(raw)?.time
    } catch (_: Exception) { null }

    fun season(raw: String, seriesId: Int, season: Int): Map<Int,Metadata> = try {
        val root=JSONObject(raw)
        if(root.optInt("season_number",-1)!=season) emptyMap()
        else {
            val array=root.optJSONArray("episodes")
            (0 until (array?.length() ?: 0)).mapNotNull { i ->
                val item=array?.optJSONObject(i) ?: return@mapNotNull null
                val ep=item.optInt("episode_number",-1)
                if(ep<1 || item.optInt("season_number",-1)!=season ||
                    (item.has("show_id") && item.optInt("show_id",-1)!=seriesId)) return@mapNotNull null
                val still=text(item,"still_path")?.takeIf {
                    Regex("/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)",RegexOption.IGNORE_CASE).matches(it)
                }?.let { "https://image.tmdb.org/t/p/w300$it" }
                Metadata(season,ep,text(item,"name"),still,text(item,"overview"),
                    item.optDouble("vote_average",Double.NaN).takeIf { it>0 && it<=10 },date(text(item,"air_date")))
            }.groupBy { it.episode }.filterValues { it.size==1 }.mapValues { it.value.single() }
        }
    } catch (_: Exception) { emptyMap() }

    suspend fun decorate(title: String, year: Int?, poster: String?, episodes: List<Episode>,
        fetch: suspend (String) -> String?) {
        // A series cover still gives the app a card when no trustworthy episode still exists.
        episodes.forEach { if(it.posterUrl.isNullOrBlank()) it.posterUrl=poster?.takeIf(String::isNotBlank) }
        if(year==null || episodes.isEmpty()) return
        val query=lookupTitle(title,year)
        if(query.isBlank()) return
        suspend fun read(path: String): String? = try { fetch(path) }
            catch(e: CancellationException) { throw e } catch (_: Exception) { null }
        // Optional display data must not hold up the detail page indefinitely.
        withTimeoutOrNull(6000) {
            val search=read("/search/tv?query=${URLEncoder.encode(query,"UTF-8")}&language=en-US")
                ?: return@withTimeoutOrNull
            val id=seriesId(search,query,year) ?: return@withTimeoutOrNull
            val semaphore=Semaphore(3)
            coroutineScope {
                episodes.mapNotNull { it.season }.filter { it>=0 }.distinct().map { se -> async {
                    semaphore.withPermit {
                        val raw=read("/tv/$id/season/$se?language=en-US") ?: return@withPermit
                        val metadata=season(raw,id,se)
                        episodes.filter { it.season==se }.forEach { ep ->
                            val item=metadata[ep.episode ?: return@forEach] ?: return@forEach
                            item.name?.let { ep.name=it }
                            item.poster?.let { ep.posterUrl=it }
                            item.description?.let { ep.description=it }
                            item.rating?.let { ep.score=Score.from(it,10) }
                            // Do not turn an existing Moviebox episode into an upcoming/locked
                            // entry just because optional external metadata has a future date.
                            item.date?.takeIf { it<=System.currentTimeMillis() }?.let { ep.date=it }
                        }
                    }
                } }.awaitAll()
            }
        }
    }
}
