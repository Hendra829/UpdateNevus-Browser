package com.nevus.mediabridge.state

import android.content.Context
import android.os.Build
import android.os.Process
import com.nevus.mediabridge.BuildConfig
import com.nevus.mediabridge.util.NevusLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * State Recovery Analysis logic.
 *
 * On process startup this class answers three questions:
 *  1. Did the last process die uncleanly? — via a sentinel file that must match the previous
 *     recorded PID / version / boot.
 *  2. What work was in-flight when it died? — by scanning every [RecoverableStore] journal
 *     the app has registered.
 *  3. What can be truncated? — the highest record index safely past checkpoint boundaries.
 *
 * Usage in [com.nevus.mediabridge.NevusApplication.onCreate]:
 * ```
 * val analyzer = StateRecoveryAnalyzer(this)
 *     .register("downloads", DownloadRequest.serializer())
 *     .register("bubble", BubbleState.serializer())
 * val report = analyzer.analyzeOnStartup()
 * if (report.hadCrash) surfaceCrashRecoveryToUser(report)
 * ```
 *
 * The analyzer is intentionally sync — startup work should be observable and short. All I/O
 * lives in the app's internal `filesDir/state/`; nothing is written to shared storage.
 */
class StateRecoveryAnalyzer(context: Context) {

    private val stateRoot: File = File(context.filesDir, "state").apply { mkdirs() }
    private val sentinelFile: File = File(stateRoot, "sentinel.json")
    private val registered: MutableMap<String, RecoverableStore<*>> = LinkedHashMap()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Register a store the analyzer should scan on startup. Chainable. */
    fun <T> register(name: String, payloadSerializer: kotlinx.serialization.KSerializer<T>): StateRecoveryAnalyzer {
        registered[name] = RecoverableStore(stateRoot, name, payloadSerializer)
        return this
    }

    /**
     * Perform the startup pass. Call exactly once from `Application.onCreate` before any other
     * subsystem reads or writes state.
     */
    fun analyzeOnStartup(): RecoveryReport {
        val t0 = System.currentTimeMillis()
        val previous = readSentinel()
        val currentBoot = readBootId()
        // A sentinel file present at startup ⇒ the previous run did not reach `markCleanShutdown`.
        // A missing sentinel ⇒ clean previous exit (or first-ever launch).
        val cleanShutdown = previous == null

        var inFlight = mutableListOf<InFlightTx>()
        var corrupted = 0
        var highest = 0L

        for ((storeName, store) in registered) {
            val records = try {
                store.readAll()
            } catch (t: Throwable) {
                NevusLog.e(TAG, "Failed to read store '$storeName' — treating as corrupted", t)
                corrupted += 1
                continue
            }
            val txnState = LinkedHashMap<String, RecoverableStore.Record<*>>()  // txId -> BEGIN record
            for (rec in records) {
                if (rec.index > highest) highest = rec.index
                when (rec.kind) {
                    RecoverableStore.Record.Kind.BEGIN -> txnState[rec.txId] = rec
                    RecoverableStore.Record.Kind.COMMIT, RecoverableStore.Record.Kind.ABORT -> txnState.remove(rec.txId)
                }
            }
            // Anything still in txnState is in-flight.
            txnState.values.forEach { begin ->
                inFlight.add(
                    InFlightTx(
                        store = storeName,
                        txId = begin.txId,
                        startedAtMs = begin.timestampMs,
                        beginPayloadJson = begin.payload?.let { any ->
                            @Suppress("UNCHECKED_CAST")
                            runCatching {
                                // Best-effort JSON — falls back to toString on unrepresentable payloads.
                                (any as? Any)?.toString()
                            }.getOrNull()
                        },
                    )
                )
            }
        }

        // Write the current run's sentinel; if we're mid-startup and someone else wrote it, that's fine.
        writeSentinel(currentBoot)

        val report = RecoveryReport(
            cleanShutdown = cleanShutdown,
            previousSentinel = previous,
            inFlight = inFlight,
            corruptedRecordCount = corrupted,
            highestIndex = highest,
            elapsedMs = System.currentTimeMillis() - t0,
        )
        NevusLog.i(TAG, "analyzeOnStartup: ${report.summary()}")
        return report
    }

    /**
     * Mark a graceful shutdown — call from `Application`'s lifecycle or a `ProcessLifecycleOwner`
     * on `Lifecycle.State.DESTROYED`. Missing on next startup ⇒ crash detection triggers.
     */
    fun markCleanShutdown() {
        if (sentinelFile.exists() && !sentinelFile.delete()) {
            NevusLog.w(TAG, "Could not delete sentinel — clean-shutdown mark did not stick")
        }
    }

    /**
     * Access a registered store — for the app code that wants to append `BEGIN`/`COMMIT`/`ABORT`
     * records for its own transactions.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> store(name: String): RecoverableStore<T> =
        (registered[name] as? RecoverableStore<T>)
            ?: error("Store '$name' not registered before use")

    // ─────────── sentinel I/O ───────────

    private fun writeSentinel(bootId: String) {
        val sentinel = StartupSentinelJson(
            pid = Process.myPid(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            bootId = bootId,
            startedAtMs = System.currentTimeMillis(),
        )
        try {
            sentinelFile.writeText(json.encodeToString(sentinel))
        } catch (t: Throwable) {
            NevusLog.e(TAG, "Failed to write sentinel", t)
        }
    }

    private fun readSentinel(): StartupSentinel? = try {
        if (!sentinelFile.exists()) null else {
            val decoded = json.decodeFromString(StartupSentinelJson.serializer(), sentinelFile.readText())
            StartupSentinel(decoded.pid, decoded.versionName, decoded.versionCode, decoded.bootId, decoded.startedAtMs)
        }
    } catch (t: Throwable) {
        NevusLog.w(TAG, "Sentinel unreadable (treating as clean-slate)", t)
        null
    }

    /**
     * Device boot session identifier. Same across all processes started in the same boot;
     * changes on reboot. Falls back to a synthetic per-process token pre-Android 8.
     */
    private fun readBootId(): String {
        // /proc/sys/kernel/random/boot_id is a UUID that changes on every boot.
        return try {
            File("/proc/sys/kernel/random/boot_id").readText().trim().ifBlank { fallbackBootId() }
        } catch (t: Throwable) {
            fallbackBootId()
        }
    }

    private fun fallbackBootId(): String = "no-boot-id/${Build.FINGERPRINT.hashCode()}"

    @Serializable
    private data class StartupSentinelJson(
        val pid: Int,
        val versionName: String,
        val versionCode: Int,
        val bootId: String,
        val startedAtMs: Long,
    )

    private companion object {
        const val TAG = "StateRecovery"
    }
}
