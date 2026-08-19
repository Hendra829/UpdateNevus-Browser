package com.nevus.mediabridge.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nevus.mediabridge.R
import com.nevus.mediabridge.download.DownloadHistoryEntry
import java.text.DateFormat
import java.util.Date

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<DownloadHistoryEntry> = emptyList()

    fun submit(newItems: List<DownloadHistoryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val statusIcon: TextView = itemView.findViewById(R.id.historyStatusIcon)
        private val fileName: TextView = itemView.findViewById(R.id.historyFileName)
        private val subtitle: TextView = itemView.findViewById(R.id.historySubtitle)

        fun bind(entry: DownloadHistoryEntry) {
            val ok = entry.status == DownloadHistoryEntry.Status.COMPLETED
            statusIcon.text = if (ok) "✓" else "✕"
            fileName.text = entry.fileName
            val when_ = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestampMs))
            subtitle.text = if (ok) {
                "${entry.kind} • ${entry.bytesWritten / 1024} KB • $when_"
            } else {
                "${entry.kind} • ${entry.failureMessage ?: "gagal"} • $when_"
            }
        }
    }
}
