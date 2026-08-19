package com.nevus.mediabridge.download

import kotlinx.serialization.Serializable

/**
 * Everything needed to (a) start a media download and (b) journal it for state recovery.
 *
 * `txId` doubles as the RecoverableStore transaction id and the human-readable download id
 * shown in the bubble; keep it short and unique per-session.
 */
@Serializable
data class DownloadRequest(
    val txId: String,
    val url: String,
    val kind: MediaKind,
    /** File name to save under. Optional; if null the engine derives one from the URL path. */
    val fileName: String? = null,
    /** Optional Referer header — many CDNs deny direct access without it. Ignored unless HTTPS. */
    val referer: String? = null,
    val userAgent: String? = null,
) {
    fun suggestedFileName(): String {
        val fromCaller = fileName?.takeIf { it.isNotBlank() }
        if (fromCaller != null) return sanitize(fromCaller)
        val last = url.substringAfterLast('/', missingDelimiterValue = "media")
            .substringBefore('?')
            .substringBefore('#')
            .ifBlank { "media" }
        return sanitize(last)
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        // Strip leading dots so we never write a hidden file, and cap length.
        return cleaned.trimStart('.').ifBlank { "media" }.take(120)
    }
}

/**
 * Progress emitted by [DownloadEngine]. Subscribers filter by [txId].
 */
sealed interface DownloadEvent {
    val txId: String

    data class Started(override val txId: String, val expectedBytes: Long?) : DownloadEvent
    data class Progress(override val txId: String, val bytesRead: Long, val totalBytes: Long?) : DownloadEvent
    data class Completed(
        override val txId: String,
        val outputPath: String,
        val sha256Hex: String,
        val bytesWritten: Long,
    ) : DownloadEvent
    data class Failed(override val txId: String, val message: String, val cause: Throwable? = null) : DownloadEvent
    data class Cancelled(override val txId: String) : DownloadEvent
}
