package com.nevus.mediabridge.download

import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads real quality renditions out of HLS (`.m3u8`) and DASH (`.mpd`) manifests — no fabricated
 * options. Scope is deliberately bounded (documented per case below); anything outside it fails
 * clearly (`null`/empty) rather than guessing.
 *
 * Not supported, on purpose: encrypted HLS (`#EXT-X-KEY`), live/incomplete HLS playlists (no
 * `#EXT-X-ENDLIST`), DASH multi-`Period`, and DASH `SegmentList`/duration-only `SegmentTemplate`
 * (only `SegmentTemplate` + explicit `SegmentTimeline`, or a plain single-file `BaseURL`
 * `Representation`, are resolved).
 */
object PlaylistParser {

    private const val TAG = "PlaylistParser"
    private const val MAX_MANIFEST_BYTES = 5 * 1024 * 1024
    private const val MAX_SEGMENTS = 20_000

    fun probe(url: String): ManifestKind {
        val path = runCatching { URI(url).path }.getOrNull()?.lowercase().orEmpty()
        return when {
            path.endsWith(".m3u8") -> ManifestKind.HLS
            path.endsWith(".mpd") -> ManifestKind.DASH
            else -> ManifestKind.NONE
        }
    }

    suspend fun listVariants(url: String, kind: ManifestKind): List<QualityVariant> {
        if (kind == ManifestKind.NONE) return emptyList()
        val body = fetchText(url) ?: return emptyList()
        return runCatching {
            when (kind) {
                ManifestKind.HLS -> parseHlsMasterVariants(body)
                ManifestKind.DASH -> parseDashVariants(body)
                ManifestKind.NONE -> emptyList()
            }
        }.onFailure { NevusLog.w(TAG, "Failed to parse manifest variants for $url", it) }
            .getOrDefault(emptyList())
    }

    suspend fun resolve(manifestUrl: String, kind: ManifestKind, variant: QualityVariant): ResolvedVariant? {
        val body = fetchText(manifestUrl) ?: return null
        return runCatching {
            when (kind) {
                ManifestKind.HLS -> resolveHls(manifestUrl, variant)
                ManifestKind.DASH -> resolveDash(manifestUrl, body, variant)
                ManifestKind.NONE -> null
            }
        }.onFailure { NevusLog.w(TAG, "Failed to resolve variant ${variant.label} for $manifestUrl", it) }
            .getOrNull()
    }

    // ─────────── HLS ───────────

