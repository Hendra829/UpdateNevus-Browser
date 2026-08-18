package com.nevus.mediabridge.crypto

import com.nevus.mediabridge.audit.LiveStatisticalValidator
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
