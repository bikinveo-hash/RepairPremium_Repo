package com.michat88

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder

const val TMDBAPI = "https://api.themoviedb.org/3"

suspend fun fetchtmdb(title: String?, year: Int?, isMovie: Boolean): Int? {
    if (title.isNullOrBlank()) return null
    val encodedTitle = URLEncoder.encode(title, "UTF-8")
    // API Key rahasia dari file JAR
    val url = "$TMDBAPI/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=$encodedTitle"
    val res = app.get(url).text
    val results = JSONObject(res).optJSONArray("results") ?: return null

    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val resTitle = if (isMovie) item.optString("title") else item.optString("name")
        val dateStr = if (isMovie) item.optString("release_date") else item.optString("first_air_date")
        val resYear = dateStr.take(4).toIntOrNull()

        if (resTitle.equals(title, true) && (year == null || resYear == year)) {
            return item.optInt("id")
        }
    }
    return null
}
