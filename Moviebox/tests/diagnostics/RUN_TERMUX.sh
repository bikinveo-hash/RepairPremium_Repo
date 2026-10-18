#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
if ! command -v python >/dev/null 2>&1; then pkg install -y python; fi
if [ ! -d "$HOME/storage/downloads" ]; then
    termux-setup-storage
    read -r -p 'Izinkan akses penyimpanan, lalu tekan Enter: ' ready
fi
echo 'Reacher Diagnostic V3 — pemeriksaan MP4 terbatas; sumber plugin tidak diubah.'
echo 'Tanpa video penuh: maksimal 36 request media, masing-masing 64 KiB; tidak memutar video.'
read -r -p 'ID/tautan Reacher terpilih (Enter = ID awal): ' selected
read -r -p 'Judul lain yang berhasil, bukan Reacher (Enter boleh kosong): ' control
args=(--subject "${selected:-3620534210064175096}" --check-media --out "$HOME/storage/downloads")
if [ -n "$control" ]; then args+=(--control "$control"); fi
python reacher_diagnostic.py "${args[@]}"
