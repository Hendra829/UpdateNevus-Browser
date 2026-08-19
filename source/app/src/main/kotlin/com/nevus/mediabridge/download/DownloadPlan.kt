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
    val stickerMode: StickerMode = StickerMode.NONE,
    /** Split into ~20 parallel Range connections and merge (see [DownloadEngine.enqueueSegmented]). Ignored when [chosenVariant] is set — HLS/DASH already downloads via its own segments. */
    val segmented: Boolean = false,
)

/** How (if at all) to also produce a sticker-style copy of a downloaded image — see [com.nevus.mediabridge.media.StickerMaker]. */
enum class StickerMode { NONE, BORDER, BACKGROUND_REMOVAL }
