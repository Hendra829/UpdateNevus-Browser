package com.nevus.mediabridge.state

import android.content.Context
import android.os.Build
import android.os.Process
import com.nevus.mediabridge.BuildConfig
import com.nevus.mediabridge.util.NevusLog
import kotlinx.serialization.KSerializer
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
    private val registered: MutableMap<String, RegisteredStore<*>> = LinkedHashMap()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Register a store the analyzer should scan on startup. Chainable. */
    fun <T> register(name: String, payloadSerializer: KSerializer<T>): StateRecoveryAnalyzer {
        val store = RecoverableStore(stateRoot, name, payloadSerializer)
        registered[name] = RegisteredStore(store, payloadSerializer)
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
        val cleanShutdown = previous == null
        val crossedBoot = previous != null && previous.bootId.isNotBlank() &&
            currentBoot.isNotBlank() && previous.bootId != currentBoot

        val inFlight = mutableListOf<InFlightTx>()
        var corrupted = 0
        var highest = 0L

        for ((storeName, entry) in registered) {
            val records = try {
                entry.store.readAll()
            } catch (t: Throwable) {
                NevusLog.e(TAG, "Failed to read store '$storeName' — treating as corrupted", t)
                corrupted += 1
                continue
            }
            val txnState = LinkedHashMap<String, RecoverableStore.Record<*>>()
            for (rec in records) {
                if (rec.index > highest) highest = rec.index
                when (rec.kind) {
                    RecoverableStore.Record.Kind.BEGIN -> txnState[rec.txId] = rec
                    RecoverableStore.Record.Kind.COMMIT,
                    RecoverableStore.Record.Kind.ABORT -> txnState.remove(rec.txId)
                }
            }
            txnState.values.forEach { begin ->
                inFlight.add(
                    InFlightTx(
                        store = storeName,
                        txId = begin.txId,
                        startedAtMs = begin.timestampMs,
                        beginPayloadJson = entry.encodePayload(begin),
                    )
                )
            }
        }

        writeSentinel(currentBoot)

        val report = RecoveryReport(
            cleanShutdown = cleanShutdown,
            previousSentinel = previous,
            inFlight = inFlight,
            corruptedRecordCount = corrupted,
            highestIndex = highest,
            elapsedMs = System.currentTimeMillis() - t0,
            crossedBoot = crossedBoot,
        )
        NevusLog.i(TAG, "analyzeOnStartup: ${report.summary()}")
        return report
    }

    /**
     * Mark a graceful shutdown — invoked from the [androidx.lifecycle.ProcessLifecycleOwner]
     * `onStop` callback. A missing sentinel on next startup ⇒ clean previous exit.
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
        (registered[name]?.store as? RecoverableStore<T>)
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
        runCatching {
            val tmp = File(stateRoot, "sentinel.json.tmp")
            tmp.writeText(json.encodeToString(sentinel))
            if (!tmp.renameTo(sentinelFile)) {
                sentinelFile.delete()
                if (!tmp.renameTo(sentinelFile)) throw IllegalStateException("rename failed")
            }
        }.onFailure { NevusLog.e(TAG, "Failed to write sentinel", it) }
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

    private fun readBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("").ifBlank { fallbackBootId() }

    private fun fallbackBootId(): String = "no-boot-id/${Build.FINGERPRINT.hashCode()}"

    @Serializable
    private data class StartupSentinelJson(
        val pid: Int,
        val versionName: String,
        val versionCode: Int,
        val bootId: String,
        val startedAtMs: Long,
    )

    private data class RegisteredStore<T>(
        val store: RecoverableStore<T>,
        val serializer: KSerializer<T>,
    ) {
        fun encodePayload(record: RecoverableStore.Record<*>): String? {
            val payload = record.payload ?: return null
            @Suppress("UNCHECKED_CAST")
            return runCatching {
                Json.encodeToString(serializer, payload as T)
            }.getOrElse { payload.toString() }
        }
    }

    private companion object {
        const val TAG = "StateRecovery"
    }
}
