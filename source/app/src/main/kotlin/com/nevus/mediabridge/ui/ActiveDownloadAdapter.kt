package com.nevus.mediabridge.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nevus.mediabridge.R
import com.nevus.mediabridge.download.DownloadEvent

/** Downloads currently in flight, keyed by txId, driven by [com.nevus.mediabridge.download.FloatingBubbleService.activeDownloads]. */
class ActiveDownloadAdapter(
    private val onPause: (String) -> Unit,
    private val onCancel: (String) -> Unit,
) : RecyclerView.Adapter<ActiveDownloadAdapter.ViewHolder>() {

    private var items: List<Pair<String, DownloadEvent>> = emptyList()

    fun submit(newItems: Map<String, DownloadEvent>) {
        items = newItems.entries.map { it.key to it.value }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_progress, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], onPause, onCancel)

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val label: TextView = itemView.findViewById(R.id.progressLabel)
        private val bar: LinearProgressIndicator = itemView.findViewById(R.id.progressBar)
        private val pauseBtn: View = itemView.findViewById(R.id.pauseBtn)
        private val cancelBtn: View = itemView.findViewById(R.id.cancelBtn)

        fun bind(pair: Pair<String, DownloadEvent>, onPause: (String) -> Unit, onCancel: (String) -> Unit) {
            val (txId, event) = pair
            val bytesRead = when (event) {
                is DownloadEvent.Progress -> event.bytesRead
                is DownloadEvent.Started -> 0L
                else -> 0L
            }
            val total = when (event) {
                is DownloadEvent.Progress -> event.totalBytes
                is DownloadEvent.Started -> event.expectedBytes
                else -> null
            }
            label.text = if (total != null) {
                "$txId — ${bytesRead / 1024} KB / ${total / 1024} KB"
            } else {
                "$txId — ${bytesRead / 1024} KB"
            }
            if (total != null && total > 0) {
                bar.isIndeterminate = false
                bar.max = 100
                bar.setProgressCompat((bytesRead * 100 / total).toInt().coerceIn(0, 100), true)
            } else {
                bar.isIndeterminate = true
            }
            pauseBtn.setOnClickListener { onPause(txId) }
            cancelBtn.setOnClickListener { onCancel(txId) }
        }
    }
}
