package com.nevus.mediabridge.crypto

import com.nevus.mediabridge.audit.LiveStatisticalValidator
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Bridges [CSPRNGProvider] output to a [LiveStatisticalValidator] and turns statistical alarms
 * into concrete mitigations (automatic reseed + observable events).
 *
 * Attach at process start:
 * ```
 * val monitor = CSPRNGHealthMonitor(validator)
 * CSPRNGProvider.tapForHealthMonitor(monitor)
 * ```
 *
 * The tap only observes sampled bursts (≤256 bytes per burst by default) so it does not
 * degrade throughput on high-volume callers such as content hashing.
 */
class CSPRNGHealthMonitor(
    private val validator: LiveStatisticalValidator,
    private val onAlarmReseed: Boolean = true,
) {

    private val _events = MutableSharedFlow<Event>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events

    private val periodicReseedRunning = AtomicBoolean(false)

    init {
        validator.onAlarm { report ->
            NevusLog.w(TAG, "Statistical alarm: $report")
            _events.tryEmit(Event.Alarm(report))
            if (onAlarmReseed) {
                CSPRNGProvider.reseedSelf()
                _events.tryEmit(Event.ReseedApplied(reason = "post-alarm"))
            }
        }
        _events.tryEmit(Event.Attached)
    }

    /** Fed by [CSPRNGProvider.nextBytes]. Not intended to be called by app code directly. */
    fun observeBurst(burst: ByteArray, sampleCap: Int) {
        val take = min(burst.size, sampleCap)
        if (take <= 0) return
        // Take a contiguous prefix (already random within a burst, so any deterministic slice is fine).
        validator.observe(burst, 0, take)
    }

    /**
     * Reseed on a fixed cadence, independent of any statistical alarm — defence against a
     * generator that has quietly drifted in a way the live tests don't yet flag. Idempotent;
     * a second call is a no-op until [stopPeriodicReseed] is invoked.
     */
    fun schedulePeriodicReseed(intervalMs: Long) {
        require(intervalMs > 0) { "intervalMs must be positive" }
        if (!periodicReseedRunning.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "csprng-periodic-reseed") {
            while (periodicReseedRunning.get()) {
                try {
                    Thread.sleep(intervalMs)
                } catch (_: InterruptedException) {
                    break
                }
                if (!periodicReseedRunning.get()) break
                runCatching { CSPRNGProvider.reseedSelf() }
                    .onSuccess { _events.tryEmit(Event.ReseedApplied(reason = "periodic")) }
                    .onFailure { NevusLog.w(TAG, "Periodic reseed failed", it) }
            }
        }
    }

    /** Stop the periodic reseed loop started by [schedulePeriodicReseed], if running. */
    fun stopPeriodicReseed() {
        periodicReseedRunning.set(false)
    }

    /** Public status snapshot — useful for the settings/audit screen. */
    fun snapshot(): Snapshot = Snapshot(
        provider = CSPRNGProvider.stats(),
        validator = validator.snapshot(),
    )

    sealed interface Event {
        data object Attached : Event
        data class Alarm(val report: LiveStatisticalValidator.AlarmReport) : Event
        data class ReseedApplied(val reason: String) : Event
    }

    data class Snapshot(
        val provider: CSPRNGProvider.Stats,
        val validator: LiveStatisticalValidator.Snapshot,
    )

    private companion object {
        const val TAG = "CSPRNGHealth"
    }
}
