package com.nevus.mediabridge.audit

import com.nevus.mediabridge.util.NevusLog
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Live statistical validator logic.
 *
 * Continuously ingests a byte stream (typically CSPRNG output; also usable on hashed download
 * chunks or user-supplied entropy) and runs NIST SP 800-22 style tests over a **sliding
 * window** of the most recent N bytes. When a test's p-value drops below [alarmPValue] on
 * [alarmConsecutive] consecutive evaluations, an alarm is raised.
 *
 * Design decisions:
 *  - **Sliding window in a ring buffer** — O(1) ingest per byte, evaluation on demand.
 *  - **Consecutive-failure gate** — a single false positive at α=0.01 is expected on ~1% of
 *    evaluations; requiring several in a row before alarming keeps the signal actionable.
 *  - **Thread-safe** — ingest paths use `synchronized(buffer)` (very short critical section);
 *    the alarm dispatch happens outside the lock.
 *  - **No coroutines dependency in the core** — the class can be used from a plain
 *    `SecureRandom` wrapper, a test harness, or a background thread without pulling in a
 *    `CoroutineScope` at construction time.
 *
 * Typical wiring:
 * ```
 * val validator = LiveStatisticalValidator(windowBytes = 8192)
 * validator.onAlarm { report -> logger.warn("RNG suspect: $report") }
 * CSPRNGProvider.tapForHealthMonitor(CSPRNGHealthMonitor(validator))
 * ```
 */
