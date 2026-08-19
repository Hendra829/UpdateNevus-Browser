package com.nevus.mediabridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.webkit.WebViewClientCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.nevus.mediabridge.download.FloatingBubbleService
import com.nevus.mediabridge.download.MediaKind
import com.nevus.mediabridge.download.MediaUrlDetector
import com.nevus.mediabridge.ui.DownloadManagerActivity
import com.nevus.mediabridge.ui.SettingsActivity
import com.nevus.mediabridge.util.NevusLog
import com.nevus.mediabridge.util.PerformanceTuner

/**
 * Single-activity host for the browser WebView plus the button that toggles the floating
 * download bubble.
 *
 * Responsibilities:
 *  - WebView setup routed through [PerformanceTuner] for a snappy default profile.
 *  - Media URL sniffing via [MediaUrlDetector] on every network resource — hits push into
 *    [FloatingBubbleService.notifyDetected] via a UI-thread post.
 *  - Overlay permission gating: the CTA button switches between "grant permission" and
 *    "start bubble" states based on `Settings.canDrawOverlays`.
 *  - Runtime `POST_NOTIFICATIONS` request on Android 13+ so the FGS notification renders.
 *  - Hardware back button navigates WebView history first, then falls back to activity finish.
 *  - Non-HTTPS/HTTP schemes handed off to the OS (mailto:, tel:, intent:, …) instead of being
 *    loaded into the WebView.
 *  - WebView save/restore across configuration changes and process death.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlField: TextInputEditText
    private lateinit var bubbleToggle: MaterialButton
    private lateinit var progress: ProgressBar
    private var reloadBtn: MaterialButton? = null
    private var backBtn: MaterialButton? = null
    private var forwardBtn: MaterialButton? = null
    private var historyBtn: MaterialButton? = null
    private var settingsBtn: MaterialButton? = null

    private val urlDetector = MediaUrlDetector()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            NevusLog.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlField = findViewById(R.id.urlField)
        bubbleToggle = findViewById(R.id.bubbleToggle)
        progress = findViewById(R.id.progress)
        reloadBtn = findViewById(R.id.reloadBtn)
        backBtn = findViewById(R.id.backBtn)
        forwardBtn = findViewById(R.id.forwardBtn)
        historyBtn = findViewById(R.id.historyBtn)
        settingsBtn = findViewById(R.id.settingsBtn)

        setupWebView()
        wireUrlField()
        wireBubbleButton()
        wireNavButtons()
        wireBackNavigation()
        maybeRequestNotificationPermission()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val start = intent?.dataString?.takeIf { it.isNotBlank() }
                ?: getString(R.string.default_start_url)
            load(start)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.takeIf { it.isNotBlank() }?.let(::load)
    }

    private fun setupWebView() {
        PerformanceTuner.tune(webView)
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                urlDetector.classify(url)?.let { kind ->
                    view.post {
                        FloatingBubbleService.notifyDetected(
                            applicationContext,
                            url,
                            kind,
                            referer = view.url,
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                val scheme = target.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                // Hand external schemes (mailto:, tel:, intent:, market:, geo:, …) to the OS.
                return runCatching {
                    val handoff = Intent(Intent.ACTION_VIEW, target).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (handoff.resolveActivity(packageManager) != null) {
                        startActivity(handoff)
                        true
                    } else {
                        Toast.makeText(this@MainActivity, R.string.no_handler_for_scheme, Toast.LENGTH_SHORT).show()
                        true
                    }
                }.getOrDefault(true)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { if (!urlField.hasFocus()) urlField.setText(it) }
                progress.isVisible = true
                updateNavButtons()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress.isVisible = false
                updateNavButtons()
                view?.evaluateJavascript(MEDIA_LONG_PRESS_JS, null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.isVisible = newProgress in 1..99
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                // Auto-deny — a browsing session should not leak GPS by default.
                callback?.invoke(origin, /* allow = */ false, /* retain = */ false)
            }
        }
        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            // Any HTML anchor with a download attribute (or a direct file url the browser can't
            // display) surfaces here — treat it exactly like a bubble-detected media hit.
            val guessedName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val kind = urlDetector.classify(url)
                ?: MediaUrlDetector.mimeToKind(mimetype)
                ?: return@setDownloadListener
            FloatingBubbleService.notifyDetected(applicationContext, url, kind, referer = webView.url)
            Toast.makeText(this, getString(R.string.download_queued, guessedName), Toast.LENGTH_SHORT).show()
        }
        wireImageLongPress()
        webView.addJavascriptInterface(MediaLongPressBridge(), JS_BRIDGE_NAME)
        NevusLog.i(TAG, "WebView engine: ${PerformanceTuner.currentEngineInfo(this)}")
    }

    /**
     * Long-press on any visible `<img>`/image-anchor offers "Simpan gambar" via WebView's own
     * hit-test — this catches images that never surface through [MediaUrlDetector]'s network
     * sniffing (e.g. a CSS `background-image`, or one already cached before this page load).
     */
    private fun wireImageLongPress() {
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val url = when (result.type) {
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> result.extra
                else -> null
            } ?: return@setOnLongClickListener false

            AlertDialog.Builder(this)
                .setMessage(R.string.save_image_prompt)
                .setPositiveButton(R.string.save_image_action) { _, _ ->
                    FloatingBubbleService.notifyDetected(applicationContext, url, MediaKind.IMAGE, referer = webView.url)
                    Toast.makeText(this, R.string.download_queued_generic, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.download_options_cancel, null)
                .show()
            true
        }
    }

    /**
     * WebView's native `hitTestResult` (used for [wireImageLongPress]) has no type for HTML5
     * `<video>`/`<audio>` elements, so long-press detection for those is done in-page via
     * injected JS + this bridge. The listener is `contextmenu` (fires on long-press in Android
     * WebView, same gesture as [wireImageLongPress]), not `click` — a plain tap has to keep
     * playing/pausing the media normally, not pop a download prompt on every tap.
     */
    private inner class MediaLongPressBridge {
        @JavascriptInterface
        fun onLongPress(url: String, tag: String) {
            // JS interface calls land on a worker thread, and the input is page-controlled —
            // validate the scheme ourselves rather than trusting it, same invariant the rest of
            // the download pipeline enforces.
            val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase()
            if (scheme != "http" && scheme != "https") return
            val kind = if (tag.equals("audio", ignoreCase = true)) MediaKind.AUDIO else MediaKind.VIDEO
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(if (kind == MediaKind.AUDIO) R.string.save_audio_prompt else R.string.save_video_prompt)
                    .setPositiveButton(R.string.save_image_action) { _, _ ->
                        FloatingBubbleService.notifyDetected(applicationContext, url, kind, referer = webView.url)
                        Toast.makeText(this@MainActivity, R.string.download_queued_generic, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.download_options_cancel, null)
                    .show()
            }
        }
    }

    private fun wireUrlField() {
        urlField.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                load(v.text.toString())
                hideKeyboard()
                true
            } else false
        }
    }

    private fun wireBubbleButton() {
        bubbleToggle.setOnClickListener { toggleBubble() }
    }

    private fun wireNavButtons() {
        backBtn?.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forwardBtn?.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        reloadBtn?.setOnClickListener { webView.reload() }
        historyBtn?.setOnClickListener { startActivity(Intent(this, DownloadManagerActivity::class.java)) }
        settingsBtn?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        updateNavButtons()
    }

    private fun updateNavButtons() {
        backBtn?.isEnabled = webView.canGoBack()
        forwardBtn?.isEnabled = webView.canGoForward()
    }

    private fun wireBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBubbleToggleLabel()
        webView.onResume()
        @Suppress("DEPRECATION")
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        @Suppress("DEPRECATION")
        webView.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        // Detach the WebView from its parent BEFORE destroy() — Chromium WebView will otherwise
        // log "detached from window before destroy" warnings and can leak.
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.settings.javaScriptEnabled = false
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private fun toggleBubble() {
        if (!canDrawOverlays()) {
            requestOverlayPermission()
            return
        }
        FloatingBubbleService.start(this)
        Toast.makeText(this, R.string.bubble_notification_title, Toast.LENGTH_SHORT).show()
        // Open the manager right away so tapping this button visibly does something — not just a
        // toast and a background service the user has no way to see the result of.
        startActivity(Intent(this, DownloadManagerActivity::class.java))
    }

    private fun refreshBubbleToggleLabel() {
        bubbleToggle.setText(
            if (canDrawOverlays()) R.string.start_bubble else R.string.grant_overlay_permission
        )
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { NevusLog.w(TAG, "Cannot open overlay-permission screen", it) }
        Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
    }

    private fun load(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("about:") -> trimmed
            trimmed.contains('.') && !trimmed.contains(' ') -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(trimmed)
        }
        webView.loadUrl(normalized)
        urlField.setText(normalized)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(urlField.windowToken, 0)
        urlField.clearFocus()
        webView.requestFocus(View.FOCUS_DOWN)
    }

    private companion object {
        const val TAG = "MainActivity"
        const val JS_BRIDGE_NAME = "NevusMediaBridge"

        // Re-injected on every onPageFinished — cheap, idempotent (the __nevusBound marker
        // skips elements already wired), and covers SPA-added <video>/<audio> via the observer.
        const val MEDIA_LONG_PRESS_JS = """
            (function() {
                function resolveSrc(el) {
                    if (el.currentSrc) return el.currentSrc;
                    if (el.src) return el.src;
                    var source = el.querySelector('source[src]');
                    return source ? source.src : null;
                }
                function attach(el) {
                    if (el.__nevusBound) return;
                    el.__nevusBound = true;
                    el.addEventListener('contextmenu', function(ev) {
                        var url = resolveSrc(el);
                        if (!url) return;
                        ev.preventDefault();
                        window.$JS_BRIDGE_NAME.onLongPress(url, el.tagName.toLowerCase());
                    });
                }
                function scan() {
                    document.querySelectorAll('video, audio').forEach(attach);
                }
                scan();
                if (!window.__nevusObserverBound) {
                    window.__nevusObserverBound = true;
                    new MutationObserver(scan).observe(document.documentElement, {childList: true, subtree: true});
                }
            })();
        """
    }
}
