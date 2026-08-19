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
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
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

    override fun onCreate() {
        super.onCreate()
        NevusLog.i(TAG, "Service creating.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground MUST happen before the 5-second grace period; call it unconditionally.
        startForeground(NOTIF_ID, buildNotification())

        if (!canDrawOverlays()) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        engine = engine ?: DownloadEngine(applicationContext, serviceScope)
        bubble = bubble ?: BubbleController(this, ::onBubbleTap).also { it.attach() }
        refreshBubble()
        subscribeToDownloadEvents()
        subscribeToPendingDetections()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NevusLog.i(TAG, "Service destroying.")
        bubble?.detach()
        bubble = null
        eventJob?.cancel()
        pendingJob?.cancel()
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
                    is DownloadEvent.Completed -> {
                        NevusLog.i(TAG, "Downloaded ${evt.outputPath} bytes=${evt.bytesWritten} sha256=${evt.sha256Hex.take(12)}…")
                        Toast.makeText(
                            this@FloatingBubbleService,
                            getString(R.string.download_done, File(evt.outputPath).name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is DownloadEvent.Failed -> {
                        NevusLog.w(TAG, "Download failed ${evt.txId}: ${evt.message}", evt.cause)
                        Toast.makeText(this@FloatingBubbleService, getString(R.string.download_failed, evt.message), Toast.LENGTH_LONG).show()
                    }
                    is DownloadEvent.Cancelled -> {
                        NevusLog.i(TAG, "Download cancelled ${evt.txId}")
                    }
                    else -> Unit
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

    // ─────────── helpers ───────────

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

        /** Cap on retained pending detections — silently drops old entries past this. */
        private const val MAX_PENDING = 64

        /** Detections older than this are dropped when new ones arrive. 15 minutes. */
        private const val STALE_MS = 15L * 60 * 1000

        private val nextId = AtomicLong(0L)

        val pendingDetections: MutableStateFlow<List<Detection>> = MutableStateFlow(emptyList())

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
}
