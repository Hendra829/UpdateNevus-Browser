package com.nevus.mediabridge.download

/**
 * User's choices from [com.nevus.mediabridge.ui.DownloadOptionsDialog], submitted to
 * [FloatingBubbleService] to actually run the download (+ optional enhancement pass).
 */
data class DownloadPlan(
    val detection: FloatingBubbleService.Detection,
    val audioOnly: Boolean,
    val manifestKind: ManifestKind,
    val chosenVariant: QualityVariant?,
    val targetHeightPx: Int?,
    val enhance: Boolean,
)
