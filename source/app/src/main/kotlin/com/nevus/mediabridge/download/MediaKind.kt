package com.nevus.mediabridge.download

import android.os.Environment

/**
 * Kinds of media the floating download bubble handles. Deliberately narrow: video, audio,
 * image, music — no HTML/JS/exe/archive to keep the bubble a *media* download and not a
 * general download manager.
 */
enum class MediaKind(
    /** Directory under `Environment` where files of this kind live natively. */
    val standardDir: String,
    /** Sub-folder under app's external files dir. */
    val subDir: String,
    /** Emoji glyph shown in the bubble badge. */
    val glyph: String,
) {
    VIDEO(Environment.DIRECTORY_MOVIES, "Video", "▶"),
    AUDIO(Environment.DIRECTORY_PODCASTS, "Audio", "♪"),
    IMAGE(Environment.DIRECTORY_PICTURES, "Image", "◈"),
    MUSIC(Environment.DIRECTORY_MUSIC, "Music", "♫"),
    ;

    /** Rough MIME family for hints to the system MediaScanner. */
    fun mimeTypeFor(extension: String): String {
        val ext = extension.lowercase()
        return when (this) {
            VIDEO -> when (ext) {
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "m3u8" -> "application/vnd.apple.mpegurl"
                "mpd" -> "application/dash+xml"
                else -> "video/mp4"
            }
            AUDIO, MUSIC -> when (ext) {
                "aac" -> "audio/aac"
                "flac" -> "audio/flac"
                "ogg", "opus" -> "audio/ogg"
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                else -> "audio/mpeg"
            }
            IMAGE -> when (ext) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "avif" -> "image/avif"
                "heic", "heif" -> "image/heif"
                else -> "image/jpeg"
            }
        }
    }
}