class LiveStatisticalValidator(
    /** Sliding window size, in bytes. 8 KiB is a reasonable default for continuous monitoring. */
    private val windowBytes: Int = 8192,
    /** Byte count between test evaluations. */
    private val evaluationEveryBytes: Int = 1024,
    /** Reject the null hypothesis when p-value drops below this. NIST default: 0.01. */
    private val alarmPValue: Double = 0.01,
    /** Consecutive failures on the same test required to raise an alarm. */
    private val alarmConsecutive: Int = 3,
) {

    init {
        require(windowBytes >= 512) { "windowBytes must be ≥ 512 for a stable chi-square" }
        require(evaluationEveryBytes in 1..windowBytes)
        require(alarmPValue in 0.0..1.0)
        require(alarmConsecutive >= 1)
    }

    private val buffer = ByteArray(windowBytes)
    private var writeIndex = 0
    private var filled = 0

    private val bytesObserved = AtomicLong(0L)
    private val evaluations = AtomicLong(0L)
    private val alarmCount = AtomicLong(0L)
    private var sinceLastEval = 0

    private var monobitConsecutiveFails = 0
    private var runsConsecutiveFails = 0
    private var chiConsecutiveFails = 0
    private var blockFreqConsecutiveFails = 0

    private val lastReport = AtomicReference<TestReport?>(null)
    private val alarmListener = AtomicReference<((AlarmReport) -> Unit)?>(null)

    /** Bounded trend history for the audit screen — oldest first, capped at [RECENT_REPORTS_CAP]. */
    private val recentReports = ArrayDeque<TestReport>(RECENT_REPORTS_CAP)

    /** Register a callback invoked when the alarm gate opens. Only one listener at a time. */
    fun onAlarm(listener: (AlarmReport) -> Unit) {
        alarmListener.set(listener)
    }

    /** Ingest a sub-range of [source]; safe to call from any thread. */
    fun observe(source: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size)
        if (length == 0) return

        var toEval: TestReport? = null

        synchronized(buffer) {
            // Copy into the ring buffer, wrapping.
            var srcIdx = offset
            var remaining = length
            while (remaining > 0) {
                val chunk = minOf(remaining, buffer.size - writeIndex)
                System.arraycopy(source, srcIdx, buffer, writeIndex, chunk)
                writeIndex = (writeIndex + chunk) % buffer.size
                srcIdx += chunk
                remaining -= chunk
            }
            filled = minOf(filled + length, buffer.size)
            sinceLastEval += length
            bytesObserved.addAndGet(length.toLong())

            if (filled == buffer.size && sinceLastEval >= evaluationEveryBytes) {
                sinceLastEval = 0
                // We evaluate on a contiguous *copy* of the ring so tests can index linearly.
                val snapshot = ByteArray(buffer.size)
                val tail = buffer.size - writeIndex
                System.arraycopy(buffer, writeIndex, snapshot, 0, tail)
                System.arraycopy(buffer, 0, snapshot, tail, writeIndex)
                toEval = runTests(snapshot)
            }
        }

        toEval?.let { report ->
            lastReport.set(report)
            evaluations.incrementAndGet()
            synchronized(recentReports) {
                if (recentReports.size >= RECENT_REPORTS_CAP) recentReports.removeFirst()
                recentReports.addLast(report)
            }
            maybeAlarm(report)
        }
    }

    private fun runTests(snapshot: ByteArray): TestReport = TestReport(
        monobitP = NistTests.monobit(snapshot, 0, snapshot.size),
        runsP = NistTests.runs(snapshot, 0, snapshot.size),
        chiSquareP = NistTests.byteHistogramChiSquare(snapshot, 0, snapshot.size),
        blockFrequencyP = NistTests.blockFrequency(snapshot, 0, snapshot.size),
        windowBytes = snapshot.size,
        atByteCount = bytesObserved.get(),
    )

    private fun maybeAlarm(report: TestReport) {
        val monobitFail = report.monobitP < alarmPValue
        val runsFail = report.runsP < alarmPValue
        val chiFail = report.chiSquareP < alarmPValue
        val blockFreqFail = report.blockFrequencyP < alarmPValue

        monobitConsecutiveFails = if (monobitFail) monobitConsecutiveFails + 1 else 0
        runsConsecutiveFails = if (runsFail) runsConsecutiveFails + 1 else 0
        chiConsecutiveFails = if (chiFail) chiConsecutiveFails + 1 else 0
        blockFreqConsecutiveFails = if (blockFreqFail) blockFreqConsecutiveFails + 1 else 0

        val triggered = buildList {
            if (monobitConsecutiveFails >= alarmConsecutive) add(Trigger.MONOBIT to report.monobitP)
            if (runsConsecutiveFails >= alarmConsecutive) add(Trigger.RUNS to report.runsP)
            if (chiConsecutiveFails >= alarmConsecutive) add(Trigger.CHI_SQUARE to report.chiSquareP)
            if (blockFreqConsecutiveFails >= alarmConsecutive) add(Trigger.BLOCK_FREQUENCY to report.blockFrequencyP)
        }
        if (triggered.isEmpty()) return

        val alarm = AlarmReport(
            triggered = triggered.map { it.first },
            report = report,
            index = alarmCount.incrementAndGet(),
        )
        NevusLog.w(TAG, "Alarm #${alarm.index}: ${alarm.triggered} — p-values ${triggered.map { it.second }}")
        // Reset the consecutive counters that just fired to avoid an alarm storm; still track future failures.
        if (Trigger.MONOBIT in alarm.triggered) monobitConsecutiveFails = 0
        if (Trigger.RUNS in alarm.triggered) runsConsecutiveFails = 0
        if (Trigger.CHI_SQUARE in alarm.triggered) chiConsecutiveFails = 0
        if (Trigger.BLOCK_FREQUENCY in alarm.triggered) blockFreqConsecutiveFails = 0

        alarmListener.get()?.invoke(alarm)
    }

    /** Observability snapshot for the audit screen. */
    fun snapshot(): Snapshot = Snapshot(
        bytesObserved = bytesObserved.get(),
        evaluations = evaluations.get(),
        alarms = alarmCount.get(),
        lastReport = lastReport.get(),
        windowBytes = windowBytes,
        recentReports = synchronized(recentReports) { recentReports.toList() },
    )

    enum class Trigger { MONOBIT, RUNS, CHI_SQUARE, BLOCK_FREQUENCY }

    data class TestReport(
        val monobitP: Double,
        val runsP: Double,
        val chiSquareP: Double,
        val blockFrequencyP: Double,
        val windowBytes: Int,
        val atByteCount: Long,
    )

    data class AlarmReport(
        val triggered: List<Trigger>,
        val report: TestReport,
        val index: Long,
    )

    data class Snapshot(
        val bytesObserved: Long,
        val evaluations: Long,
        val alarms: Long,
        val lastReport: TestReport?,
        val windowBytes: Int,
        /** Oldest-first, capped trend history — for the audit screen's p-value trend view. */
        val recentReports: List<TestReport>,
    )

    private companion object {
        const val TAG = "StatValidator"
        const val RECENT_REPORTS_CAP = 20
    }
}
