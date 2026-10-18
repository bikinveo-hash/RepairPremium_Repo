package com.Moviebox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/** H5 navigation context only. Never resolves media or substitutes another subject. */
internal class MovieboxPlaybackContext(private val clock: () -> Long = { System.nanoTime()/1_000_000 },
    private val budgetMs: Long = 6000) {
    private val paths=linkedMapOf<String,Pair<Long,String>>()
    private val allowedHosts=MovieboxPlayback.webDomains.mapNotNull { URI(it).host }.toSet()+"moviebox.ph"

    fun path(value: String?, subject: String): String? {
        if(value.isNullOrBlank()) return null
        val clean=value.trim()
        if(clean.matches(Regex("[A-Za-z0-9_-]{1,220}")))
            return clean.takeUnless { it in setOf("detail","movies","moviedetail","download-app") }
        return try {
            val uri=URI(clean)
            if(uri.rawAuthority!=null && !uri.isAbsolute) return null
            if(uri.isAbsolute && (uri.scheme!="https" || uri.host !in allowedHosts || uri.userInfo!=null || uri.port !in listOf(-1,443))) return null
            if(uri.rawFragment!=null) return null
            val declared=uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=')=="id" }
                .map { it.substringAfter('=',"") }
            if(declared.any { it!=subject }) return null
            uri.path.orEmpty().trimEnd('/').substringAfterLast('/').takeIf {
                it.matches(Regex("[A-Za-z0-9_-]{1,220}")) && it !in setOf("detail","movies","moviedetail","download-app")
            }
        } catch (_: Exception) { null }
    }

    fun knownPath(metadata: JSONObject, subject: String): String? {
        val declared=MovieboxPlayback.text(metadata,"subjectId")
        if(declared.isNotBlank() && declared!=subject) return null
        val explicit=MovieboxPlayback.text(metadata,"detailPath").ifBlank { MovieboxPlayback.text(metadata,"detailUrl") }
        // A native /detail?id=... route is not an H5 content slug. Validate the
        // unmodified URL before extracting its path or dropping its ID query.
        return if(explicit.isNotBlank()) path(explicit,subject)
            else path(MovieboxPlayback.detailPath(metadata,subject),subject)
    }

    /** Only direct fields from the same Nuxt subject object are read; JS is never run. */
    fun catalogPath(html: String, subject: String): String? = try {
        if(html.length>2_000_000) null else {
            val node=Jsoup.parse(html).selectFirst("script#__NUXT_DATA__[type=application/json]")
            val table=JSONArray(node?.data() ?: "[]")
            fun field(row: JSONObject, key: String): String {
                val index=row.opt(key) as? Number ?: return ""
                val i=index.toLong()
                if(i<0 || i>=table.length().toLong() || i.toDouble()!=index.toDouble()) return ""
                val value=table.opt(i.toInt())
                return if(value is String || value is Number) value.toString() else ""
            }
            (0 until minOf(table.length(),30_000)).mapNotNull { i ->
                val row=table.optJSONObject(i) ?: return@mapNotNull null
                if(field(row,"subjectId")!=subject) return@mapNotNull null
                path(field(row,"detailPath").ifBlank { field(row,"detailUrl") },subject)
            }.distinct().singleOrNull()
        }
    } catch (_: Exception) { null }

    /** The public catalog searches localized titles server-side. Preserve its IDs and
     * canonical titles; a translated query is never treated as an identity alias. */
    fun catalogSubjects(html: String): List<JSONObject> = try {
        if(html.length>2_000_000) emptyList() else {
            val script=Jsoup.parse(html).selectFirst("script#__NUXT_DATA__[type=application/json]")
            val table=JSONArray(script?.data() ?: "[]")
            fun value(row: JSONObject,key: String): Any? {
                val n=row.opt(key) as? Number ?: return null
                val i=n.toLong()
                if(i<0 || i>=table.length() || i.toDouble()!=n.toDouble()) return null
                return table.opt(i.toInt())
            }
            (0 until minOf(table.length(),30_000)).mapNotNull { i ->
                val row=table.optJSONObject(i) ?: return@mapNotNull null
                val id=value(row,"subjectId")?.toString().orEmpty()
                val title=value(row,"title") as? String ?: return@mapNotNull null
                val type=(value(row,"subjectType") as? Number)?.takeIf { it.toDouble() in listOf(1.0,2.0) }?.toInt()
                if(!id.matches(Regex("[0-9]{1,24}")) || title.isBlank() || type !in listOf(1,2)) return@mapNotNull null
                val cover=value(row,"cover") as? JSONObject
                val poster=cover?.let { value(it,"url") as? String }?.takeIf { MovieboxPlayback.http(it) }
                JSONObject().put("subjectId",id).put("title",title).put("subjectType",type)
                    .put("cover",JSONObject().put("url",poster.orEmpty()))
            }.distinctBy { it.getString("subjectId") }.take(30)
        }
    } catch (_: Exception) { emptyList() }

    suspend fun resolve(subject: String, selectedSubject: String, selectedTitle: String?, selectedPath: String?,
        metadata: JSONObject, read: suspend (String) -> String?): String {
        if(!subject.matches(Regex("[0-9]{1,24}"))) return ""
        val declared=MovieboxPlayback.text(metadata,"subjectId")
        if(declared.isNotBlank() && declared!=subject) return ""
        if(subject==selectedSubject) path(selectedPath,subject)?.let { return it }
        val now=clock()
        synchronized(paths) { paths[subject]?.takeIf { now-it.first in 0 until 60_000 }?.let { return it.second } }
        // Native and saved display titles can differ by language. Search both, but
        // only the exact selected subject may supply a navigation path.
        val titles=(listOf("title","originalTitle").map { MovieboxPlayback.text(metadata,it) }+
            listOfNotNull(selectedTitle.takeIf { subject==selectedSubject }))
            .map { it.trim().take(200) }.filter { it.isNotBlank() }.distinct().take(3)
        if(titles.isEmpty()) return ""
        val found=try { withTimeoutOrNull(budgetMs) {
            for(title in titles) {
                val url="https://moviebox.ph/web/searchResult?keyword=${URLEncoder.encode(title,"UTF-8")}"
                val html=try { read(url) } catch(e: CancellationException) { throw e } catch (_: Exception) { null }
                html?.let { catalogPath(it,subject) }?.let { return@withTimeoutOrNull it }
            }
            null
        } } catch(e: CancellationException) { throw e } catch (_: Exception) { null } ?: return ""
        synchronized(paths) {
            if(paths.size>=64) paths.remove(paths.keys.first())
            paths[subject]=(clock()) to found
        }
        return found
    }
}
