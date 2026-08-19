package com.nevus.mediabridge.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nevus.mediabridge.R
import com.nevus.mediabridge.download.DownloadHistoryEntry
import com.nevus.mediabridge.download.MediaKind
import java.text.DateFormat
import java.util.Date

class HistoryAdapter(
    private val onDelete: (DownloadHistoryEntry) -> Unit,
    private val onMakeSticker: (DownloadHistoryEntry) -> Unit,
    private val onMakeAnimation: (DownloadHistoryEntry) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private var items: List<DownloadHistoryEntry> = emptyList()

    fun submit(newItems: List<DownloadHistoryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(items[position], onDelete, onMakeSticker, onMakeAnimation)

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val statusIcon: TextView = itemView.findViewById(R.id.historyStatusIcon)
        private val fileName: TextView = itemView.findViewById(R.id.historyFileName)
        private val subtitle: TextView = itemView.findViewById(R.id.historySubtitle)
        private val deleteBtn: View = itemView.findViewById(R.id.historyDeleteBtn)
        private val imageActions: View = itemView.findViewById(R.id.historyImageActions)
        private val stickerBtn: View = itemView.findViewById(R.id.historyStickerBtn)
        private val animateBtn: View = itemView.findViewById(R.id.historyAnimateBtn)

        fun bind(
            entry: DownloadHistoryEntry,
            onDelete: (DownloadHistoryEntry) -> Unit,
            onMakeSticker: (DownloadHistoryEntry) -> Unit,
            onMakeAnimation: (DownloadHistoryEntry) -> Unit,
        ) {
            val ok = entry.status == DownloadHistoryEntry.Status.COMPLETED
            statusIcon.text = if (ok) "✓" else "✕"
            fileName.text = entry.fileName
            val when_ = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestampMs))
            subtitle.text = if (ok) {
                "${entry.kind} • ${entry.bytesWritten / 1024} KB • $when_"
            } else {
                "${entry.kind} • ${entry.failureMessage ?: "gagal"} • $when_"
            }
            deleteBtn.setOnClickListener { onDelete(entry) }

            imageActions.visibility = if (ok && entry.kind == MediaKind.IMAGE) View.VISIBLE else View.GONE
            stickerBtn.setOnClickListener { onMakeSticker(entry) }
            animateBtn.setOnClickListener { onMakeAnimation(entry) }
        }
    }
}
