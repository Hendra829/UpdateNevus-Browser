package com.nevus.mediabridge.download

import com.nevus.mediabridge.util.NevusLog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only JSONL log of finished downloads, at `filesDir/state/download_history.jsonl`.
 *
 * This is a plain log, not a [com.nevus.mediabridge.state.RecoverableStore] — that class's
 * BEGIN/COMMIT/ABORT semantics exist for crash-recovery transaction scanning, which does not fit
 * "append one record per finished download". Same fsync-before-return durability discipline,
 * simpler shape.
 */
class DownloadHistoryStore(private val root: File) {

    private val file: File get() = File(root, "download_history.jsonl")

    init {
        if (!root.exists()) root.mkdirs()
    }

    @Synchronized
    fun append(entry: DownloadHistoryEntry) {
        val line = json.encodeToString(entry) + "\n"
        FileOutputStream(file, /* append = */ true).use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** Most recent first. */
    fun readAll(): List<DownloadHistoryEntry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<DownloadHistoryEntry>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { idx, raw ->
                if (raw.isBlank()) return@forEachIndexed
                try {
                    out.add(json.decodeFromString(DownloadHistoryEntry.serializer(), raw))
                } catch (t: SerializationException) {
                    NevusLog.w(TAG, "Skipping malformed history record at line ${idx + 1}: ${t.message}")
                }
            }
        }
        return out.asReversed()
    }

    /** Remove a single entry by txId. No-op if it isn't present. */
    @Synchronized
    fun remove(txId: String) {
        val kept = readAll().asReversed().filterNot { it.txId == txId }
        rewrite(kept)
    }

    /** Wipe the whole history. */
    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun rewrite(entries: List<DownloadHistoryEntry>) {
        val tmp = File(root, "download_history.jsonl.tmp")
        FileOutputStream(tmp, /* append = */ false).use { out ->
            entries.forEach { entry ->
                out.write((json.encodeToString(entry) + "\n").toByteArray(Charsets.UTF_8))
            }
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val TAG = "DownloadHistory"

        private val json = Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    }
}
