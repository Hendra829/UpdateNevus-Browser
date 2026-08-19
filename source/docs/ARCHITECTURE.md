# Nevus Browser — arsitektur v3.0.0

## Diagram alur startup

```
Process start
    │
    ▼
NevusApplication.onCreate()
    │
    ├── 1. CSPRNGProvider.warmUp()                 (background thread, non-blocking)
    │
    ├── 2. LiveStatisticalValidator + CSPRNGHealthMonitor
    │      CSPRNGProvider.tapForHealthMonitor(monitor)
    │
    ├── 3. StateRecoveryAnalyzer
    │      .register("downloads", …)
    │      .analyzeOnStartup()
    │         → RecoveryReport (in-flight tx, corrupted count)
    │
    ├── 4. Notification channel "nevus.bubble.channel"
    │
    └── 5. ShutdownHook → stateRecovery.markCleanShutdown()

MainActivity.onCreate()
    │
    ├── PerformanceTuner.tune(webView)
    │
    ├── WebViewClient.shouldInterceptRequest
    │      → MediaUrlDetector.classify(url)
    │      → FloatingBubbleService.notifyDetected(url, kind)
    │
    └── User taps "Aktifkan gelembung" → FloatingBubbleService.start()
                                                │
                                                ├── BubbleController.attach()
                                                ├── DownloadEngine created
                                                └── On tap → DownloadEngine.enqueue()
```

## Modul & tanggung jawab

| Package | Responsibility | Depends on |
| --- | --- | --- |
| `util` | Logging, WebView tuning | (stdlib) |
| `crypto` | CSPRNG + Keystore-backed encryption | `util`, `audit` |
| `audit` | Statistical validation of RNG output | (stdlib) |
| `state` | Journaled state + crash-recovery scan | `util`, `kotlinx-serialization` |
| `download` | Media URL classifier, bubble UI, HTTP downloader | `util` |
| `bridge` | (opsional) JavaScript-interface untuk WebView bila diperlukan | `crypto`, `download`, `audit` |

Semua modul bawah tidak import satu sama lain kecuali seperti tabel di atas —
lulus DAG sanity check (no cycles).

## Prinsip

- **Fail fast di boundary** — `require(...)` untuk kontrak publik, exception
  untuk kondisi tak terpulihkan; `IllegalStateException` bukan `Error`.
- **Thread-safe by design** — semua singleton (`CSPRNGProvider`, `object`s)
  menggunakan `AtomicReference`/`AtomicLong` atau `synchronized`.
- **Off-load I/O ke `Dispatchers.IO`** — network + file operations tidak
  boleh menyentuh main thread.
- **Deteksi ≠ eksekusi** — `MediaUrlDetector` hanya mengklasifikasi; unduhan
  ditrigger user (tap bubble), bukan otomatis.

## Batas keamanan

- `SYSTEM_ALERT_WINDOW` — dipandu oleh user melalui Settings, tidak diminta
  runtime.
- `FOREGROUND_SERVICE_SPECIAL_USE` — sub-type `floating_download_bubble_overlay`
  dideklarasi via `<property>` sesuai Android 14 policy.
- File hasil download disimpan di `context.getExternalFilesDir(kind.standardDir)` —
  tidak butuh `MANAGE_EXTERNAL_STORAGE`, tidak menyentuh direktori sistem.
- Tidak ada permission network privileged (mis. `CHANGE_NETWORK_STATE`).
- Tidak ada permission tracking/AD_ID.

## Ekstensi selanjutnya (rekomendasi)

1. Tambahkan `bridge/NevusMediaBridge.kt` — surface `@JavascriptInterface` untuk
   halaman web internal yang memanggil download secara programatik.
2. ~~Ganti `HttpURLConnection` dengan **OkHttp + Cronet** untuk resumable
   download (Range requests)~~ — **sudah diimplementasikan** di atas
   `HttpURLConnection` yang ada: `DownloadEngine` menulis ke sidecar `.part`
   dan mengirim `Range: bytes=<n>-` saat `enqueue()` diulang untuk target
   yang sama (lihat komentar di `DownloadEngine.run()`).
3. Tambahkan **background scheduler** via `WorkManager` untuk retry download
   yang gagal karena hilang koneksi.
4. Tambahkan **UI settings screen** yang membaca `CSPRNGHealthMonitor.snapshot()`
   dan `StateRecoveryAnalyzer` history — user bisa memantau kesehatan sistem
   dan riwayat crash.
