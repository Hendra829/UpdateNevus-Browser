# UpdateNevus-Browser

Aplikasi Android native **Nevus Browser v3.0.0** — repository sumber + biner
siap-pasang.

## Isi repo

```
UpdateNevus-Browser/
├── README.md                (dokumen ini)
├── dist/                    APK debug siap pasang (per ABI)
│   ├── NevusBrowser-v3.0.0-debug-arm64-v8a.apk    5.6 MB — kebanyakan HP 2018+
│   └── NevusBrowser-v3.0.0-debug-x86_64.apk       5.6 MB — emulator / tablet x86
└── source/                  Source Kotlin + Gradle (lihat source/README.md)
    ├── app/                 Modul aplikasi
    ├── build.gradle.kts
    ├── docs/                Dokumen arsitektur
    └── README.md            Panduan build detail
```

## Fitur v3.0.0

| Fitur | Kelas | Berkas |
| --- | --- | --- |
| **CSPRNG Standard Mitigation** | `crypto/CSPRNGProvider` + `CSPRNGHealthMonitor` | 2 file |
| **State Recovery Analysis** | `state/StateRecoveryAnalyzer` + `RecoverableStore` | 3 file |
| **Live Statistical Validator** | `audit/LiveStatisticalValidator` + `NistTests` (NIST SP 800-22) | 2 file |
| **Floating Download Bubble** (video / audio / gambar / musik saja) | `download/FloatingBubbleService` + `BubbleController` + `MediaUrlDetector` + `DownloadEngine` | 6 file |
| **Performance tuning WebView** | `util/PerformanceTuner` | 1 file |

Total kode aplikasi: **~2&thinsp;100 LOC Kotlin**, **19 file**.

## Pasang di device Android

Ada tiga jalur. Pilih yang paling mudah untuk Anda.

### A. Via Termux (paling cocok tanpa PC)

Jalankan **satu baris** di Termux:

```bash
curl -sSL https://raw.githubusercontent.com/Hendra829/UpdateNevus-Browser/main/dist/install.sh | bash
```

Skrip akan:

1. Cek/pasang `curl` dan `termux-tools`
2. Minta izin storage (kalau belum diberikan) — setujui pop-up
3. Auto-deteksi ABI device (arm64-v8a / x86_64)
4. Download APK ke `/sdcard/Download/`
5. Buka installer sistem — Anda tinggal ketuk "Install"

**Kalau repo private**, tambahkan token dulu:

```bash
export NEVUS_GH_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxxxxxx"
curl -sSL -H "Authorization: Bearer $NEVUS_GH_TOKEN" \
     https://raw.githubusercontent.com/Hendra829/UpdateNevus-Browser/main/dist/install.sh | bash
```

Cara buat token: https://github.com/settings/tokens → "Generate new token (classic)"
→ scope hanya centang `repo` → copy hasilnya.

> Kalau tidak mau pakai token, buat repo ini **public** di GitHub Settings —
> maka one-liner tanpa token langsung jalan.

### B. Via ADB (dengan PC)

```bash
adb install dist/NevusBrowser-v3.0.0-debug-arm64-v8a.apk
```

### C. Manual copy

Salin file `.apk` sesuai ABI ke penyimpanan HP via file manager, ketuk untuk
pasang. Aktifkan "Install unknown apps" untuk file manager Anda di Settings
kalau muncul dialog blocking.

## Pemakaian

1. Buka **Nevus Browser** (icon di launcher).
2. Ketuk **"Berikan izin overlay"** → di Settings, aktifkan `Allow display over other apps`.
3. Kembali ke aplikasi, ketuk **"Aktifkan gelembung unduhan"**.
4. Jelajahi halaman apa saja di URL bar.
5. Begitu WebView menemukan **URL video, audio, gambar, atau musik**, gelembung
   mengambang menampilkan badge dengan jumlah antrean. Ketuk gelembung untuk
   mengunduh media teratas ke `Android/data/com.nevus.mediabridge.debug/files/`.

## Perbandingan versi

| Aspek | v2.0.0 (lama) | v3.0.0 (sekarang) |
| --- | --- | --- |
| Ukuran APK | 76 MB (universal) | **5.6 MB per ABI** |
| Target SDK | 34 | 34 |
| ABI | arm64 + x86_64 dalam satu APK | ABI split, satu APK per arsitektur |
| Kode | Monolith `MainActivity` 67 KB | Modular — MainActivity ~200 baris |
| Crypto | AES-256-GCM + PBKDF2 | ditambah **CSPRNG standard** + live health monitor |
| State | Ephemeral | **State Recovery Analysis** dengan WAL journal |
| Audit | TamperEvidentLog | ditambah **Live Statistical Validator** (NIST 800-22) |
| Download | Programatik via `@JavascriptInterface` | **Floating Bubble** — user-facing, media-only |
| `allowBackup` | true | **false** (data sensitif excluded) |
| `mixedContentMode` | COMPATIBILITY | **NEVER_ALLOW** |
| ProGuard/R8 | tidak dikonfigurasi | konfigurasi lengkap untuk release build |

## Build sendiri

Lihat [`source/README.md`](source/README.md). Ringkas:

```bash
cd source
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
gradle :app:assembleDebug
```

Perlu JDK 17 + Android SDK build-tools 34.

## Kebijakan pembaharuan

**Overwrite policy**: setiap versi baru menimpa berkas di `dist/` dan `source/`
di lokasi yang sama. Tidak ada file versi lama yang bertahan — commit git
menyimpan riwayat, tapi working tree selalu mencerminkan versi terbaru saja.
