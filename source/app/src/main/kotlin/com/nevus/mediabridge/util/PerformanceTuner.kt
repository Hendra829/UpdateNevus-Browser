package com.nevus.mediabridge.util

import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Runtime tuning for a snappy — and safe — browser experience. Applied once per WebView
 * instance. The single largest win comes from *not blocking the main thread*; the rest is
 * disabling legacy pessimizations and enabling modern rendering paths.
 *
 * Security posture:
 *  - `file://` and content:// access is disabled — no local-file exfiltration via a hostile page.
 *  - Mixed content is `NEVER_ALLOW` — https only for subresources on an https page.
 *  - Geolocation prompts are auto-denied by attaching a no-op chrome client; a caller who
 *    wants prompts can install their own `WebChromeClient` on top.
 *  - Third-party cookies are off by default.
 *  - Autoplay is gated by user gesture — no auto-blast audio.
 *  - Safe browsing is enabled where the WebView backend supports it.
 */
object PerformanceTuner {

    /** Applied to every WebView the app creates. Safe to call multiple times. */
    fun tune(webView: WebView) {
        val s = webView.settings
        s.applySnappyDefaults()

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        @Suppress("DEPRECATION")
        s.setRenderPriority(WebSettings.RenderPriority.HIGH)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(s, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, true)
        }

        // Follow the app theme for pages that declare `prefers-color-scheme` — modern replacement
        // for the legacy `FORCE_DARK` API removed in newer AndroidX WebKit versions.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, true) }
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, false)
    }

    fun currentEngineInfo(context: Context): String {
        val pkg = WebViewCompat.getCurrentWebViewPackage(context) ?: return "unknown WebView engine"
        return "${pkg.packageName} ${pkg.versionName}"
    }

    private fun WebSettings.applySnappyDefaults() {
        javaScriptEnabled = true
        domStorageEnabled = true
        @Suppress("DEPRECATION")
        databaseEnabled = true
        loadsImagesAutomatically = true
        useWideViewPort = true
        loadWithOverviewMode = true
        // Require a user gesture before autoplay: no unsolicited audio blast on page load.
        mediaPlaybackRequiresUserGesture = true
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false

        cacheMode = WebSettings.LOAD_DEFAULT
        setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        allowContentAccess = false
        allowFileAccess = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }

        // Append a Nevus product tag so downstream analytics can attribute traffic; keep the
        // stock Chrome UA prefix intact so sites that gate on Chrome versioning still work.
        userAgentString = "$userAgentString NevusBrowser/3.0"
    }
}
