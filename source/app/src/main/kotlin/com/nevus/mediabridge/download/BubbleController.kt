package com.nevus.mediabridge.download

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.nevus.mediabridge.R
import com.nevus.mediabridge.util.NevusLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws, positions, and drives touch on the floating download bubble overlay.
 *
 * The bubble is a single tap surface:
 *  - **Tap** → invokes [onTap] (bubble service opens the pending-media picker).
 *  - **Drag** → moves the bubble around; on release it snaps to the nearest screen edge.
 *  - **Badge** → shows the count of pending media (video/audio/image/music) detected but not
 *    yet dismissed.
 *
 * Uses `TYPE_APPLICATION_OVERLAY` (Android 8+) which requires the SYSTEM_ALERT_WINDOW
 * permission; the caller must gate creation on `Settings.canDrawOverlays(context)`.
 *
 * Rotation-safe: registers a [ComponentCallbacks] and re-snaps to the nearest edge whenever the
 * configuration changes, so the bubble never gets marooned off-screen after a rotation.
 */
class BubbleController(
    private val context: Context,
    private val onTap: () -> Unit,
) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val root: View = LayoutInflater.from(context).inflate(R.layout.view_floating_bubble, null, false)
    private val badgeView: TextView = root.findViewById(R.id.bubbleBadge)
    private val glyphView: TextView = root.findViewById(R.id.bubbleGlyph)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val dm = context.resources.displayMetrics
        x = dm.widthPixels - dip(72)
        y = dm.heightPixels / 3
    }

    private var attached = false
    private var pendingCount = 0
    private var topGlyphKind: MediaKind? = null

    private val configListener = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (attached) snapToNearestEdge()
        }

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    fun attach() {
        if (attached) return
        attachTouchHandler()
        try {
            windowManager.addView(root, params)
            attached = true
            root.contentDescription = context.getString(R.string.bubble_content_description)
            renderBadge()
            context.registerComponentCallbacks(configListener)
        } catch (t: Throwable) {
            NevusLog.e(TAG, "Failed to attach bubble overlay", t)
        }
    }

    fun detach() {
        if (!attached) return
        runCatching { context.unregisterComponentCallbacks(configListener) }
        try {
            windowManager.removeView(root)
        } catch (t: Throwable) {
            NevusLog.w(TAG, "detach: removeView threw", t)
        }
        attached = false
    }

    fun setPendingCount(count: Int, topKind: MediaKind?) {
        pendingCount = max(0, count)
        topGlyphKind = topKind
        renderBadge()
    }

    private fun renderBadge() {
        badgeView.visibility = if (pendingCount > 0) View.VISIBLE else View.GONE
        badgeView.text = if (pendingCount > 99) "99+" else pendingCount.toString()
        glyphView.text = topGlyphKind?.glyph ?: "↓"
        root.contentDescription = context.getString(
            R.string.bubble_content_description_count,
            pendingCount,
        )
    }

    private fun attachTouchHandler() {
        val touchSlop = dip(6).toFloat()
        var startX = 0f
        var startY = 0f
        var startWinX = 0
        var startWinY = 0
        var dragged = false

        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    startWinX = params.x
                    startWinY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragged = true
                    if (dragged) {
                        params.x = (startWinX + dx).toInt()
                        params.y = (startWinY + dy).toInt()
                        safeUpdateLayout()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) snapToNearestEdge() else onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun snapToNearestEdge() {
        val dm: DisplayMetrics = context.resources.displayMetrics
        val bubbleWidth = root.width.takeIf { it > 0 } ?: dip(60)
        val bubbleHeight = root.height.takeIf { it > 0 } ?: dip(60)
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        params.x = if (params.x + bubbleWidth / 2 < screenWidth / 2) 0 else screenWidth - bubbleWidth
        // Leave 24dp margin from top/bottom so the bubble never lands under the status bar.
        val marginY = dip(24)
        params.y = min(max(marginY, params.y), screenHeight - bubbleHeight - marginY)
        safeUpdateLayout()
    }

    private fun safeUpdateLayout() {
        if (!attached) return
        try {
            windowManager.updateViewLayout(root, params)
        } catch (t: Throwable) {
            NevusLog.w(TAG, "updateViewLayout failed", t)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dip(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TAG = "BubbleController"
    }
}
