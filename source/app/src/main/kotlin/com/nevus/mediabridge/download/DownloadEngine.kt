package com.nevus.mediabridge.download

import android.content.Context
import android.media.MediaScannerConnection
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Coroutine-based media downloader.
 *
 * Design:
 *  - **No external HTTP dependency** (no OkHttp, no Retrofit) — `HttpURLConnection` is
 *    sufficient for straight downloads and keeps the APK ~1 MB smaller.
 *  - **Kind-specific target directory** via `context.getExternalFilesDir(kind.standardDir)` —
 *    files land where users expect them, no external-storage permission needed on any SDK.
 *  - **MediaScanner ping** after completion so the Gallery/Music app sees the file
 *    immediately.
 *  - **SHA-256 hash** streamed alongside the download for integrity verification.
 *  - **Cancellable** — [cancel] terminates an in-flight job at the next chunk boundary.
 *  - **Events on a SharedFlow** — one flow for the whole engine, subscribers filter by
 *    [DownloadEvent.txId].
 */
class DownloadEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chunkBytes: Int = 64 * 1024,
) {

    private val _events = MutableSharedFlow<DownloadEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<DownloadEvent> = _events

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Enqueue a download. The returned [Job] can be cancelled directly, or via [cancel]. */
    fun enqueue(request: DownloadRequest): Job {
        // Reject accidental duplicates keyed by txId.
        jobs[request.txId]?.let { existing -> if (existing.isActive) return existing }
        val job = scope.launch(Dispatchers.IO) { run(request) }
        jobs[request.txId] = job
        job.invokeOnCompletion { jobs.remove(request.txId, job) }
        return job
    }

    fun cancel(txId: String) {
        jobs[txId]?.cancel()
    }

    private suspend fun run(request: DownloadRequest) {
        val target: File = try {
            resolveTargetFile(request)
        } catch (t: Throwable) {
            emit(DownloadEvent.Failed(request.txId, "cannot resolve target: ${t.message}", t))
            return
        }

        val conn: HttpURLConnection = try {
            openConnection(request)
        } catch (t: Throwable) {
            emit(DownloadEvent.Failed(request.txId, "connection failed: ${t.message}", t))
            return
        }

        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                emit(DownloadEvent.Failed(request.txId, "HTTP $status ${conn.responseMessage.orEmpty()}"))
                return
            }
            val total = conn.contentLengthLong.takeIf { it > 0L }
            emit(DownloadEvent.Started(request.txId, total))

            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastEmit = 0L

            conn.inputStream.use { input: InputStream ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(chunkBytes)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        // Throttle progress emissions to ~10 Hz to keep the UI thread happy.
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 100 || (total != null && read == total)) {
                            emit(DownloadEvent.Progress(request.txId, read, total))
                            lastEmit = now
                        }
                        withContext(Dispatchers.Default) { /* cooperative cancellation checkpoint */ }
                    }
                    out.fd.sync()
                }
            }

            val sha = digest.digest().toHex()
            triggerMediaScan(target, request.kind, request.fileName ?: target.name)
            emit(DownloadEvent.Completed(request.txId, target.absolutePath, sha))
        } catch (t: Throwable) {
            emit(DownloadEvent.Failed(request.txId, t.message ?: t.javaClass.simpleName, t))
            runCatching { if (target.exists()) target.delete() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun resolveTargetFile(request: DownloadRequest): File {
        val dir = context.getExternalFilesDir(request.kind.standardDir)
            ?: File(context.filesDir, "downloads/${request.kind.subDir}").apply { mkdirs() }
        if (!dir.exists()) dir.mkdirs()
        val name = uniquify(dir, request.suggestedFileName())
        return File(dir, name)
    }

    private fun uniquify(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (File(dir, "$base-$i$ext").exists()) i += 1
        return "$base-$i$ext"
    }

    private fun openConnection(request: DownloadRequest): HttpURLConnection {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        request.referer?.let { conn.setRequestProperty("Referer", it) }
        request.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
        // Explicit Accept — some CDNs 406 without it.
        conn.setRequestProperty("Accept", "*/*")
        conn.doInput = true
        conn.requestMethod = "GET"
        return conn
    }

    private fun triggerMediaScan(file: File, kind: MediaKind, originalName: String) {
        val ext = originalName.substringAfterLast('.', "")
        val mime = kind.mimeTypeFor(ext)
        try {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        } catch (t: Throwable) {
            NevusLog.w(TAG, "MediaScanner failed for ${file.name}", t)
        }
    }

    private suspend fun emit(event: DownloadEvent) {
        _events.emit(event)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append(HEX[(b.toInt() ushr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        return sb.toString()
    }

    private companion object {
        const val TAG = "DownloadEngine"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
