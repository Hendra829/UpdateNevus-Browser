package com.nevus.mediabridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nevus.mediabridge.audit.LiveStatisticalValidator
import com.nevus.mediabridge.crypto.CSPRNGHealthMonitor
import com.nevus.mediabridge.crypto.CSPRNGProvider
import com.nevus.mediabridge.download.FloatingBubbleService
import com.nevus.mediabridge.state.StateRecoveryAnalyzer
import com.nevus.mediabridge.util.NevusLog
import kotlinx.serialization.builtins.serializer

/**
 * Process entry point.
 *
 * Order of subsystem bootstrap matters — do not reorder without re-reading the notes.
 *
 *   1. CSPRNG warm-up (background thread; must precede any subsystem that draws random bytes
 *      so the UI never blocks on entropy).
 *   2. Live validator + health monitor attached BEFORE the first `nextBytes()` so the first
 *      burst is also observed.
 *   3. State recovery pass — nothing else may read/write state before it completes.
 *   4. Notification channel — registered before any foreground service can post.
 *   5. Clean-shutdown mark via [ProcessLifecycleOwner]. JVM `addShutdownHook` is unreliable on
 *      Android (the framework SIGKILLs the process) so we instead flip the sentinel when the
 *      whole app enters the STOPPED lifecycle state.
 */
class NevusApplication : Application() {

    lateinit var stateRecovery: StateRecoveryAnalyzer
        private set
    lateinit var statisticalValidator: LiveStatisticalValidator
        private set
    lateinit var csprngHealthMonitor: CSPRNGHealthMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        NevusLog.i(TAG, "Nevus Browser v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) starting.")

        CSPRNGProvider.warmUp()

        statisticalValidator = LiveStatisticalValidator()
        csprngHealthMonitor = CSPRNGHealthMonitor(statisticalValidator)
        CSPRNGProvider.tapForHealthMonitor(csprngHealthMonitor)

        stateRecovery = StateRecoveryAnalyzer(this)
            .register("downloads", String.serializer())
        val report = stateRecovery.analyzeOnStartup()
        NevusLog.i(TAG, "State recovery: ${report.summary()}")
        if (report.hadCrash) {
            NevusLog.w(TAG, "Detected previous unclean shutdown; in-flight=${report.inFlight.size}")
        }
        // Trim the journal to what analyzeOnStartup() just resolved, per RecoverableStore's own
        // documented contract — otherwise downloads.jsonl grows without bound across launches.
        runCatching { stateRecovery.store<String>("downloads").checkpoint(report.highestIndex) }
            .onFailure { NevusLog.w(TAG, "Post-startup checkpoint failed", it) }

        registerBubbleNotificationChannel()
        csprngHealthMonitor.schedulePeriodicReseed(PERIODIC_RESEED_MS)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // App-wide STOPPED — every activity has left the foreground. On graceful teardown
                // (user swipes away, system moves us out) this fires; on a crash, it does not,
                // and the sentinel remains on disk for the next launch to notice.
                stateRecovery.markCleanShutdown()
            }
        })
    }

    private fun registerBubbleNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(FloatingBubbleService.CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            FloatingBubbleService.CHANNEL_ID,
            getString(R.string.bubble_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.bubble_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    private companion object {
        const val TAG = "Application"

        /** How often to self-reseed the CSPRNG independent of any statistical alarm. 30 min. */
        const val PERIODIC_RESEED_MS = 30L * 60 * 1000
    }
}
