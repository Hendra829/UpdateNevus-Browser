package com.nevus.mediabridge

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.webkit.WebViewClientCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.nevus.mediabridge.download.FloatingBubbleService
import com.nevus.mediabridge.download.MediaUrlDetector
import com.nevus.mediabridge.util.NevusLog
import com.nevus.mediabridge.util.PerformanceTuner

/**
 * Single-activity host for the browser WebView plus the button that toggles the floating
 * download bubble.
 *
 * Responsibilities:
 *  - WebView setup routed through [PerformanceTuner] for a snappy default profile.
 *  - Media URL sniffing via [MediaUrlDetector] on every network resource the WebView requests
 *    — hits push into [FloatingBubbleService.notifyDetected].
 *  - Overlay permission gating: the CTA button switches between "grant permission" and
 *    "start bubble" states based on `Settings.canDrawOverlays`.
 *  - Hardware back button navigates WebView history first, then falls back to activity finish.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlField: TextInputEditText
    private lateinit var bubbleToggle: MaterialButton
    private lateinit var progress: ProgressBar

    private val urlDetector = MediaUrlDetector()

    @SuppressLint("SetJavaScriptEnabled")  // enabled deliberately via PerformanceTuner
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlField = findViewById(R.id.urlField)
        bubbleToggle = findViewById(R.id.bubbleToggle)
        progress = findViewById(R.id.progress)

        setupWebView()
        wireUrlField()
        wireBubbleButton()
        wireBackNavigation()

        val start = intent?.dataString?.takeIf { it.isNotBlank() } ?: getString(R.string.default_start_url)
        load(start)
    }

    private fun setupWebView() {
        PerformanceTuner.tune(webView)
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                urlDetector.classify(url)?.let { kind ->
                    // Off-load the notify call from the network thread to the main thread — it
                    // touches StateFlow and starts a foreground service.
                    view.post { FloatingBubbleService.notifyDetected(applicationContext, url, kind) }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { urlField.setText(it) }
                progress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress.isVisible = false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.isVisible = newProgress in 1..99
            }
        }
        NevusLog.i(TAG, "WebView engine: ${PerformanceTuner.currentEngineInfo(this)}")
    }

    private fun wireUrlField() {
        urlField.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                load(v.text.toString())
                true
            } else false
        }
    }

    private fun wireBubbleButton() {
        bubbleToggle.setOnClickListener { toggleBubble() }
    }

    private fun wireBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshBubbleToggleLabel()
    }

    private fun toggleBubble() {
        if (!canDrawOverlays()) {
            requestOverlayPermission()
            return
        }
        FloatingBubbleService.start(this)
        Toast.makeText(this, R.string.bubble_notification_title, Toast.LENGTH_SHORT).show()
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
        startActivity(intent)
        Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
    }

    private fun load(input: String) {
        val normalized = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains('.') && !input.contains(' ') -> "https://$input"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(input)
        }
        webView.loadUrl(normalized)
        urlField.setText(normalized)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
