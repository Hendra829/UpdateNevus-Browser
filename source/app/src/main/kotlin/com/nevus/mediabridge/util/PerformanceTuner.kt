package com.nevus.mediabridge.util

import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Runtime tuning for a snappy browser experience — applied once per WebView instance.
 *
 * The single largest win comes from *not blocking the main thread*; the rest is disabling
 * legacy pessimizations and enabling all the modern rendering paths.
 */
object PerformanceTuner {

    /** Applied to every WebView the app creates. Safe to call multiple times. */
    fun tune(webView: WebView) {
        val s = webView.settings
        s.applySnappyDefaults()

        // Hardware acceleration is on by default in modern SDKs, but be explicit — an OEM ROM
        // may have flipped it to software for the launcher WebView instance.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // A generation-old caveat: `setRenderPriority` is deprecated but respected by the
        // legacy WebView backend; new Chromium ignores it silently, so it's safe.
        @Suppress("DEPRECATION")
        s.setRenderPriority(WebSettings.RenderPriority.HIGH)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(s, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, true)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    fun currentEngineInfo(context: Context): String {
        val pkg = WebViewCompat.getCurrentWebViewPackage(context) ?: return "unknown WebView engine"
        return "${pkg.packageName} ${pkg.versionName}"
    }

    private fun WebSettings.applySnappyDefaults() {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true
        useWideViewPort = true
        loadWithOverviewMode = true
        mediaPlaybackRequiresUserGesture = false  // let auto-play work for embedded video where allowed
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = true

        // Prefer network freshness, fall back to cache when offline — feels fastest for a browser.
        cacheMode = WebSettings.LOAD_DEFAULT
        // Standards mode; quirks-only pages are rare and the perf cost is negligible.
        setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL)
        // Never allow mixed content on modern Android — HTTPS-only.
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // On modern Android these are already true by default; force it in case a device-vendor build flipped them.
        allowContentAccess = false
        allowFileAccess = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }
    }
}
