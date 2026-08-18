# UpdateNevus-Browser

Repositori distribusi biner debug untuk **NevusMediaBridge** — aplikasi Android
native berbasis WebView (browser + media bridge) di lini produk Nevus.

## Isi repo

| Path                                | Ukuran (compressed) | Ukuran asli | Deskripsi                                |
| ----------------------------------- | ------------------- | ----------- | ---------------------------------------- |
| `dist/NevusMediaBridge-debug.zip`   | ~27 MB (LZMA)       | ~76 MB      | Arsip berisi APK debug siap-install      |
| _(inside)_ `NevusMediaBridge-debug.apk` | —               | ~76 MB      | Package: `com.nevus.mediabridge.debug` · v2.0.0 |

Semua biner (`*.apk`, `*.zip`, `*.aab`, `*.jar`, `*.so`) disimpan melalui
**Git LFS** — lihat `.gitattributes`. Working tree tetap ringan; file besar
diambil hanya jika klien meminta.

## Cara clone

```bash
# Pastikan git-lfs terinstall sekali di mesin Anda:
git lfs install

# Clone seperti biasa — LFS otomatis menarik file besar:
git clone https://github.com/Hendra829/UpdateNevus-Browser.git
```

Jika hanya perlu metadata (tanpa menarik APK 27 MB):

```bash
GIT_LFS_SKIP_SMUDGE=1 git clone https://github.com/Hendra829/UpdateNevus-Browser.git
```

## Cara pasang APK ke device

```bash
# 1. Ekstrak arsip zip untuk mendapatkan file APK
unzip dist/NevusMediaBridge-debug.zip -d /tmp/

# 2. Pasang via adb (device dengan USB debugging aktif)
adb install /tmp/NevusMediaBridge-debug.apk
```

Atau salin `NevusMediaBridge-debug.apk` ke penyimpanan device dan buka
lewat file manager (butuh izin "Install unknown apps" di Settings).

## Catatan penting

- APK adalah **debug build** — `android:debuggable="true"`, tidak
  dioptimalkan/di-minify. **Jangan didistribusikan ke pengguna akhir**;
  hanya untuk testing internal.
- Repo bersifat **private**. Akses hanya untuk kolaborator.
- Tidak menyertakan source code — hanya artefak build biner.
- Untuk rilis versi baru, tambahkan file APK/ZIP baru di `dist/` — LFS
  otomatis menangani ukuran, working tree tetap ringan.

## Kompatibilitas

- **Target SDK**: 34 (Android 14)
- **ABI didukung**: `arm64-v8a`, `x86_64` (device 64-bit modern & emulator)
- **Minimum device**: Android 7.0+ (API 24) direkomendasikan

## Referensi audit

Analisa statis APK ini tersedia sebagai laporan terpisah (lihat riwayat
kolaborasi). Rekomendasi utama yang perlu diterapkan pada _source repo_
NevusMediaBridge:

1. Sandbox validasi path di semua `@JavascriptInterface` method
2. Perketat `mixedContentMode` → `MIXED_CONTENT_NEVER_ALLOW`
3. Konfigurasi `dataExtractionRules` untuk exclude data sensitif dari backup
4. Pecah `MainActivity` (67 KB monolith)
5. Konfigurasi ProGuard/R8 untuk build release