    private fun parseHlsMasterVariants(master: String): List<QualityVariant> {
        val lines = master.lines()
        val out = mutableListOf<QualityVariant>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val attrs = parseAttributeList(line.substringAfter(':'))
            val uri = lines.getOrNull(i + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith('#') } ?: continue
            val resolution = attrs["RESOLUTION"] // e.g. "1920x1080"
            val height = resolution?.substringAfter('x', "")?.toIntOrNull()
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull()
            val label = height?.let { "${it}p" } ?: bandwidth?.let { "${it / 1000} kbps" } ?: "Varian ${out.size + 1}"
            out.add(QualityVariant(label = label, heightPx = height, bandwidthBps = bandwidth, locator = uri))
        }
        // Highest quality first — more useful default ordering for a picker.
        return out.sortedByDescending { it.heightPx ?: 0 }
    }

    private suspend fun resolveHls(masterUrl: String, variant: QualityVariant): ResolvedVariant? {
        val mediaPlaylistUrl = resolveRelative(masterUrl, variant.locator)
        val mediaBody = fetchText(mediaPlaylistUrl) ?: return null
        val lines = mediaBody.lines()

        if (lines.any { it.trim().startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") }) {
            NevusLog.w(TAG, "Rejecting encrypted HLS media playlist: $mediaPlaylistUrl")
            return null
        }
        if (lines.none { it.trim() == "#EXT-X-ENDLIST" }) {
            NevusLog.w(TAG, "Rejecting live/incomplete HLS playlist (no ENDLIST): $mediaPlaylistUrl")
            return null
        }

        val segments = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            segments.add(resolveRelative(mediaPlaylistUrl, trimmed))
            if (segments.size > MAX_SEGMENTS) {
                NevusLog.w(TAG, "HLS playlist exceeds $MAX_SEGMENTS segments — rejecting: $mediaPlaylistUrl")
                return null
            }
        }
        if (segments.isEmpty()) return null

        val container = if (segments.first().substringAfterLast('.', "").lowercase() in FMP4_EXT) {
            SegmentContainer.FMP4
        } else {
            SegmentContainer.MPEG_TS
        }
        return ResolvedVariant(variant, segments, container)
    }

    /** Splits an `#EXT-X-STREAM-INF:` attribute list on commas that are not inside quotes. */
    private fun parseAttributeList(raw: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var depth = 0
        var start = 0
        val parts = mutableListOf<String>()
        for (i in raw.indices) {
            when (raw[i]) {
                '"' -> depth = 1 - depth
                ',' -> if (depth == 0) {
                    parts.add(raw.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(raw.substring(start))
        for (part in parts) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim().trim('"')
            out[key] = value
        }
        return out
    }

    // ─────────── DASH ───────────

    private fun parseDashVariants(mpd: String): List<QualityVariant> {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(mpd.byteInputStream())

        val out = mutableListOf<QualityVariant>()
        val adaptationSets = doc.getElementsByTagName("AdaptationSet")
        for (i in 0 until adaptationSets.length) {
            val adaptationSet = adaptationSets.item(i) as? Element ?: continue
            val mimeType = adaptationSet.getAttribute("mimeType").ifBlank { null }
                ?: adaptationSet.getElementsByTagName("Representation").let { reps ->
                    (0 until reps.length).firstNotNullOfOrNull { (reps.item(it) as? Element)?.getAttribute("mimeType")?.ifBlank { null } }
                }
            if (mimeType != null && !mimeType.startsWith("video/")) continue

            val representations = adaptationSet.getElementsByTagName("Representation")
            for (r in 0 until representations.length) {
                val rep = representations.item(r) as? Element ?: continue
                val id = rep.getAttribute("id")
                if (id.isBlank()) continue
                val height = rep.getAttribute("height").toIntOrNull()
                val bandwidth = rep.getAttribute("bandwidth").toLongOrNull()
                val label = height?.let { "${it}p" } ?: bandwidth?.let { "${it / 1000} kbps" } ?: "Representation $id"
                out.add(QualityVariant(label = label, heightPx = height, bandwidthBps = bandwidth, locator = id))
            }
        }
        return out.sortedByDescending { it.heightPx ?: 0 }
    }

    private fun resolveDash(mpdUrl: String, mpd: String, variant: QualityVariant): ResolvedVariant? {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(mpd.byteInputStream())

        val representations = doc.getElementsByTagName("Representation")
        var target: Element? = null
        for (i in 0 until representations.length) {
            val rep = representations.item(i) as? Element ?: continue
            if (rep.getAttribute("id") == variant.locator) { target = rep; break }
        }
        val rep = target ?: return null

        // Case A: plain single-file representation via <BaseURL>.
        val baseUrlEl = rep.getElementsByTagName("BaseURL").item(0) as? Element
        if (baseUrlEl != null) {
            val fileUrl = resolveRelative(mpdUrl, baseUrlEl.textContent.trim())
            return ResolvedVariant(variant, listOf(fileUrl), SegmentContainer.FMP4)
        }

        // Case B: SegmentTemplate + explicit SegmentTimeline.
        val template = rep.getElementsByTagName("SegmentTemplate").item(0) as? Element ?: return null
        val timeline = template.getElementsByTagName("SegmentTimeline").item(0) as? Element ?: return null
        val mediaPattern = template.getAttribute("media").ifBlank { return null }
        val initPattern = template.getAttribute("initialization").ifBlank { null }
        val startNumber = template.getAttribute("startNumber").toIntOrNull() ?: 1

        val segmentNumbers = mutableListOf<Int>()
        var number = startNumber
        val sNodes = timeline.getElementsByTagName("S")
        for (i in 0 until sNodes.length) {
            val s = sNodes.item(i) as? Element ?: continue
            val repeatCount = s.getAttribute("r").toIntOrNull() ?: 0
            repeatTimes(repeatCount + 1) {
                segmentNumbers.add(number)
                number += 1
                if (segmentNumbers.size > MAX_SEGMENTS) return null
            }
        }
        if (segmentNumbers.isEmpty()) return null

        val representationId = rep.getAttribute("id")
        fun substitute(pattern: String, num: Int?): String {
            var s = pattern.replace("\$RepresentationID\$", representationId)
            if (num != null) {
                s = Regex("\\\$Number%0(\\d+)d\\\$").replace(s) { m -> num.toString().padStart(m.groupValues[1].toInt(), '0') }
                s = s.replace("\$Number\$", num.toString())
            }
            return s
        }

        val segments = mutableListOf<String>()
        initPattern?.let { segments.add(resolveRelative(mpdUrl, substitute(it, null))) }
        segmentNumbers.forEach { n -> segments.add(resolveRelative(mpdUrl, substitute(mediaPattern, n))) }

        return ResolvedVariant(variant, segments, SegmentContainer.FMP4)
    }

    private inline fun repeatTimes(times: Int, block: () -> Unit) {
        for (i in 0 until times) block()
    }

    // ─────────── shared HTTP/URL helpers ───────────

    private fun resolveRelative(base: String, relative: String): String =
        runCatching { URI(base).resolve(relative).toString() }.getOrDefault(relative)

    private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = URL(url)
            require(parsed.protocol.equals("https", ignoreCase = true)) { "manifest fetch requires https: $url" }
            val conn = parsed.openConnection() as HttpsURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                val bytes = conn.inputStream.use { it.readBytes(MAX_MANIFEST_BYTES) }
                bytes.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }.onFailure { NevusLog.w(TAG, "Failed to fetch manifest $url", it) }.getOrNull()
    }

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw IllegalStateException("manifest exceeds $limit bytes")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private val FMP4_EXT = setOf("m4s", "mp4", "m4v")
}
