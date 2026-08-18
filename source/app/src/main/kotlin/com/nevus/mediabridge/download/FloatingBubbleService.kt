package com.nevus.mediabridge.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.nevus.mediabridge.MainActivity
import com.nevus.mediabridge.R
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that hosts the floating download bubble.
 *
 * Lifecycle:
 *  - Started via [start] once the user has granted `SYSTEM_ALERT_WINDOW`. If the permission
 *    is missing at start-time the service surfaces a toast and stops itself.
 *  - Runs as a foreground service with a low-importance notification (Android 8+ requirement);
 *    on Android 14 the `foregroundServiceType` is `specialUse` with a documented sub-type.
 *  - Exposes a **static queue of pending detections** via [pendingDetections] so the app-side
 *    WebView can drop URLs into it via [notifyDetected] without holding a reference to the
 *    service instance.
 *
 * Media URL flow:
 *  1. `MainActivity` intercepts a WebView resource load, classifies it with
 *     [MediaUrlDetector], and calls [notifyDetected].
 *  2. The service adds the URL to the pending queue and refreshes the bubble badge.
 *  3. User taps the bubble → the service starts the corresponding download.
 */
class FloatingBubbleService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubble: BubbleController? = null
    private var engine: DownloadEngine? = null
    private var eventJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NevusLog.i(TAG, "Service creating.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        // Guard: must have overlay permission before we can attach the bubble.
        if (!canDrawOverlays()) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        engine = engine ?: DownloadEngine(applicationContext, serviceScope)
        bubble = bubble ?: BubbleController(this, ::onBubbleTap).also { it.attach() }
        refreshBubble()
        subscribeToDownloadEvents()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        NevusLog.i(TAG, "Service destroying.")
        bubble?.detach()
        bubble = null
        eventJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─────────── UI actions ───────────

    private fun onBubbleTap() {
        val next = pendingDetections.value.firstOrNull() ?: run {
            Toast.makeText(this, "Belum ada media terdeteksi", Toast.LENGTH_SHORT).show()
            return
        }
        // Consume & enqueue.
        pendingDetections.value = pendingDetections.value.drop(1)
        refreshBubble()

        val txId = "dl-${nextId.incrementAndGet()}"
        val request = DownloadRequest(
            txId = txId,
            url = next.url,
            kind = next.kind,
            userAgent = "NevusBrowser/3.0",
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
                        NevusLog.i(TAG, "Downloaded ${evt.outputPath} (sha256=${evt.sha256Hex.take(12)}…)")
                        Toast.makeText(
                            this@FloatingBubbleService,
                            getString(R.string.download_done, java.io.File(evt.outputPath).name),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is DownloadEvent.Failed -> {
                        NevusLog.w(TAG, "Download failed ${evt.txId}: ${evt.message}", evt.cause)
                        Toast.makeText(this@FloatingBubbleService, getString(R.string.download_failed, evt.message), Toast.LENGTH_LONG).show()
                    }
                    else -> Unit
                }
            }
        }
    }

    // ─────────── helpers ───────────

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun buildNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "nevus.bubble.channel"
        private const val NOTIF_ID = 43
        private const val TAG = "BubbleService"

        private val nextId = AtomicLong(0L)

        /**
         * The queue of media URLs discovered by the WebView but not yet downloaded/dismissed.
         * Kept as a plain state flow so the WebView (running in another process feature area)
         * can update it without holding a service binder.
         */
        val pendingDetections: MutableStateFlow<List<Detection>> = MutableStateFlow(emptyList())

        /**
         * Push a newly-detected media URL into the bubble. Deduplicates by URL to prevent the
         * badge from ballooning when a video sends many range requests for the same asset.
         */
        fun notifyDetected(context: Context, url: String, kind: MediaKind) {
            val current = pendingDetections.value
            if (current.any { it.url == url }) return
            pendingDetections.value = current + Detection(url, kind, System.currentTimeMillis())
            NevusLog.d("BubbleService", "Detected $kind: $url")
            // Ensure the service is running so the bubble picks up the change.
            ContextCompat_startForeground(context, Intent(context, FloatingBubbleService::class.java))
        }

        /** Start the bubble service, honoring Android 8+ foreground-service rules. */
        fun start(context: Context) {
            ContextCompat_startForeground(context, Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }

        // Small manual polyfill to avoid an extra dependency on androidx.core versions that
        // include ContextCompat.startForegroundService differently.
        private fun ContextCompat_startForeground(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
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
    )
}
