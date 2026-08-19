package com.nevus.mediabridge.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.Toast
import com.nevus.mediabridge.R
import com.nevus.mediabridge.download.DownloadHistoryStore
import com.nevus.mediabridge.download.FloatingBubbleService
import com.nevus.mediabridge.media.KenBurnsAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows what the floating bubble is doing: media detected but not yet queued (tap to configure
 * via [DownloadOptionsDialog]), downloads currently in flight, and past results — the screen
 * that was missing when "Aktifkan gelembung unduhan" used to just toast and do nothing visible.
 */
class DownloadManagerActivity : AppCompatActivity() {

    private lateinit var pendingAdapter: PendingAdapter
    private lateinit var activeAdapter: ActiveDownloadAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private lateinit var activeEmptyLabel: View
    private lateinit var historyEmptyLabel: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_manager)

        findViewById<View>(R.id.managerBackBtn).setOnClickListener { finish() }
        activeEmptyLabel = findViewById(R.id.activeEmptyLabel)
        historyEmptyLabel = findViewById(R.id.historyEmptyLabel)

        pendingAdapter = PendingAdapter { detection -> DownloadOptionsDialog.show(this, lifecycleScope, detection) }
        findViewById<RecyclerView>(R.id.activeList).apply {
            layoutManager = LinearLayoutManager(this@DownloadManagerActivity)
            adapter = pendingAdapter
        }

        activeAdapter = ActiveDownloadAdapter(
            onPause = { txId -> FloatingBubbleService.requestCancel(txId, discard = false) },
            onCancel = { txId -> FloatingBubbleService.requestCancel(txId, discard = true) },
        )
        findViewById<RecyclerView>(R.id.progressList).apply {
            layoutManager = LinearLayoutManager(this@DownloadManagerActivity)
            adapter = activeAdapter
        }

        historyAdapter = HistoryAdapter(
            onDelete = { entry -> deleteHistoryEntry(entry.txId) },
            onMakeSticker = { entry -> entry.outputPath?.let { StickerChoiceDialog.show(this, lifecycleScope, it) } },
            onMakeAnimation = { entry -> entry.outputPath?.let { makeAnimation(it) } },
        )
        findViewById<RecyclerView>(R.id.historyList).apply {
            layoutManager = LinearLayoutManager(this@DownloadManagerActivity)
            adapter = historyAdapter
        }
        findViewById<View>(R.id.deleteAllBtn).setOnClickListener { confirmDeleteAllHistory() }

        observeLiveState()
    }

    override fun onResume() {
        super.onResume()
        reloadHistory()
    }

    private fun observeLiveState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    FloatingBubbleService.pendingDetections.collect { list ->
                        pendingAdapter.submit(list)
                        activeEmptyLabel.visibility = if (list.isEmpty() && activeAdapter.itemCount == 0) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    FloatingBubbleService.activeDownloads.collect { map ->
                        activeAdapter.submit(map)
                        activeEmptyLabel.visibility =
                            if (map.isEmpty() && FloatingBubbleService.pendingDetections.value.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun reloadHistory() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                DownloadHistoryStore(File(applicationContext.filesDir, "state")).readAll()
            }
            historyAdapter.submit(entries)
            historyEmptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun deleteHistoryEntry(txId: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                DownloadHistoryStore(File(applicationContext.filesDir, "state")).remove(txId)
            }
            reloadHistory()
        }
    }

    private fun makeAnimation(inputPath: String) {
        lifecycleScope.launch {
            val input = File(inputPath)
            val outputPath = File(input.parentFile, "${input.nameWithoutExtension}-animated.gif").absolutePath
            val ok = withContext(Dispatchers.Default) { KenBurnsAnimator.generate(inputPath, outputPath) }
            val message = if (ok) {
                getString(R.string.download_done, File(outputPath).name)
            } else {
                getString(R.string.download_failed, File(inputPath).name)
            }
            Toast.makeText(this@DownloadManagerActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteAllHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.download_manager_delete_all_title)
            .setMessage(R.string.download_manager_delete_all_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        DownloadHistoryStore(File(applicationContext.filesDir, "state")).clear()
                    }
                    reloadHistory()
                }
            }
            .setNegativeButton(R.string.download_options_cancel, null)
            .show()
    }
}
