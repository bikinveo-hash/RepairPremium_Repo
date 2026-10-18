# Reacher diagnostic kit V3 (tanpa root)

**Update berikutnya:** video `585430.mp4` dan dua Share membuktikan konteks judul H5 yang
hilang. Perbaikan produksi versi 16 kini memulihkan path/Referer hanya untuk dua subject
Reacher. Kit V3 tidak perlu diulang untuk memeriksa perbaikan ini; uji langsung plugin versi
16. Detail dan batas verifikasi ada di `../REACHER_APK_CONTRACT.md` dan fixture
`reacher_share_evidence_20261016.json`. Bagian di bawah menyimpan riwayat kit sebelumnya.

V3 memeriksa kandidat MP4 yang benar-benar dikembalikan untuk Reacher, **bukan patch
playback**. Plugin produksi tetap versi 15. Tidak memasang APK Mod, membaca data privat
aplikasi, menangkap HTTPS aplikasi lain, memakai akun lain, mengubah region/TLS, mengekspor
cookie/token/URL media, atau mengunduh video penuh.

## Jalankan di Termux

Ekstrak `Reacher_Diagnostic_Kit_V3.zip` ke Download. Jalankan:

```bash
bash /storage/emulated/0/Download/Reacher_Diagnostic_Kit_V3/RUN_TERMUX.sh
```

Tekan **Enter pada kedua pertanyaan** untuk memeriksa Reacher asli dan versi Indonesia
secara otomatis. Indonesia diikuti hanya jika server kembali menyatakan hubungan dub-nya.
Kolom kedua opsional untuk judul pembanding yang berbeda dan sudah berhasil.
Script memasang Python jika belum tersedia dan meminta izin penyimpanan standar Termux.
Output `Reacher_Diagnostic_<waktu>_<acak>.zip` berada di Download; kirim ZIP tersebut.
Script bisa memerlukan beberapa menit jika CDN timeout. Tidak perlu mengirim kredensial,
raw logcat, URL video, atau memasang APK Mod.

## Bukti yang mendasari V3

Hasil V2 dari HP pengguna, tersimpan sebagai fixture `phone_reacher_native_20261016.json`:

- Guest, `get`, `dub-info`, `season-info`, dan `resource`: HTTP 200/code 0.
- Server menghubungkan asli `3620534210064175096` dengan Indonesia `4149489342181954296`.
  Tidak ada subject dub Inggris terpisah pada respons ini; bahasa asli tidak diasumsikan.
- Native `play-info` S1E1: dua kandidat MP4 asli dan satu kandidat MP4 Indonesia.
  Bukan URL notice yang telah dikenal, tetapi isi/durasinya **belum diperiksa** oleh V2.
- `detailUrl` kosong pada kedua edisi. `PLAY_URL_WEB` memiliki URL kosong, sehingga bukan
  tujuan player yang bisa digunakan. H5 sebelumnya kosong untuk konteks yang diuji.
- Audit lanjutan DTO APK menemukan `DownloadListBean.items` sebenarnya memakai
  `@SerializedName("list")`; `DownloadItem.url` memakai `@SerializedName("resourceLink")`.
  Kit V2 membaca nama properti Kotlin, sehingga angka nol resource V2 **tidak membuktikan
  respons resource kosong**. V3 membaca nama JSON sesuai APK. Ini koreksi kit diagnostik,
  bukan bukti bug parser H5 produksi atau alasan mengganti sistem playback.

## Pemeriksaan yang dilakukan

- Guest katalog yang sudah ada, instance acak milik probe, satu host API yang sama.
  Ini **bukan capture runtime APK resmi**. Sesi/token/kunci hanya di memori.
  Source protokol publik dipatok commit dan SHA-256; tidak menyimpan source atau profil
  device-bound lama. Query wire dan canonical signature tetap diurutkan seperti V2.
- Hanya endpoint yang sudah dibuktikan: guest bootstrap, `dub-info`, `get`, `season-info`,
  `resource`, dan diagnostik `play-info`. Tidak memindai host atau kombinasi episode acak.
- Hanya dua ID Reacher terbukti boleh menjalankan pemeriksaan media, setelah `get` cocok.
  S1E1 dan perbandingan S1E2 hanya bila dideklarasikan metadata season. Indeks tidak digeser.
  Tidak pernah menggunakan S1E2 sebagai fallback S1E1. Respons dengan identitas eksplisit
  yang berbeda tidak diperiksa medianya. Bahasa selain whitelist tidak diminta.
- `resource.list` dicocokkan dengan subject/se/ep; maksimal dua item tepat per episode
  diperiksa medianya, sedangkan metadata ID tetap dicatat. Link resource tetap dianggap metadata
  download: pemeriksaan sampel tidak otomatis mempromosikannya menjadi sumber player.
  Jika array tidak ada, jumlah resource tepat dilaporkan UNKNOWN, bukan nol.
- Media memakai GET Range, maksimal 12 URL+cookie unik, 36 request, 64 KiB per respons
  (batas baca aplikasi 2.25 MiB keseluruhan), timeout 8 detik, anggaran 150 detik untuk
  memulai request media. Metadata API punya batas waktu sendiri. Jika server mengabaikan
  Range, stream tetap ditutup setelah sampel terbatas. Tidak menyimpan byte media.
