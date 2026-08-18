package com.nevus.mediabridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.nevus.mediabridge.audit.LiveStatisticalValidator
import com.nevus.mediabridge.crypto.CSPRNGHealthMonitor
import com.nevus.mediabridge.crypto.CSPRNGProvider
import com.nevus.mediabridge.download.FloatingBubbleService
import com.nevus.mediabridge.state.StateRecoveryAnalyzer
import com.nevus.mediabridge.util.NevusLog
import kotlinx.serialization.builtins.serializer

/**
 * Process entry point. Bootstraps the four cross-cutting subsystems in the exact order
 * required for correctness:
 *
 *   1. **CSPRNG warm-up**    — background entropy gather; must start before any subsystem
 *                              draws random bytes so the UI never blocks on entropy.
 *   2. **Live validator + health monitor** — attach BEFORE the first `nextBytes()` call so
 *                              the first burst is also observed.
 *   3. **State recovery**    — analyses the previous run's journal; the app must not read or
 *                              write state before this pass completes.
 *   4. **Notification channel** — registered before any foreground service can post.
 *   5. **Clean-shutdown mark** on `Lifecycle.State.DESTROYED` — flips the sentinel so the
 *                              next launch does not falsely report a crash.
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
        NevusLog.i(TAG, "Nevus Browser v${BuildConfig.VERSION_NAME} starting.")

        // 1. CSPRNG warm-up (background, ~ms on modern devices).
        CSPRNGProvider.warmUp()

        // 2. Statistical validator + health monitor.
        statisticalValidator = LiveStatisticalValidator()
        csprngHealthMonitor = CSPRNGHealthMonitor(statisticalValidator)
        CSPRNGProvider.tapForHealthMonitor(csprngHealthMonitor)

        // 3. State recovery pass.
        stateRecovery = StateRecoveryAnalyzer(this)
            .register("downloads", String.serializer())
        val report = stateRecovery.analyzeOnStartup()
        NevusLog.i(TAG, "State recovery: ${report.summary()}")
        if (report.hadCrash) {
            NevusLog.w(TAG, "Detected previous unclean shutdown; in-flight=${report.inFlight.size}")
            // Application-level handling would go here (e.g., replay downloads).
        }

        // 4. Notification channel for the download bubble.
        registerBubbleNotificationChannel()

        // 5. Clean-shutdown mark. JVM shutdown hooks fire on graceful process teardown; a
        //    crash bypasses them, and the sentinel file remains — exactly what state recovery
        //    uses on the next launch to detect the crash.
        Runtime.getRuntime().addShutdownHook(Thread({ stateRecovery.markCleanShutdown() }, "nevus-shutdown"))
    }

    private fun registerBubbleNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            FloatingBubbleService.CHANNEL_ID,
            getString(R.string.bubble_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.bubble_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private companion object {
        const val TAG = "Application"
    }
}
