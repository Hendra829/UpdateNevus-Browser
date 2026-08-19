package com.nevus.mediabridge.download

import android.net.Uri

/**
 * Classifies URLs into one of the four supported [MediaKind]s. Returns `null` for anything
 * that does not clearly look like a video, audio, image, or music resource — the bubble stays
 * quiet on HTML pages, scripts, style sheets, archives, executables, or unknown types.
 *
 * Strategy — fast path on extension first, then a small set of well-known streaming
 * containers and music-platform host hints. No network requests.
 *
 * Performance — a bounded LRU (default 512 entries) short-circuits repeat classifications for
 * hot pages that request the same subresources dozens of times per second.
 */
class MediaUrlDetector(
    private val cacheCapacity: Int = 512,
) {

    private val cache: MutableMap<String, MediaKind?> = object :
        LinkedHashMap<String, MediaKind?>(cacheCapacity, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MediaKind?>): Boolean =
            size > cacheCapacity
    }

    fun classify(url: String): MediaKind? {
        synchronized(cache) {
            if (cache.containsKey(url)) return cache[url]
        }
        val result = classifyUncached(url)
        synchronized(cache) { cache[url] = result }
        return result
    }

    private fun classifyUncached(url: String): MediaKind? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "blob") return null

        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")

        if (extension in NON_MEDIA_EXT) return null

        val byExt: MediaKind? = when (extension) {
            in VIDEO_EXT -> MediaKind.VIDEO
            in IMAGE_EXT -> MediaKind.IMAGE
            in AUDIO_EXT -> if (looksLikeMusic(uri, host, path)) MediaKind.MUSIC else MediaKind.AUDIO
            else -> null
        }
        if (byExt != null) return byExt

        if (extension == "m3u8" || extension == "mpd" || path.contains("/hls/") || path.contains("/dash/")) {
            return MediaKind.VIDEO
        }
        if (host in KNOWN_MUSIC_HOSTS) return MediaKind.MUSIC

        return null
    }

    private fun looksLikeMusic(uri: Uri, host: String, path: String): Boolean {
        if (host in KNOWN_MUSIC_HOSTS) return true
        val query = uri.query?.lowercase().orEmpty()
        return MUSIC_HINTS.any { hint -> hint in path || hint in query }
    }

    /** Drop the cache — useful when the WebView is torn down. */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    companion object {
        // 100+ real, established container/codec extensions across the three kinds — covers
        // common, legacy, regional and professional formats, not padded with made-up ones.
        private val VIDEO_EXT = setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "qt", "3gp", "3g2", "ts", "m2ts", "mts",
            "mpg", "mpeg", "mpe", "m1v", "m2v", "flv", "f4v", "f4p", "wmv", "asf", "rm", "rmvb",
            "vob", "divx", "ogv", "dv", "mxf", "m4p", "yuv", "drc", "mng", "nsv",
        )
        private val AUDIO_EXT = setOf(
            "mp3", "aac", "m4a", "ogg", "oga", "opus", "wav", "flac", "wma", "amr", "aiff", "aif",
            "alac", "ape", "mid", "midi", "au", "ra", "ram", "dts", "ac3", "caf", "w64", "voc",
            "tta", "wv", "mpc", "spx", "gsm", "8svx",
        )
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "heic", "heif", "bmp", "tiff", "tif",
            "gifv", "apng", "ico", "jfif", "pjpeg", "pjp", "ppm", "pgm", "pbm", "pnm", "tga",
            "targa", "dng", "raw", "cr2", "nef", "orf", "arw", "rw2", "psd", "xcf", "exr", "hdr",
            "jp2", "jpx", "jxr", "wbmp",
        )
        private val NON_MEDIA_EXT = setOf(
            "html", "htm", "xhtml", "js", "mjs", "css", "json", "xml",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md",
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "exe", "dll", "so", "apk", "aab", "dmg", "pkg", "deb", "rpm",
            "woff", "woff2", "ttf", "otf",
        )
        private val MUSIC_HINTS = listOf("music", "song", "album", "track", "playlist", "audio-music")
        private val KNOWN_MUSIC_HOSTS = setOf(
            "music.youtube.com",
            "www.jiosaavn.com",
            "spotify.com",
            "www.spotify.com",
            "open.spotify.com",
            "soundcloud.com",
            "music.apple.com",
            "www.deezer.com",
            "tidal.com",
            "listen.tidal.com",
            "bandcamp.com",
        )

        /** Map a MIME type (e.g. from `WebView.setDownloadListener`) to a [MediaKind]. */
        fun mimeToKind(mime: String?): MediaKind? = when {
            mime == null -> null
            mime.startsWith("video/", ignoreCase = true) -> MediaKind.VIDEO
            mime.startsWith("image/", ignoreCase = true) -> MediaKind.IMAGE
            mime.startsWith("audio/", ignoreCase = true) -> MediaKind.AUDIO
            mime.equals("application/vnd.apple.mpegurl", ignoreCase = true) -> MediaKind.VIDEO
            mime.equals("application/dash+xml", ignoreCase = true) -> MediaKind.VIDEO
            else -> null
        }
    }
}
