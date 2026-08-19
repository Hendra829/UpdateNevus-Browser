package com.nevus.mediabridge.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * A real connection speed test — latency (round-trip, best of 3 HEAD requests) and download/
 * upload throughput, measured against Cloudflare's public speed-test endpoints
 * (`speed.cloudflare.com`, the same service behind Cloudflare's own speed-test page and used by
 * several open-source speed-test tools). No servers of our own are involved; each measurement is
 * a real, timed transfer, not an estimate.
 */
object NetworkSpeedTest {

    suspend fun run(): SpeedTestResult = withContext(Dispatchers.IO) {
        SpeedTestResult(
            latencyMs = measureLatencyMs(),
            downloadMbps = measureDownloadMbps(),
            uploadMbps = measureUploadMbps(),
        )
    }

    private fun measureLatencyMs(): Long? {
        val samples = mutableListOf<Long>()
        repeat(3) {
            val conn = runCatching {
                (URL(PING_URL).openConnection() as HttpsURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "HEAD"
                }
            }.getOrNull() ?: return@repeat
            val t0 = System.nanoTime()
            runCatching { conn.responseCode }
                .onSuccess { samples.add((System.nanoTime() - t0) / 1_000_000) }
            conn.disconnect()
        }
        return samples.minOrNull()
    }

    private fun measureDownloadMbps(): Double? {
        val conn = runCatching {
            (URL(DOWNLOAD_URL).openConnection() as HttpsURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TRANSFER_TIMEOUT_MS
            }
        }.getOrNull() ?: return null
        return try {
            val t0 = System.nanoTime()
            var total = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            mbps(total, System.nanoTime() - t0)
        } catch (t: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun measureUploadMbps(): Double? {
        val payload = ByteArray(UPLOAD_BYTES)
        val conn = runCatching {
            (URL(UPLOAD_URL).openConnection() as HttpsURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TRANSFER_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
        }.getOrNull() ?: return null
        return try {
            val t0 = System.nanoTime()
            conn.outputStream.use { it.write(payload) }
            conn.responseCode // force the request to actually complete before we stop the clock
            mbps(payload.size.toLong(), System.nanoTime() - t0)
        } catch (t: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun mbps(bytes: Long, elapsedNanos: Long): Double? {
        val seconds = elapsedNanos / 1_000_000_000.0
        if (seconds <= 0.0 || bytes <= 0L) return null
        return (bytes * 8.0 / 1_000_000.0) / seconds
    }

    private const val TIMEOUT_MS = 5_000
    private const val TRANSFER_TIMEOUT_MS = 20_000
    private const val UPLOAD_BYTES = 2 * 1024 * 1024
    private const val PING_URL = "https://www.gstatic.com/generate_204"
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=10000000"
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
}

data class SpeedTestResult(
    val latencyMs: Long?,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
)
