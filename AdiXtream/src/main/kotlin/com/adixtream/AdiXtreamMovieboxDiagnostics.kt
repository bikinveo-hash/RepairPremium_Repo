package com.adixtream

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import org.json.JSONObject

/** Bounded sanitized events, accessible on the plugin settings gear without adb/root. */
internal object AdiXtreamMovieboxDiagnostics {
    private val rows = java.util.ArrayDeque<String>()
    private val keys =
        setOf(
            "subjectId",
            "tmdbId",
            "se",
            "ep",
            "type",
            "quality",
            "codec",
            "audio",
            "host",
            "status",
            "mime",
            "redirect",
            "attempt",
            "elapsedMs",
            "ranged",
            "retryAfterMs",
            "protocol",
            "candidates",
            "validated",
            "verified",
            "hasCookie",
            "hasSignHeader",
        )

    @Synchronized
    fun clear() {
        rows.clear()
    }

    @Synchronized
    fun record(stage: String, reason: String, fields: Map<String, Any?> = emptyMap()) {
        val row = JSONObject().put("stage", stage).put("reason", reason)
        for ((key, value) in fields) if (key in keys && value != null) {
            // Values originate in typed diagnostics, never request/response headers or URLs.
            row.put(key, if (value is String) value.filter { it >= ' ' }.take(100) else value)
        }
        val line = row.toString()
        if (rows.size >= 160) rows.removeFirst()
        rows.addLast(line)
        Log.d("AdiXtreamMoviebox", "[$stage] $line")
    }

    @Synchronized
    fun snapshot(): String = "AdiXtream Moviebox playback diagnostics\n" + rows.joinToString("\n")

    fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Diagnostik Moviebox — AdiXtream")
            .setMessage(
                "Setelah mencoba film, salin log ini. Log tidak memuat URL media, cookie, atau token.\n\n" +
                    snapshot()
            )
            .setPositiveButton("Salin log") { _, _ ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("Moviebox diagnostics", snapshot()))
            }
            .setNeutralButton("Bersihkan") { _, _ -> clear() }
            .setNegativeButton("Tutup", null)
            .show()
    }
}
