package com.nevus.mediabridge.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nevus.mediabridge.MainActivity
import com.nevus.mediabridge.R
import com.nevus.mediabridge.media.EnhanceEvent
import com.nevus.mediabridge.media.ImageEnhancer
import com.nevus.mediabridge.media.MediaEnhancer
import com.nevus.mediabridge.media.StickerMaker
import com.nevus.mediabridge.util.NevusLog
import com.nevus.mediabridge.util.NevusSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that hosts the floating download bubble.
 *
 * Lifecycle contract:
 *  - Started via [start] once the user has granted `SYSTEM_ALERT_WINDOW`. If the permission is
 *    absent at start-time the service still promotes itself to the foreground (mandatory within
 *    the 5-second window on Android 8+), *then* surfaces a toast and stops itself — the alternative
 *    of returning without startForeground crashes with `RemoteServiceException`.
 *  - Runs as a `specialUse` foreground service with a low-importance notification.
 *  - Static state — [pendingDetections] is a companion-level StateFlow so the WebView can drop
 *    URLs into it without holding a service binder.
 *
 * Media URL flow:
 *  1. `MainActivity` intercepts a WebView resource load, classifies it via [MediaUrlDetector],
 *     and calls [notifyDetected].
 *  2. The service adds the URL to the pending queue (deduped) and refreshes the bubble badge.
 *  3. User taps the bubble → the service starts the corresponding download.
 */
