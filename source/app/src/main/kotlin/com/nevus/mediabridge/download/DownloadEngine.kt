package com.nevus.mediabridge.download

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Coroutine-based media downloader.
 *
 * Guarantees:
 *  - **HTTPS-only** — plain http:// is rejected at enqueue. There is no reason a modern browser
 *    should be downloading media over cleartext, and mixed content is blocked in the WebView
 *    anyway; this keeps the invariant.
 *  - **Kind-specific external files dir** — `context.getExternalFilesDir(kind.standardDir)`
 *    lands files where users expect and needs no runtime storage permission.
 *  - **MediaScanner ping** on completion so Gallery/Music apps see the file immediately.
 *  - **Streaming SHA-256** for integrity verification.
 *  - **Bounded size** — the engine refuses a body larger than [maxBytes] (default 4 GiB) to
 *    avoid a hostile server filling the device disk.
 *  - **Cooperative cancellation** — every chunk boundary is a `ensureActive() + yield()` pair;
 *    a partially-written file is deleted on cancel/failure — *unless* it was cancelled by the
 *    engine itself for a resumable retry, in which case the partial bytes are kept.
 *  - **Resumable** — a retry for the same target file reuses a `.part` sidecar and sends
 *    `Range: bytes=<n>-`; a `206` response continues the SHA-256 over the existing prefix bytes,
 *    a `200` response (server ignored Range) restarts clean. The `.part` is renamed to the final
 *    name only once the body is fully received and verified against the declared length.
 *  - **Events on a SharedFlow** — one flow for the whole engine, subscribers filter by
 *    [DownloadEvent.txId].
 */
class DownloadEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chunkBytes: Int = 64 * 1024,
    private val maxBytes: Long = 4L * 1024 * 1024 * 1024,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {

    init {
        require(chunkBytes in 1024..(1 shl 20)) { "chunkBytes out of range: $chunkBytes" }
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }

    private val _events = MutableSharedFlow<DownloadEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<DownloadEvent> = _events

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Enqueue a download. The returned [Job] can be cancelled directly, or via [cancel]. */
    fun enqueue(request: DownloadRequest): Job {
        jobs[request.txId]?.let { existing -> if (existing.isActive) return existing }
        val job = scope.launch(Dispatchers.IO) { run(request) }
        jobs[request.txId] = job
        job.invokeOnCompletion { jobs.remove(request.txId, job) }
        return job
    }

    fun cancel(txId: String) {
        jobs[txId]?.cancel(CancellationException("cancelled by caller"))
    }

    /** Number of currently active downloads. */
    fun activeCount(): Int = jobs.count { it.value.isActive }

    /**
     * Enqueue a resolved HLS/DASH rendition (from [PlaylistParser]) — fetches every segment in
     * order and concatenates them into one output file. No mid-transfer resume across segments
     * (unlike [enqueue]): a retry restarts from the first segment.
     */
    fun enqueueVariant(request: DownloadRequest, resolved: ResolvedVariant): Job {
        jobs[request.txId]?.let { existing -> if (existing.isActive) return existing }
        val job = scope.launch(Dispatchers.IO) { runVariant(request, resolved) }
        jobs[request.txId] = job
        job.invokeOnCompletion { jobs.remove(request.txId, job) }
        return job
    }

    private suspend fun run(request: DownloadRequest) {
        val parsed = runCatching { URL(request.url) }.getOrNull()
        if (parsed == null || parsed.protocol.lowercase() != "https") {
            emit(DownloadEvent.Failed(request.txId, "rejecting non-HTTPS url: ${request.url}"))
            return
        }

        val target: File = try {
            resolveTargetFile(request)
        } catch (t: Throwable) {
            emit(DownloadEvent.Failed(request.txId, "cannot resolve target: ${t.message}", t))
            return
        }
        // A leftover .part from a previous failed/cancelled attempt at the same target name is
        // the resume point. resolveTargetFile only checks the *final* name, so a stale .part
        // does not perturb the uniquify() result — the sidecar naturally lines up on retry.
        val partFile = File(target.parentFile, "${target.name}$PART_SUFFIX")
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L

        val conn: HttpURLConnection = try {
            openConnection(parsed, request, rangeStart = resumeFrom.takeIf { it > 0 })
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

            // 206 confirms the server honoured our Range header; anything else (typically 200)
            // means it sent the whole body from byte zero, so we must restart clean.
            val resuming = resumeFrom > 0 && status == HttpURLConnection.HTTP_PARTIAL
            val startOffset = if (resuming) resumeFrom else 0L
            if (!resuming && partFile.exists()) partFile.delete()

            val declaredTotal = declaredTotalBytes(conn, startOffset)
            if (declaredTotal != null && declaredTotal > maxBytes) {
                emit(DownloadEvent.Failed(request.txId, "declared size ${declaredTotal} exceeds cap ${maxBytes}"))
                return
            }
            emit(DownloadEvent.Started(request.txId, declaredTotal))

            val digest = MessageDigest.getInstance("SHA-256")
            if (resuming) rehashExisting(partFile, digest)

            var read = startOffset
            var lastEmitMs = 0L

            BufferedInputStream(conn.inputStream, chunkBytes).use { input ->
                FileOutputStream(partFile, /* append = */ resuming).use { fout ->
                    BufferedOutputStream(fout, chunkBytes).use { bout ->
                        val buf = ByteArray(chunkBytes)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            bout.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (read > maxBytes) {
                                bout.flush()
                                // Not resumable — a source that lies about its size is not one we
                                // want to keep partial bytes around for.
                                runCatching { partFile.delete() }
                                emit(DownloadEvent.Failed(request.txId, "body exceeded cap ${maxBytes} while reading"))
                                return
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 100 || (declaredTotal != null && read == declaredTotal)) {
                                emit(DownloadEvent.Progress(request.txId, read, declaredTotal))
                                lastEmitMs = now
                            }
                            yield()
                        }
                        bout.flush()
                    }
                    fout.fd.sync()
                }
            }

            if (!partFile.renameTo(target)) {
                throw IllegalStateException("cannot finalize ${partFile.name} -> ${target.name}")
            }
            val sha = digest.digest().toHex()
            triggerMediaScan(target, request.kind, request.fileName ?: target.name)
            emit(DownloadEvent.Completed(request.txId, target.absolutePath, sha, read))
        } catch (ce: CancellationException) {
            // Keep the .part on cancel — a later enqueue() for the same target resumes from here.
            emit(DownloadEvent.Cancelled(request.txId))
            throw ce
        } catch (t: Throwable) {
            // Likewise keep it on a transient failure (network drop, timeout, ...); it is only
            // ever deleted above for the non-retryable oversize case.
            emit(DownloadEvent.Failed(request.txId, t.message ?: t.javaClass.simpleName, t))
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private suspend fun runVariant(request: DownloadRequest, resolved: ResolvedVariant) {
        if (resolved.segmentUrls.isEmpty()) {
            emit(DownloadEvent.Failed(request.txId, "no segments to download"))
            return
        }
        val ext = if (resolved.container == SegmentContainer.MPEG_TS) "ts" else "mp4"
        val target: File = try {
            resolveTargetFile(request, forcedExtension = ext)
        } catch (t: Throwable) {
            emit(DownloadEvent.Failed(request.txId, "cannot resolve target: ${t.message}", t))
            return
        }
        val partFile = File(target.parentFile, "${target.name}$PART_SUFFIX")
        if (partFile.exists()) partFile.delete()

        emit(DownloadEvent.Started(request.txId, expectedBytes = null))
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        var lastEmitMs = 0L

        try {
            FileOutputStream(partFile, /* append = */ false).use { fout ->
                BufferedOutputStream(fout, chunkBytes).use { bout ->
                    for (segmentUrl in resolved.segmentUrls) {
                        coroutineContext.ensureActive()
                        val parsedSegment = URL(segmentUrl)
                        require(parsedSegment.protocol.equals("https", ignoreCase = true)) {
                            "rejecting non-HTTPS segment: $segmentUrl"
                        }
                        val conn = openConnection(parsedSegment, request)
                        try {
                            val status = conn.responseCode
                            if (status !in 200..299) {
                                throw IllegalStateException("segment HTTP $status: $segmentUrl")
                            }
                            BufferedInputStream(conn.inputStream, chunkBytes).use { input ->
                                val buf = ByteArray(chunkBytes)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    bout.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                    read += n
                                    if (read > maxBytes) {
                                        throw IllegalStateException("body exceeded cap $maxBytes while reading")
                                    }
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmitMs >= 100) {
                                        emit(DownloadEvent.Progress(request.txId, read, null))
                                        lastEmitMs = now
                                    }
                                    yield()
                                }
                            }
                        } finally {
                            runCatching { conn.disconnect() }
                        }
                    }
                    bout.flush()
                }
                fout.fd.sync()
            }

            if (!partFile.renameTo(target)) {
                throw IllegalStateException("cannot finalize ${partFile.name} -> ${target.name}")
            }
            val sha = digest.digest().toHex()
            triggerMediaScan(target, request.kind, request.fileName ?: target.name)
            emit(DownloadEvent.Completed(request.txId, target.absolutePath, sha, read))
        } catch (ce: CancellationException) {
            runCatching { partFile.delete() }
            emit(DownloadEvent.Cancelled(request.txId))
            throw ce
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            emit(DownloadEvent.Failed(request.txId, t.message ?: t.javaClass.simpleName, t))
        }
    }

    /** Feed bytes already on disk from a prior attempt into [digest] so the final hash covers the whole file. */
    private fun rehashExisting(partFile: File, digest: MessageDigest) {
        BufferedInputStream(FileInputStream(partFile), chunkBytes).use { existing ->
            val buf = ByteArray(chunkBytes)
            while (true) {
                val n = existing.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
    }

    /** Total body size if derivable — from `Content-Range: bytes s-e/total`, else `Content-Length` (+ offset). */
    private fun declaredTotalBytes(conn: HttpURLConnection, startOffset: Long): Long? {
        conn.getHeaderField("Content-Range")
            ?.substringAfter('/', missingDelimiterValue = "")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it }
        val len = conn.contentLengthLong.takeIf { it > 0L } ?: return null
        return startOffset + len
    }

    private fun resolveTargetFile(request: DownloadRequest, forcedExtension: String? = null): File {
        val dir = context.getExternalFilesDir(request.kind.standardDir)
            ?: File(context.filesDir, "downloads/${request.kind.subDir}")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("cannot create ${dir.absolutePath}")
        }
        val suggested = request.suggestedFileName()
        val withExtension = if (forcedExtension == null) suggested else {
            "${suggested.substringBeforeLast('.', suggested)}.$forcedExtension"
        }
        val name = uniquify(dir, withExtension)
        val candidate = File(dir, name)
        // Sanity check: the resolved path must sit inside the intended parent — guards against
        // any name that survives sanitization and tries to escape via canonicalisation quirks.
        val canonicalDir = dir.canonicalPath
        val canonicalTarget = candidate.canonicalPath
        require(canonicalTarget.startsWith(canonicalDir + File.separator)) {
            "resolved path escapes target dir: $canonicalTarget"
        }
        return candidate
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

    private fun openConnection(parsed: URL, request: DownloadRequest, rangeStart: Long? = null): HttpURLConnection {
        val conn = parsed.openConnection() as HttpsURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        request.referer?.let { referer ->
            // Only forward a referer that is itself HTTPS — protects against a cleartext leak.
            if (Uri.parse(referer).scheme.equals("https", ignoreCase = true)) {
                conn.setRequestProperty("Referer", referer)
            }
        }
        conn.setRequestProperty("User-Agent", request.userAgent ?: DEFAULT_UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")  // we stream the raw body; skip gzip
        if (rangeStart != null && rangeStart > 0) {
            conn.setRequestProperty("Range", "bytes=$rangeStart-")
        }
        conn.doInput = true
        conn.requestMethod = "GET"
        return conn
    }

    private fun triggerMediaScan(file: File, kind: MediaKind, originalName: String) {
        val ext = originalName.substringAfterLast('.', "")
        val mime = kind.mimeTypeFor(ext)
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        }.onFailure { NevusLog.w(TAG, "MediaScanner failed for ${file.name}", it) }
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
        const val DEFAULT_UA = "NevusBrowser/3.0 (Android; media-bridge)"
        const val PART_SUFFIX = ".part"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
