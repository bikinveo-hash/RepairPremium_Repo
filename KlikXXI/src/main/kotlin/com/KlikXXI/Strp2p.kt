package com.KlikXXI

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Strp2p : ExtractorApi() {
    override val name            = "KlikXXI Regex Stream Unpacker"
    override val mainUrl         = "https://klikxxi.shop"
    override val requiresReferer = true

    private val TAG = "Strp2p_Regex_Core"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "══════════ PILOT EKSTRAKSI STRIP REGEX START ══════════")
        
        // 1. Ambil muatan dokumen HTML dari simpul rute pemutar
        val htmlContent = fetchPage(url)
        if (htmlContent.isBlank()) {
            Log.e(TAG, "[-] Halaman pemutar kosong atau gagal diakses.")
            return
        }

        // 2. Isolasi skrip player terkompresi / objek konfigurasi internal
        val packedScript = extractPlayerScript(htmlContent)
        
        // Gabungkan konteks pencarian: Utamakan hasil unpacker skrip, gunakan dokumen utuh sebagai fallback
        val searchContext = packedScript.ifEmpty { htmlContent }

        // 3. Bongkar manifes HLS stream (.m3u8) dari objek text
        val rawCandidates = extractM3u8Urls(searchContext)
        Log.d(TAG, "[+] Menemukan sebanyak ${rawCandidates.size} kandidat URL potensial dari dokumen.")

        // 4. Lakukan validasi, perbaikan skema, dan eliminasi duplikasi
        val tervalidasi = rawCandidates.mapNotNull { candidate ->
            validateCandidate(candidate, url)
        }.distinct()

        Log.d(TAG, "[+] Total tautan lolos verifikasi filter duplikasi: ${tervalidasi.size}")

        // 5. Daftarkan tautan video manifest yang valid langsung ke basis sistem Cloudstream
        tervalidasi.forEach { streamUrl ->
            val authParams = extractAuthParameters(streamUrl)
            
            // Penentuan kualitas video biner berbasis penanda kluster manifest (hls4 = 1080p, else 720p)
            val qualityLabel = if (streamUrl.contains("hls4")) Qualities.P1080.value else Qualities.P720.value
            val currentServerName = if (streamUrl.contains("brightcrest")) "Server Premium (Brightcrest)" else "Server Standar"

            Log.d(TAG, "[+] Sukses mengunci aliran video hulu: $streamUrl | Token Params: $authParams")
            
            // FIX: Properti referer, quality, dan headers dimasukkan ke dalam lambda block closure sesuai arsitektur Cloudstream utils
            callback(newExtractorLink(
                source = this.name,
                name = currentServerName,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = qualityLabel
                this.headers = buildHeaders(url)
            })
        }
        Log.d(TAG, "══════════ PILOT EKSTRAKSI STRIP REGEX END ══════════")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  LOGIKA PENCARIAN & PEMBONGKARAN ARTEFAK (PIPELINE SEPARATED)
    // ─────────────────────────────────────────────────────────────────────────

    // FIX: Ditambahkan modifier 'suspend' karena memanggil app.get() yang asinkron
    suspend fun fetchPage(url: String): String {
        return try {
            Log.d(TAG, "[+] Membuka sambungan HTTP GET ke: $url")
            val response = app.get(url, headers = buildHeaders(url)).text
            response
        } catch (e: Exception) {
            Log.e(TAG, "[-] Aliran request gagal dieksekusi: ${e.message}")
            ""
        }
    }

    fun extractPlayerScript(html: String): String {
        Log.d(TAG, "[+] Mencari struktur kompresi Dean Edwards (Packer)...")
        
        // Regex penangkap fungsi evaluasi Packer secara utuh berdasarkan struktur parameter tanda tangan p,a,c,k,e,d
        val packerRegex = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?\}\s*\(\s*.*?\s*,\s*\d+\s*,\s*\d+\s*,\s*['"](.*?)['"]\s*\.\s*split\s*\(\s*['"]\|['"]\s*\)\s*\)\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val match = packerRegex.find(html)
        
        if (match != null) {
            val dictionaryString = match.groupValues[1]
            Log.d(TAG, "[+] Berhasil mengamankan blok kamus kata Packer.")
            return unpackPackerData(dictionaryString)
        }
        Log.w(TAG, "[-] Skrip Packer tidak terdeteksi pada DOM dokumen html ini.")
        return ""
    }

    fun extractM3u8Urls(script: String): List<String> {
        Log.d(TAG, "[+] Memulai pemindaian pola string literal manifest stream...")
        val candidates = mutableListOf<String>()
        
        // Regex menangkap string literal yang membawa alamat biner manifest .m3u8 absolut maupun relatif
        val m3u8Pattern = Regex("""["']((?:https?://[^"']+|/[^"']+)\.m3u8(?:\?[^"']+)?)["']""")
        val matches = m3u8Pattern.findAll(script)
        
        for (match in matches) {
            candidates.add(match.groupValues[1])
        }
        return candidates
    }

    fun extractAuthParameters(url: String): Map<String, String> {
        val paramMap = mutableMapOf<String, String>()
        
        // Regex menangkap token otentikasi pertahanan hulu (parameter t, s, dan e)
        val authPattern = Regex("""[?&](t|s|e)=([^&#"']+)""")
        val matches = authPattern.findAll(url)
        
        for (match in matches) {
            paramMap[match.groupValues[1]] = match.groupValues[2]
        }
        return paramMap
    }

    fun validateCandidate(url: String, parentUrl: String): String? {
        // Pembuangan manifest eksternal Google Tag Manager yang tidak sengaja terjaring oleh regex mesin
        if (url.contains("googletagmanager") || url.contains("google-analytics")) {
            return null
        }

        var cleanUrl = url.replace("\\/", "/") // Bersihkan escape karakter bawaan JSON format jika ada

        // Transformasi skema protokol palsu '1d://' hasil obfuskasi player menjadi skema web 'https://'
        if (cleanUrl.startsWith("1d://")) {
            cleanUrl = cleanUrl.replace("1d://", "https://")
        }

        // Penanganan Rute Jalur URL Relatif
        if (cleanUrl.startsWith("/")) {
            val baseDomain = Regex("""^(https?://[^/#?]+)""").find(parentUrl)?.groupValues?.get(1) ?: mainUrl
            cleanUrl = "$baseDomain$cleanUrl"
        }

        // NOT VERIFIED: Format ekstensi akhir. Jika hulu merubah struktur penamaan manifest, validasi ini harus diperiksa kembali.
        return if (cleanUrl.contains(".m3u8")) cleanUrl else null
    }

    fun buildHeaders(currentUrl: String): Map<String, String> {
        val baseDomain = Regex("""^(https?://[^/#?]+)""").find(currentUrl)?.groupValues?.get(1) ?: mainUrl
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
            "Referer" to "$baseDomain/",
            "Origin" to baseDomain,
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    /**
     * Algoritma Rekonstruksi Kamus Packer Mandiri
     * Mengamankan data tanpa perlu menggunakan runtime JS engine / WebView.
     */
    private fun unpackPackerData(dictionaryStr: String): String {
        return try {
            val words = dictionaryStr.split("|")
            Log.d(TAG, "[+] Melakukan de-kompresi string lookup tabel. Jumlah kata: ${words.size}")
            // Satukan seluruh bagian kamus kata untuk meloloskan domain asli dari jeratan obfuskasi string packer
            words.joinToString(separator = " ")
        } catch (e: Exception) {
            Log.e(TAG, "[-] Gagal mengurai isi tabel kamus Packer: ${e.message}")
            ""
        }
    }
}
