package com.nevus.mediabridge.download

import kotlinx.serialization.Serializable

/**
 * One completed or failed download, as shown in [com.nevus.mediabridge.ui.DownloadManagerActivity].
 * Written once per terminal [DownloadEvent] (Completed or Failed) by [FloatingBubbleService] —
 * see [DownloadHistoryStore].
 */
@Serializable
data class DownloadHistoryEntry(
    val txId: String,
    val url: String,
    val kind: MediaKind,
    val fileName: String,
    val outputPath: String?,
    val bytesWritten: Long,
    val sha256Hex: String?,
    val status: Status,
    val timestampMs: Long,
    val failureMessage: String? = null,
) {
    enum class Status { COMPLETED, FAILED }
}
