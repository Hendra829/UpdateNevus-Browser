package com.nevus.mediabridge.download

import android.net.Uri

/**
 * Classifies URLs into one of the four supported [MediaKind]s. Returns `null` for anything
 * that does not clearly look like a video, audio, image, or music resource — the bubble stays
 * quiet on HTML pages, scripts, style sheets, archives, executables, or unknown types.
 *
 * Strategy — fast path on extension first, then a small set of well-known streaming
 * containers and music-platform host hints. No network requests.
 */
class MediaUrlDetector {

    fun classify(url: String): MediaKind? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")

        // Fast reject: obvious non-media types.
        if (extension in NON_MEDIA_EXT) return null

        // Extension → kind.
        val byExt: MediaKind? = when (extension) {
            in VIDEO_EXT -> MediaKind.VIDEO
            in IMAGE_EXT -> MediaKind.IMAGE
            in AUDIO_EXT -> if (looksLikeMusic(uri, host, path)) MediaKind.MUSIC else MediaKind.AUDIO
            else -> null
        }
        if (byExt != null) return byExt

        // Fallback: streaming manifest containers with query-embedded media hints.
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

    companion object {
        // Deliberately conservative: only kinds we can name/save/categorise.
        private val VIDEO_EXT = setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "ts", "mpg", "mpeg", "flv", "wmv",
        )
        private val AUDIO_EXT = setOf(
            "mp3", "aac", "m4a", "ogg", "oga", "opus", "wav", "flac", "wma", "amr",
        )
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "svg", "avif", "heic", "heif", "bmp", "tiff", "tif",
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
        )
    }
}
