package com.nevus.mediabridge.state

import com.nevus.mediabridge.util.NevusLog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * A minimal append-only write-ahead journal for recoverable app state.
 *
 * Model — every mutation is captured as one of three record kinds:
 *  - `BEGIN(txId, work)`: a unit of work has started (e.g. "download #17").
 *  - `COMMIT(txId, payload)`: it finished successfully; payload is the durable result.
 *  - `ABORT(txId, reason)`: it failed cleanly; nothing else to do.
 *
 * On startup, [StateRecoveryAnalyzer] scans the journal:
 *   any `BEGIN` without a matching `COMMIT`/`ABORT` = in-flight when the process died.
 *   Those are the transactions the app must decide about (re-issue, discard, or surface to
 *   the user).
 *
 * Storage — one JSONL file per store name (`state/<name>.jsonl`). Each `append` fsyncs before
 * returning so a crash right after `append()` does not lose the record. `checkpoint()`
 * truncates the file to just the commits from a given point onward — the recommended
 * operation after every successful `analyzeOnStartup()`.
 *
 * Concurrency — a single instance may be shared across threads; `append` synchronizes on
 * `this`, `readAll` returns a snapshot list, and `checkpoint` is exclusive.
 */
class RecoverableStore<T>(
    private val root: File,
    private val name: String,
    private val payloadSerializer: KSerializer<T>,
    private val json: Json = defaultJson,
) {

    private val file: File get() = File(root, "$name.jsonl")

    init {
        require(name.isNotBlank() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "invalid store name: '$name'"
        }
        if (!root.exists()) root.mkdirs()
    }

    @Synchronized
    fun append(record: Record<T>) {
        val line = json.encodeToString(Record.serializer(payloadSerializer), record) + "\n"
        FileOutputStream(file, /* append = */ true).use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
            // Force a sync so an OS-level crash after this call does not lose the record.
            out.fd.sync()
        }
    }

    /**
     * Return every record in write order. Malformed lines are skipped and logged rather than
     * aborting recovery — the surrounding valid records may still be useful.
     */
    fun readAll(): List<Record<T>> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Record<T>>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { idx, raw ->
                if (raw.isBlank()) return@forEachIndexed
                try {
                    out.add(json.decodeFromString(Record.serializer(payloadSerializer), raw))
                } catch (t: SerializationException) {
                    NevusLog.w(TAG, "Skipping malformed record at line ${idx + 1} of ${file.name}: ${t.message}")
                }
            }
        }
        return out
    }

    /**
     * Truncate the journal, keeping only records with `index >= keepFromIndex`. Used after a
     * successful startup scan to prevent unbounded growth.
     */
    @Synchronized
    fun checkpoint(keepFromIndex: Long) {
        val kept = readAll().filter { it.index >= keepFromIndex }
        val tmp = File(root, "$name.jsonl.tmp")
        FileOutputStream(tmp, /* append = */ false).use { out ->
            kept.forEach { rec ->
                val line = json.encodeToString(Record.serializer(payloadSerializer), rec) + "\n"
                out.write(line.toByteArray(Charsets.UTF_8))
            }
            out.fd.sync()
        }
        // Atomic rename replaces the live journal only when the temp file is fully synced.
        if (!tmp.renameTo(file)) {
            // On some filesystems rename over an existing file requires delete-first.
            file.delete()
            require(tmp.renameTo(file)) { "checkpoint rename failed" }
        }
        NevusLog.i(TAG, "Checkpointed ${file.name}: kept ${kept.size} record(s) from index $keepFromIndex")
    }

    /** Verify the journal is readable without OOM'ing on a giant file. */
    fun estimatedSize(): Long =
        if (file.exists()) RandomAccessFile(file, "r").use { it.length() } else 0L

    @Serializable
    data class Record<T>(
        val index: Long,
        val timestampMs: Long,
        val txId: String,
        val kind: Kind,
        val payload: T? = null,
        val reason: String? = null,
    ) {
        enum class Kind { BEGIN, COMMIT, ABORT }
    }

    companion object {
        private const val TAG = "RecoverableStore"

        private val defaultJson = Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
            prettyPrint = false
        }

        /** Convenience for stores whose payload is a plain string (e.g. URL, path). */
        fun forStrings(root: File, name: String) =
            RecoverableStore(root, name, String.serializer())
    }
}