- Inspeksi format MP4, struktur `moov/mvhd`, durasi, track audio/video. Jika `moov` berada
  setelah `mdat`, probe melompat dengan offset box/Range, bukan membaca seluruh video.
  Metadata besar di luar batas dilaporkan BLOCKED, bukan diloloskan secara spekulatif.
  Format selain MP4 pada probe ini NOT_TESTED; validator HLS/DASH produksi tidak diubah.
- Episode dengan MP4 berdurasi di bawah 300 detik ditolak sebagai video pendek yang belum
  terbukti episode. Notice dengan URL dikenal, HTML/JSON/error HTTP, marker akses/member,
  dan redirect notice tidak diloloskan. Durasi panjang sendiri bukan bukti episode benar.
- HTTPS dengan TLS standar, alamat jaringan publik saja. Bearer/header API tidak diberikan
  ke CDN. Cookie yang dikembalikan untuk kandidat hanya dipakai pada origin awal yang sama,
  dan dilepas pada redirect lintas origin. Tidak ada proxy, bypass, atau autentikasi fiktif.
- URL+cookie dibandingkan secara persis dan disimpan hanya di memori. Signature/query
  berbeda tidak digabungkan. Output memberi label sementara `media-1`, dst; penggunaan
  URL sama antar-episode/edisi ditandai. Ini petunjuk notice atau pemetaan salah, bukan
  keputusan otomatis bahwa semua versi tersebut pasti salah.
- Keluaran hanya ID, indeks, jumlah, struktur, metadata numerik, klasifikasi error, HTTP,
  byte count, durasi container, track, checksum sampel, dan label pembanding. Tidak ada
  cookie, token, kunci, body/pesan error mentah, URL atau query media yang ditulis.

**Arti PASS pada satu media check:** HTTP, container MP4, durasi minimal, dan track terbaca
pada sampel. Bukan decode, bukan verifikasi visual judul/episode, bukan pemutaran Android.
`decode` dan `device_playback` tetap NOT_TESTED. Respons sintetis pada unit test juga bukan
bukti Reacher playable. Hasil ini diperlukan sebelum adapter produksi dapat dipertimbangkan.

Tanpa pemeriksaan media, gunakan `python reacher_diagnostic.py --metadata-only`.

## Opsional: log plugin melalui ADB yang sudah diizinkan

Termux biasa tidak otomatis dapat membaca log aplikasi lain atau traffic HTTPS aplikasi
MovieBox. Jika perangkat menyediakan Wireless debugging dan Anda sudah memasangkan ADB
milik sendiri, jalankan:

```bash
python reacher_diagnostic.py --logs-only --adb-seconds 90 --out /storage/emulated/0/Download
```

Buka Reacher S1E1 di CloudStream dengan plugin versi 15, kemudian judul pembanding.
Hanya field log Moviebox yang dikenal direkonstruksi. Tidak menyimpan raw log, menghapus
buffer log, meminta root, atau melewati TLS pinning. Tanpa akses log yang diizinkan, hasil
BLOCKED. Ini tidak mengklaim menangkap request aplikasi MovieBox resmi.

## Pengujian pengembang

```bash
python -m unittest discover -s Moviebox/tests/diagnostics -v
```

Fixture nyata V1/V2 membuktikan hanya metadata/HTTP dari HP. Fixture MP4 sintetis menguji
parser, batas baca/Range, redirect/cookie, notice, identitas dan privasi. Semua tetap terpisah
dari kode produksi dan pengujian pemutaran perangkat.

## Hasil nyata V3: jangan aktifkan kembali kandidat native/resource

Hasil HP `Reacher_Diagnostic_20261016_202731_a7bb39.zip` sudah dianalisis. Seluruh sepuluh
kandidat native/resource yang diperiksa untuk asli/Indonesia S1E1/S1E2 memakai URL+cookie yang
sama. Satu GET Range (hasil dipakai cache) menemukan MP4 824.720 byte, durasi 22,708 detik.
Seluruhnya ditolak: `SHORT_MEDIA_NOT_VERIFIED_EPISODE`. Metadata resource memang memuat episode,
tetapi durasi katalog 3345/3276 detik tidak cocok dengan video yang dikirimkan. Fixture ringkas
nyata ada di `phone_reacher_media_20261016.json`; visual/pesan video belum diketahui.

Jangan mengulang V3 yang sama atau memakai kandidat tersebut sebagai fallback produksi.
Bukti berikutnya: apakah aplikasi resmi terbaru yang tidak dimodifikasi benar-benar memutar
Reacher asli S1E1 sampai adegan episode, beserta tautan Share edisinya. Bila hanya muncul
pemberitahuan singkat, catat teksnya. Perekaman layar tidak dapat memperlihatkan header HTTPS;
perbandingan request hanya melalui diagnostik resmi atau lingkungan debug yang diizinkan.
Tidak perlu mengirim token/cookie/URL media atau memasang alat penyadapan.
