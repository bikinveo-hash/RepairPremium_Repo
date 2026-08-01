package com.OppaDrama

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Diagnostik M1 + M2, terbaca langsung di UI CloudStream tanpa adb/logcat.
 *
 * M2 diverifikasi dengan UJI IDENTITAS BYTE, bukan dengan playback. Segmen
 * belum dipotong pada M2, jadi 3001 memang masih diharapkan muncul kalau
 * diputar — playback bukan kriteria kelulusan di tahap ini.
 *
 * Semua HTTP di sini memakai HttpURLConnection langsung, tidak memakai API
 * internal CloudStream, supaya file ini pasti bisa dikompilasi tanpa
 * bergantung pada detail versi yang belum diaudit.
 *
 * Hapus provider ini setelah M2 diverifikasi.
 */
class OppaDramaDiagProvider : MainAPI() {
    override var mainUrl = "http://127.0.0.1"
    override var name = "OPPADRAMA-DIAG"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = mainPageOf("m2" to "Diagnostik M1 + M2")

    private data class Fetched(val code: Int, val len: Int, val sha: String, val err: String?)

    private fun fetch(url: String, range: String? = null): Fetched {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                )
                range?.let { setRequestProperty("Range", it) }
            }
            val code = conn.responseCode
            val bytes = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.readBytes() ?: ByteArray(0)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }.take(16)
            Fetched(code, bytes.size, sha, null)
        } catch (e: Throwable) {
            Fetched(-1, -1, "-", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = mutableListOf<String>()
        val running = LocalProxy.isRunning

        // ------------------------------- M1 -------------------------------
        rows += "===== M1 ====="
        rows += "R1 bind socket    : " + if (running) "OK port=${LocalProxy.port}" else "GAGAL"
        LocalProxy.lastError?.let { rows += "   error terakhir : $it" }
        rows += "R2 started_at     : ${LocalProxy.startedAtMs}"
        rows += "R2 uptime         : ${LocalProxy.uptimeSec} detik"

        if (running) {
            val p1 = fetch("http://127.0.0.1:${LocalProxy.port}/ping")
            val p2 = fetch("http://127.0.0.1:${LocalProxy.port}/ping")
            rows += "R3 probe 1        : HTTP ${p1.code} ${p1.err ?: ""}"
            rows += "R3 probe 2        : HTTP ${p2.code} ${p2.err ?: ""}"
        }
        rows += "R3 total dilayani : ${LocalProxy.served.get()}"

        // ------------------------------- M2 -------------------------------
        rows += "===== M2 ====="
        var playlistOk: Boolean? = null
        var segmenOk: Boolean? = null

        if (!running) {
            rows += "dilewati, proxy mati"
        } else {
            val targets = listOf(
                Triple(
                    "playlist",
                    "https://cdn.turboviplay.com/data3/6a469183d196d/6a469183d196d.m3u8",
                    null as String?
                ),
                Triple(
                    "segmen",
                    "https://lh3.googleusercontent.com/d/1RZi4hR-pYMZ3eKzxNMi-B00qO47UAbjh=d",
                    "bytes=0-16383"
                )
            )
            for ((label, up, rng) in targets) {
                val prox = LocalProxy.proxyUrl(up)
                if (prox == null) {
                    rows += "$label: proxyUrl null"
                    continue
                }
                val direct = fetch(up, rng)
                val via = fetch(prox, rng)
                val match = direct.sha == via.sha && direct.len == via.len && direct.len > 0
                if (label == "playlist") playlistOk = match else segmenOk = match
                rows += "$label langsung   : HTTP ${direct.code} len=${direct.len} sha=${direct.sha}"
                rows += "$label viaProxy   : HTTP ${via.code} len=${via.len} sha=${via.sha}"
                rows += "$label IDENTIK    : " + if (match) "YA" else "TIDAK"
                direct.err?.let { rows += "   err langsung  : $it" }
                via.err?.let { rows += "   err proxy     : $it" }
            }

            // allowlist wajib menolak host di luar daftar suffix
            val outside = LocalProxy.proxyUrl("http://example.com/")
            val denyCode = if (outside != null) fetch(outside).code else -1
            rows += "allowlist tolak   : HTTP $denyCode (harus 403)"
            rows += "proxied=${LocalProxy.proxied.get()} denied=${LocalProxy.denied.get()} " +
                "swallowed=${LocalProxy.swallowed.get()} bytes_out=${LocalProxy.bytesOut.get()}"
        }

        val verdict = when {
            !running -> "VERDICT: R1 GAGAL — arsitektur proxy gugur, hentikan"
            playlistOk == false -> "VERDICT: M2 GAGAL di playlist — pipeline HTTP, bukan scanner"
            segmenOk == false -> "VERDICT: M2 GAGAL di segmen — cek streaming / Content-Length"
            playlistOk == true && segmenOk == true -> "VERDICT: M2 LULUS — byte identik, lanjut M3"
            else -> "VERDICT: belum lengkap, tarik-segarkan halaman"
        }

        val items: List<SearchResponse> = (listOf(verdict) + rows).mapIndexed { i, text ->
            newMovieSearchResponse(text, "diag://$i", TvType.Others)
        }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse =
        newMovieLoadResponse("Diagnostik", url, TvType.Others, url) {
            this.plot = "Tarik-segarkan halaman utama untuk memperbarui angka."
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
}
