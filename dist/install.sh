#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
#  Nevus Browser — Termux installer
#
#  Pakai:
#    curl -sSL https://raw.githubusercontent.com/Hendra829/UpdateNevus-Browser/main/dist/install.sh | bash
#
#  Kalau repo private, sediakan token dulu:
#    export NEVUS_GH_TOKEN="ghp_xxx..."
#    curl -sSL -H "Authorization: Bearer $NEVUS_GH_TOKEN" \
#         https://raw.githubusercontent.com/Hendra829/UpdateNevus-Browser/main/dist/install.sh | bash
#
#  Default: unduh varian universal (jalan di device 64-bit apa pun).
#  Override manual:
#    bash install.sh arm64-v8a     # arm64-v8a saja
#    bash install.sh x86_64        # x86_64 saja
#    bash install.sh universal     # eksplisit universal
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO="Hendra829/UpdateNevus-Browser"
VERSION="v3.0.0"
DEST_DIR="/sdcard/Download"

log()  { printf '\033[1;36m▸\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# 1 ─ Cek: benar-benar di Termux?
[ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ] \
    || die "Skrip ini hanya untuk Termux. Buka Termux dulu, baru jalankan."

# 2 ─ Pastikan curl + termux-tools tersedia (butuh curl untuk download, termux-open untuk installer).
ensure_pkg() {
    local pkg="$1"; local bin="${2:-$1}"
    command -v "$bin" >/dev/null 2>&1 && return 0
    log "Installing $pkg..."
    pkg install -y "$pkg" >/dev/null 2>&1 \
        || { pkg update -y >/dev/null && pkg install -y "$pkg" >/dev/null; } \
        || die "Gagal install $pkg. Coba: pkg update && pkg install $pkg"
}
ensure_pkg curl curl
ensure_pkg termux-tools termux-open

# 3 ─ Storage: /sdcard harus terbaca. Termux minta izin lewat dialog.
if [ ! -r /sdcard ]; then
    log "Meminta izin akses storage (setujui pop-up)..."
    termux-setup-storage
    for _ in 1 2 3 4 5; do
        [ -r /sdcard ] && break
        sleep 1
    done
    [ -r /sdcard ] || die "Izin storage belum diberikan. Jalankan ulang setelah setujui."
fi

# 4 ─ Pilih APK. Default: universal (jalan di device 64-bit apa pun). Arg 1: paksa varian.
if [ -n "${1:-}" ]; then
    VARIANT="$1"
    case "$VARIANT" in
        arm64-v8a|x86_64|universal) ;;
        armeabi-v7a) die "APK ini 64-bit only. Device armeabi-v7a (32-bit) tidak didukung." ;;
        *) die "Argumen tidak dikenal: '$VARIANT'. Pakai: arm64-v8a | x86_64 | universal (default)" ;;
    esac
else
    VARIANT="universal"
fi

APK_NAME="NevusBrowser-${VERSION}-debug-${VARIANT}.apk"
URL_RAW="https://raw.githubusercontent.com/${REPO}/main/dist/${APK_NAME}"
URL_API="https://api.github.com/repos/${REPO}/contents/dist/${APK_NAME}?ref=main"
DEST="${DEST_DIR}/${APK_NAME}"

log "Varian APK: $VARIANT"
log "Tujuan: $DEST"

mkdir -p "$DEST_DIR"

# 5 ─ Download. Kalau ada token, pakai API endpoint (untuk repo private). Kalau tidak, raw endpoint.
if [ -n "${NEVUS_GH_TOKEN:-}" ]; then
    log "Menggunakan token GitHub (repo private)..."
    curl -L --fail --progress-bar \
         -H "Authorization: Bearer ${NEVUS_GH_TOKEN}" \
         -H "Accept: application/vnd.github.raw" \
         -H "X-GitHub-Api-Version: 2022-11-28" \
         -o "$DEST" \
         "$URL_API" \
        || die "Download gagal via API. Cek token & nama repo."
else
    log "Download tanpa token (repo harus public)..."
    curl -L --fail --progress-bar \
         -o "$DEST" \
         "$URL_RAW" \
        || {
            warn "Download gagal — apakah repo private? Kalau ya, jalankan lagi dengan:"
            warn "  export NEVUS_GH_TOKEN=\"ghp_xxx...\""
            warn "  curl -sSL $URL_RAW | bash"
            die "Batal."
        }
fi

# 6 ─ Validasi ukuran (APK v3.0.0 ~5.6 MB — kalau jauh lebih kecil, kemungkinan HTML error page).
SIZE_BYTES=$(stat -c%s "$DEST" 2>/dev/null || stat -f%z "$DEST" 2>/dev/null || echo 0)
if [ "$SIZE_BYTES" -lt 1000000 ]; then
    warn "Ukuran file terlalu kecil ($SIZE_BYTES bytes). Kemungkinan bukan APK:"
    head -c 300 "$DEST" | sed 's/^/  /'
    die "Batal — file bukan APK valid."
fi

SIZE_H=$(du -h "$DEST" 2>/dev/null | cut -f1)
ok "APK terunduh ($SIZE_H)"

# 7 ─ Serahkan ke installer Android. termux-open akan buka Package Installer sistem.
log "Membuka installer sistem..."
termux-open "$DEST"

ok "Konfirmasi prompt install di layar HP."
echo ""
echo "  Kalau muncul 'Install blocked' atau 'Install unknown apps':"
echo "    Settings → Apps → [file manager Anda] → Install unknown apps → Allow"
echo "  Lalu jalankan ulang skrip ini (APK sudah ada di ${DEST})."