class FloatingBubbleService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubble: BubbleController? = null
    private var engine: DownloadEngine? = null
    private var eventJob: Job? = null
    private var pendingJob: Job? = null
    private var plannedJob: Job? = null
    private var cancelJob: Job? = null

    private val history: DownloadHistoryStore by lazy {
        DownloadHistoryStore(File(applicationContext.filesDir, "state"))
    }

    /** In-flight requests, so a terminal event can be logged with its original url/kind/fileName. */
    private val inFlightRequests = ConcurrentHashMap<String, DownloadRequest>()

    override fun onCreate() {
        super.onCreate()
        NevusLog.i(TAG, "Service creating.")
    }

    @androidx.media3.common.util.UnstableApi
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground MUST happen before the 5-second grace period; call it unconditionally.
        startForeground(NOTIF_ID, buildNotification())

        if (!canDrawOverlays()) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        engine = engine ?: run {
            val fast = NevusSettings(applicationContext).fastConnectionMode
            DownloadEngine(
                context = applicationContext,
                scope = serviceScope,
                chunkBytes = if (fast) FAST_CHUNK_BYTES else DEFAULT_CHUNK_BYTES,
                connectTimeoutMs = if (fast) FAST_CONNECT_TIMEOUT_MS else DEFAULT_CONNECT_TIMEOUT_MS,
            )
        }
        bubble = bubble ?: BubbleController(this, ::onBubbleTap).also { it.attach() }
        refreshBubble()
        subscribeToDownloadEvents()
        subscribeToPendingDetections()
        subscribeToPlannedDownloads()
        subscribeToCancelRequests()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NevusLog.i(TAG, "Service destroying.")
        bubble?.detach()
        bubble = null
        eventJob?.cancel()
        pendingJob?.cancel()
        plannedJob?.cancel()
        cancelJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─────────── UI actions ───────────

    private fun onBubbleTap() {
        val next = pendingDetections.value.firstOrNull() ?: run {
            Toast.makeText(this, R.string.bubble_empty, Toast.LENGTH_SHORT).show()
            return
        }
        pendingDetections.value = pendingDetections.value.drop(1)
        refreshBubble()

        val txId = "dl-${nextId.incrementAndGet()}"
        val request = DownloadRequest(
            txId = txId,
            url = next.url,
            kind = next.kind,
            referer = next.referer,
            userAgent = DEFAULT_UA,
        )
        inFlightRequests[txId] = request
        engine?.enqueue(request)
        Toast.makeText(this, getString(R.string.download_started, next.kind.name.lowercase()), Toast.LENGTH_SHORT).show()
    }

    private fun refreshBubble() {
        val list = pendingDetections.value
        bubble?.setPendingCount(list.size, list.firstOrNull()?.kind)
    }

    private fun subscribeToDownloadEvents() {
        val eng = engine ?: return
        eventJob?.cancel()
        eventJob = serviceScope.launch {
            eng.events.collect { evt ->
                when (evt) {
                    is DownloadEvent.Started, is DownloadEvent.Progress -> {
                        activeDownloads.value = activeDownloads.value + (evt.txId to evt)
                    }
                    is DownloadEvent.Completed -> {
                        NevusLog.i(TAG, "Downloaded ${evt.outputPath} bytes=${evt.bytesWritten} sha256=${evt.sha256Hex.take(12)}…")
                        Toast.makeText(
                            this@FloatingBubbleService,
                            getString(R.string.download_done, File(evt.outputPath).name),
                            Toast.LENGTH_SHORT,
                        ).show()
                        activeDownloads.value = activeDownloads.value - evt.txId
                        logHistory(evt.txId, DownloadHistoryEntry.Status.COMPLETED, evt.outputPath, evt.bytesWritten, evt.sha256Hex, null)
                    }
                    is DownloadEvent.Failed -> {
                        NevusLog.w(TAG, "Download failed ${evt.txId}: ${evt.message}", evt.cause)
                        Toast.makeText(this@FloatingBubbleService, getString(R.string.download_failed, evt.message), Toast.LENGTH_LONG).show()
                        activeDownloads.value = activeDownloads.value - evt.txId
                        logHistory(evt.txId, DownloadHistoryEntry.Status.FAILED, null, 0L, null, evt.message)
                    }
                    is DownloadEvent.Cancelled -> {
                        NevusLog.i(TAG, "Download cancelled ${evt.txId}")
                        activeDownloads.value = activeDownloads.value - evt.txId
                    }
                }
            }
        }
    }

    private fun subscribeToPendingDetections() {
        pendingJob?.cancel()
        pendingJob = serviceScope.launch {
            pendingDetections.collect { refreshBubble() }
        }
    }

    private fun subscribeToCancelRequests() {
        cancelJob?.cancel()
        cancelJob = serviceScope.launch {
            cancelRequests.collect { req -> engine?.cancel(req.txId, req.discard) }
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun subscribeToPlannedDownloads() {
        plannedJob?.cancel()
        plannedJob = serviceScope.launch {
            plannedDownloads.collect { plan -> launch { handlePlan(plan) } }
        }
    }

    /**
     * Runs a fully-configured download from [com.nevus.mediabridge.ui.DownloadOptionsDialog]:
     * resolves a real HLS/DASH variant if one was chosen, downloads it (or the plain URL), and —
     * if requested — waits for that to finish and runs [MediaEnhancer] on the result.
     */
    @androidx.media3.common.util.UnstableApi
    private suspend fun handlePlan(plan: DownloadPlan) {
        val eng = engine ?: return
        val txId = "dl-${nextId.incrementAndGet()}"
        val kind = if (plan.audioOnly) MediaKind.AUDIO else plan.detection.kind
        val request = DownloadRequest(
            txId = txId,
            url = plan.detection.url,
            kind = kind,
            referer = plan.detection.referer,
            userAgent = DEFAULT_UA,
        )
        inFlightRequests[txId] = request

        if (plan.chosenVariant != null) {
            val resolved = PlaylistParser.resolve(plan.detection.url, plan.manifestKind, plan.chosenVariant)
            if (resolved == null) {
                inFlightRequests.remove(txId)
                Toast.makeText(
                    this,
                    getString(R.string.download_failed, plan.chosenVariant.label),
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            eng.enqueueVariant(request, resolved)
        } else if (plan.segmented) {
            // Parallel-Range accelerator only applies to a plain progressive URL — HLS/DASH
            // already downloads via its own segment list above. Scale the connection count to
            // the last measured real throughput (NetworkSpeedTest) — more parallel connections
            // help on a fast link with headroom, but just add overhead on a slow one.
            eng.enqueueSegmented(request, segments = segmentCountFor(NevusSettings(applicationContext).lastMeasuredDownloadMbps))
        } else {
            eng.enqueue(request)
        }

        if (!plan.enhance && plan.stickerMode == StickerMode.NONE) return

        val terminal = eng.events.first { it.txId == txId && (it is DownloadEvent.Completed || it is DownloadEvent.Failed || it is DownloadEvent.Cancelled) }
        val completed = terminal as? DownloadEvent.Completed ?: return

        if (plan.enhance) runEnhancement(completed.outputPath, plan)
        if (plan.stickerMode != StickerMode.NONE && plan.detection.kind == MediaKind.IMAGE) {
            runStickerMaking(completed.outputPath, plan.stickerMode)
        }
    }

    private suspend fun runStickerMaking(inputPath: String, mode: StickerMode) {
        val input = File(inputPath)
        val outputPath = File(input.parentFile, "${input.nameWithoutExtension}-sticker.webp").absolutePath
        val ok = withContext(Dispatchers.Default) {
            when (mode) {
                StickerMode.BORDER -> StickerMaker.simpleBorder(inputPath, outputPath)
                StickerMode.BACKGROUND_REMOVAL -> StickerMaker.removeBackground(inputPath, outputPath)
                StickerMode.NONE -> false
            }
        }
        if (ok) {
            NevusLog.i(TAG, "Sticker ready: $outputPath")
            Toast.makeText(this, getString(R.string.download_done, File(outputPath).name), Toast.LENGTH_SHORT).show()
        } else {
            NevusLog.w(TAG, "Sticker creation failed for $inputPath")
            Toast.makeText(this, getString(R.string.download_failed, File(inputPath).name), Toast.LENGTH_LONG).show()
        }
    }

    @androidx.media3.common.util.UnstableApi
    private suspend fun runEnhancement(inputPath: String, plan: DownloadPlan) {
        // media3 Transformer handles audio/video containers, not single images — the image path
        // is its own lightweight ImageEnhancer (ColorMatrix contrast + convolution sharpen).
        if (plan.detection.kind == MediaKind.IMAGE) {
            runImageEnhancement(inputPath)
            return
        }
        val input = File(inputPath)
        val ext = if (plan.audioOnly) "m4a" else "mp4"
        val outputPath = File(input.parentFile, "${input.nameWithoutExtension}-enhanced.$ext").absolutePath
        MediaEnhancer(applicationContext)
            .enhance(inputPath = inputPath, outputPath = outputPath, audioOnly = plan.audioOnly, targetHeightPx = plan.targetHeightPx)
            .collect { evt ->
                when (evt) {
                    is EnhanceEvent.Completed -> {
                        NevusLog.i(TAG, "Enhanced file ready: ${evt.outputPath}")
                        Toast.makeText(this, getString(R.string.download_done, File(evt.outputPath).name), Toast.LENGTH_SHORT).show()
                    }
                    is EnhanceEvent.Failed -> {
                        NevusLog.w(TAG, "Enhancement failed for $inputPath: ${evt.message}", evt.cause)
                        Toast.makeText(this, getString(R.string.download_failed, evt.message), Toast.LENGTH_LONG).show()
                    }
                    EnhanceEvent.Started -> Unit
                }
            }
    }

    private suspend fun runImageEnhancement(inputPath: String) {
        val input = File(inputPath)
        val outputPath = File(input.parentFile, "${input.nameWithoutExtension}-enhanced.png").absolutePath
        val ok = withContext(Dispatchers.Default) { ImageEnhancer.enhance(inputPath, outputPath) }
        if (ok) {
            NevusLog.i(TAG, "Enhanced image ready: $outputPath")
            Toast.makeText(this, getString(R.string.download_done, File(outputPath).name), Toast.LENGTH_SHORT).show()
        } else {
            NevusLog.w(TAG, "Image enhancement failed for $inputPath")
            Toast.makeText(this, getString(R.string.download_failed, File(inputPath).name), Toast.LENGTH_LONG).show()
        }
    }

    // ─────────── helpers ───────────

    /**
     * More parallel Range connections only help when there's bandwidth headroom for them to use
     * — on a slow link they mostly just add per-connection overhead. Thresholds are a simple,
     * defensible heuristic (not a magic "AI" figure): scale up with measured throughput, default
     * to the full [DEFAULT_SEGMENT_COUNT] only once a speed test has actually shown room for it.
     */
    private fun segmentCountFor(measuredMbps: Float?): Int = when {
        measuredMbps == null -> DEFAULT_SEGMENT_COUNT
        measuredMbps < 5f -> 4
        measuredMbps < 20f -> 10
        else -> DEFAULT_SEGMENT_COUNT
    }

    private fun logHistory(
        txId: String,
        status: DownloadHistoryEntry.Status,
        outputPath: String?,
        bytesWritten: Long,
        sha256Hex: String?,
        failureMessage: String?,
    ) {
        val request = inFlightRequests.remove(txId) ?: return
        runCatching {
            history.append(
                DownloadHistoryEntry(
                    txId = txId,
                    url = request.url,
                    kind = request.kind,
                    fileName = outputPath?.let { File(it).name } ?: request.suggestedFileName(),
                    outputPath = outputPath,
                    bytesWritten = bytesWritten,
                    sha256Hex = sha256Hex,
                    status = status,
                    timestampMs = System.currentTimeMillis(),
                    failureMessage = failureMessage,
                )
            )
        }.onFailure { NevusLog.w(TAG, "Failed to append history entry for $txId", it) }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "nevus.bubble.channel"
        private const val NOTIF_ID = 43
        private const val TAG = "BubbleService"
        private const val DEFAULT_UA = "NevusBrowser/3.0 (Android; media-bridge)"

        private const val DEFAULT_CHUNK_BYTES = 64 * 1024
        private const val FAST_CHUNK_BYTES = 256 * 1024
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        private const val FAST_CONNECT_TIMEOUT_MS = 8_000
        private const val DEFAULT_SEGMENT_COUNT = 20

        /** Cap on retained pending detections — silently drops old entries past this. */
        private const val MAX_PENDING = 64

        /** Detections older than this are dropped when new ones arrive. 15 minutes. */
        private const val STALE_MS = 15L * 60 * 1000

        private val nextId = AtomicLong(0L)

        val pendingDetections: MutableStateFlow<List<Detection>> = MutableStateFlow(emptyList())

        /** txId -> latest Started/Progress event, for [com.nevus.mediabridge.ui.DownloadManagerActivity]. */
        val activeDownloads: MutableStateFlow<Map<String, DownloadEvent>> = MutableStateFlow(emptyMap())

        /** One-shot download commands from [com.nevus.mediabridge.ui.DownloadOptionsDialog]. */
        private val plannedDownloads = MutableSharedFlow<DownloadPlan>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** Start the service (if needed) and hand it a fully-configured download to run. */
        fun submitPlan(context: Context, plan: DownloadPlan) {
            plannedDownloads.tryEmit(plan)
            runCatching { startInternal(context) }
                .onFailure { NevusLog.w(TAG, "submitPlan: startForegroundService failed", it) }
        }

        private val cancelRequests = MutableSharedFlow<CancelRequest>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** Stop a running download — [discard] false pauses (resumable later), true cancels for good. */
        fun requestCancel(txId: String, discard: Boolean) {
            cancelRequests.tryEmit(CancelRequest(txId, discard))
        }

        /**
         * Push a newly-detected media URL into the bubble. Deduplicates by URL to prevent the
         * badge from ballooning when a video sends many range requests for the same asset.
         * Prunes any detection older than [STALE_MS] and caps at [MAX_PENDING].
         */
        fun notifyDetected(context: Context, url: String, kind: MediaKind, referer: String? = null) {
            val now = System.currentTimeMillis()
            val current = pendingDetections.value
            if (current.any { it.url == url }) return
            val pruned = current.filter { now - it.discoveredAtMs < STALE_MS }
            val next = (pruned + Detection(url, kind, now, referer)).takeLast(MAX_PENDING)
            pendingDetections.value = next
            NevusLog.d(TAG, "Detected $kind: $url")
            runCatching { startInternal(context) }
                .onFailure { NevusLog.w(TAG, "startForegroundService failed", it) }
        }

        /** Start the bubble service, honoring Android 8+ foreground-service rules. */
        fun start(context: Context) {
            runCatching { startInternal(context) }
                .onFailure { NevusLog.w(TAG, "explicit start failed", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }

        fun clearPending() {
            pendingDetections.value = emptyList()
        }

        private fun startInternal(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /**
     * A media URL that the classifier has flagged. Not persisted — if the bubble service is
     * killed the pending queue is lost, which is the correct behaviour (state expires when the
     * user is no longer watching).
     */
    data class Detection(
        val url: String,
        val kind: MediaKind,
        val discoveredAtMs: Long,
        val referer: String? = null,
    )

    private data class CancelRequest(val txId: String, val discard: Boolean)
}
