package com.nevus.mediabridge.download

/**
 * One selectable rendition surfaced by [PlaylistParser] from a real HLS/DASH manifest. Never
 * fabricated — if a manifest exposes only one rendition, that is the only [QualityVariant]
 * offered for it.
 *
 * [locator] is an implementation detail [PlaylistParser] needs to re-resolve this variant into
 * an actual segment list (HLS: the variant's media-playlist URL; DASH: the `Representation`
 * `@id`) — not meant to be interpreted by callers.
 */
data class QualityVariant(
    val label: String,
    val heightPx: Int?,
    val bandwidthBps: Long?,
    internal val locator: String,
)

/** How a [ResolvedVariant]'s segments should be concatenated into one output file. */
enum class SegmentContainer {
    /** Raw MPEG-TS segments — concatenated as-is, output kept as `.ts`. */
    MPEG_TS,

    /** Fragmented MP4 (CMAF) — init segment + media segments concatenate into a valid `.mp4`. */
    FMP4,
}

/** A [QualityVariant] with its segment URLs resolved, ready for [DownloadEngine.enqueueVariant]. */
data class ResolvedVariant(
    val variant: QualityVariant,
    val segmentUrls: List<String>,
    val container: SegmentContainer,
)

enum class ManifestKind { NONE, HLS, DASH }
