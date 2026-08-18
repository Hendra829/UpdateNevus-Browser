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

```bash
# 1. Aktifkan "Install unknown apps" untuk file manager Anda di Settings.
# 2. Salin file APK sesuai arsitektur device:
adb install dist/NevusBrowser-v3.0.0-debug-arm64-v8a.apk
```

Atau salin APK ke device via file manager, ketuk untuk pasang.

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
