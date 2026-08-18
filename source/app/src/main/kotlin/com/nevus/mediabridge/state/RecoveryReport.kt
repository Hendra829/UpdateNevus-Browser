package com.nevus.mediabridge.state

/**
 * Outcome of a startup [StateRecoveryAnalyzer.analyzeOnStartup] pass.
 *
 * Human-friendly [summary] is meant for a log line; [inFlight] is the actionable list that the
 * app should walk to decide what to re-issue.
 */
data class RecoveryReport(
    /** True if the previous shutdown was clean (matching sentinel found, no in-flight tx). */
    val cleanShutdown: Boolean,
    /** Detected on-disk sentinel — the app's own PID + version from the previous run. */
    val previousSentinel: StartupSentinel?,
    /** Transactions started but neither committed nor aborted before the crash. */
    val inFlight: List<InFlightTx>,
    /** Records the analyzer had to skip because they were malformed. */
    val corruptedRecordCount: Int,
    /** Highest record index seen; useful to pass to `RecoverableStore.checkpoint`. */
    val highestIndex: Long,
    /** Milliseconds spent analyzing. */
    val elapsedMs: Long,
) {

    val hadCrash: Boolean get() = !cleanShutdown

    fun summary(): String = buildString {
        append(if (cleanShutdown) "clean" else "CRASH")
        append("; in-flight=").append(inFlight.size)
        append("; corrupted=").append(corruptedRecordCount)
        append("; highestIndex=").append(highestIndex)
        append("; elapsedMs=").append(elapsedMs)
        previousSentinel?.let {
            append("; prev pid=").append(it.pid)
            append(" version=").append(it.versionName)
        }
    }
}

/**
 * A transaction found in the journal without a matching commit/abort. The app decides how to
 * handle it — reissue, drop, or ask the user.
 */
data class InFlightTx(
    val store: String,
    val txId: String,
    val startedAtMs: Long,
    /** JSON representation of the payload attached to the `BEGIN` record, if any. */
    val beginPayloadJson: String?,
)

/**
 * Marker written to `state/sentinel.json` on startup and re-checked on the next startup.
 *
 * A missing or mismatched sentinel on read = the previous process did not exit cleanly.
 */
data class StartupSentinel(
    val pid: Int,
    val versionName: String,
    val versionCode: Int,
    val bootId: String,
    val startedAtMs: Long,
)
