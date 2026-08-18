# Nevus Browser — source

Native Android browser + media bridge, versi **3.0.0**.

Rewrite bersih dari NevusMediaBridge v2.0.0 dengan empat sistem logika baru:

| Sistem | Kelas utama | Berkas |
| --- | --- | --- |
| **CSPRNG Standard Mitigation** | `crypto/CSPRNGProvider` + `CSPRNGHealthMonitor` | 2 file · ~250 baris |
| **State Recovery Analysis** | `state/StateRecoveryAnalyzer` + `RecoverableStore` | 3 file · ~380 baris |
| **Live Statistical Validator** | `audit/LiveStatisticalValidator` + `NistTests` | 2 file · ~330 baris |
| **Floating Download Bubble** (video/audio/gambar/musik saja) | `download/FloatingBubbleService` + `BubbleController` + `MediaUrlDetector` + `DownloadEngine` | 6 file · ~700 baris |

Ditambah tuning kinerja WebView di `util/PerformanceTuner` — hardware layer,
off-screen pre-raster, cache aktif, mixed content diblokir, third-party cookie
dimatikan.

## Struktur

```
source/
├── settings.gradle.kts          Modul :app
├── build.gradle.kts             Plugin resolver
├── gradle/libs.versions.toml    Version catalog
├── gradle.properties            Konfigurasi Gradle
└── app/
    ├── build.gradle.kts         AGP 8.5, Kotlin 2.0, JDK 17, SDK 34
    ├── proguard-rules.pro       Kaidah untuk minifikasi rilis
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/nevus/mediabridge/
        │   ├── NevusApplication.kt
        │   ├── MainActivity.kt
        │   ├── audit/                Live statistical validator + NIST tests
        │   ├── crypto/               CSPRNG provider + health monitor
        │   ├── download/             Bubble mengambang + engine unduh
        │   ├── state/                Recovery analyzer + WAL journal
        │   └── util/                 Logging + performance tuner
        └── res/                      Layout, drawable, string, tema, backup rules
```

## Build

Prasyarat:

- JDK 17
- Android SDK cmdline-tools, `build-tools;34.0.0`, `platforms;android-34`

Langkah:

```bash
# 1. Set path SDK di source/local.properties
echo "sdk.dir=/path/to/Android/Sdk" > source/local.properties

# 2. Build debug APK
cd source
./gradlew :app:assembleDebug
```

Output APK ada di `source/app/build/outputs/apk/debug/`. Dua APK (satu per ABI:
`arm64-v8a` dan `x86_64`) karena ABI split diaktifkan; ambil yang sesuai
device Anda.

Untuk rilis produksi (minified + shrink resources):

```bash
./gradlew :app:assembleRelease
```

## Pasang

```bash
adb install source/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Setelah terpasang:

1. Buka Nevus Browser
2. Ketuk tombol "Berikan izin overlay" → grant permission `SYSTEM_ALERT_WINDOW`
3. Kembali ke aplikasi, ketuk "Aktifkan gelembung unduhan"
4. Jelajahi halaman apa saja — begitu WebView menemukan URL video, audio,
   gambar, atau musik, badge di gelembung bertambah. Ketuk gelembung untuk
   mengunduh media teratas.

## Detail sistem logika baru

### CSPRNG Standard Mitigation

`CSPRNGProvider.warmUp()` mengumpulkan entropi di thread background saat
proses start. `nextBytes()` per-thread (via `ThreadLocal`), tanpa kontensi.
Sample setiap burst (≤256 byte) dialirkan ke `CSPRNGHealthMonitor` yang
memanggil `LiveStatisticalValidator`. Jika validator memicu alarm,
`reseedSelf()` otomatis diinvoke.

### Live Statistical Validator

Ring buffer 8 KiB. Setiap 1 KiB byte baru men-trigger tiga tes NIST SP 800-22:

- **Monobit frequency** (§2.1)
- **Runs** (§2.3)
- **Byte-histogram chi-square** (df=255, Wilson–Hilferty approx.)

Alarm hanya diangkat setelah 3 kegagalan berturut-turut pada tes yang sama
(threshold p<0.01) — mencegah alarm false-positive dari 1% expected rate.

### State Recovery Analysis

Journal append-only per store (`state/<name>.jsonl`), tiap baris fsync.
Startup memindai journal: BEGIN tanpa COMMIT/ABORT ⇒ transaksi in-flight
saat crash. Sentinel file (`state/sentinel.json`) mendeteksi apakah shutdown
sebelumnya bersih. Boot ID dari `/proc/sys/kernel/random/boot_id`
membedakan crash-in-boot vs crash-antara-boot.

### Floating Download Bubble

Foreground service `TYPE_APPLICATION_OVERLAY` (Android 8+). Bubble bisa
di-drag, snap ke edge terdekat pada release, tap untuk mengunduh media
teratas. Deduplikasi URL agar range request tidak membengkakkan badge.
Deteksi berbatas ke 4 `MediaKind`: `VIDEO`, `AUDIO`, `IMAGE`, `MUSIC` — HTML,
JS, CSS, PDF, ZIP, exe *tidak* memicu bubble.

## Rekomendasi audit yang sudah diterapkan

Audit APK v2.0.0 sebelumnya menghasilkan 7 rekomendasi; semuanya sudah
diterapkan di v3.0.0:

- ✅ Sandbox validasi path — semua download disimpan ke `getExternalFilesDir(kind.standardDir)`
- ✅ `mixedContentMode = NEVER_ALLOW`
- ✅ `allowBackup=false` + `dataExtractionRules` + `fullBackupContent` exclude data sensitif
- ✅ MainActivity dipecah (67 KB → 200 baris; fitur ke package terpisah)
- ✅ ABI split (arm64-v8a + x86_64 sebagai APK terpisah, bukan universal)
- ✅ Race guard di key provider (menggunakan `@Synchronized` bila diperlukan)
- ✅ ProGuard/R8 config lengkap untuk build release
