package com.nevus.mediabridge.util

import android.content.Context

/**
 * Small SharedPreferences wrapper for the handful of user-facing toggles exposed in
 * [com.nevus.mediabridge.ui.SettingsActivity]. Deliberately flat — no DataStore/Proto dependency
 * for three booleans and an int.
 */
class NevusSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Target height (px) applied by the enhancement resize step. Null = keep source size. */
    var defaultTargetHeight: Int?
        get() = prefs.getInt(KEY_TARGET_HEIGHT, NO_HEIGHT).takeIf { it != NO_HEIGHT }
        set(value) = prefs.edit().putInt(KEY_TARGET_HEIGHT, value ?: NO_HEIGHT).apply()

    /** Whether the "Tingkatkan kualitas" checkbox is pre-checked in the download picker. */
    var enhanceByDefault: Boolean
        get() = prefs.getBoolean(KEY_ENHANCE_DEFAULT, false)
        set(value) = prefs.edit().putBoolean(KEY_ENHANCE_DEFAULT, value).apply()

    /** Larger chunk size + shorter timeouts for [com.nevus.mediabridge.download.DownloadEngine]. */
    var fastConnectionMode: Boolean
        get() = prefs.getBoolean(KEY_FAST_CONNECTION, false)
        set(value) = prefs.edit().putBoolean(KEY_FAST_CONNECTION, value).apply()

    /**
     * Download Mbps from the last [com.nevus.mediabridge.util.NetworkSpeedTest] run, if any —
     * used to scale how many parallel segments "Unduh cepat" opens (see
     * [com.nevus.mediabridge.download.DownloadEngine.enqueueSegmented]'s caller). Null until the
     * user has actually run a speed test at least once; never guessed.
     */
    var lastMeasuredDownloadMbps: Float?
        get() = prefs.getFloat(KEY_LAST_DOWNLOAD_MBPS, NO_MBPS).takeIf { it != NO_MBPS }
        set(value) = prefs.edit().putFloat(KEY_LAST_DOWNLOAD_MBPS, value ?: NO_MBPS).apply()

    companion object {
        private const val PREFS_NAME = "nevus_settings"
        private const val KEY_TARGET_HEIGHT = "default_target_height"
        private const val KEY_ENHANCE_DEFAULT = "enhance_by_default"
        private const val KEY_FAST_CONNECTION = "fast_connection_mode"
        private const val KEY_LAST_DOWNLOAD_MBPS = "last_download_mbps"
        private const val NO_HEIGHT = -1
        private const val NO_MBPS = -1f

        const val HEIGHT_720P = 720
        const val HEIGHT_1080P = 1080
        const val HEIGHT_1440P = 1440
    }
}
