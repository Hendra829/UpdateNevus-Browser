package com.nevus.mediabridge.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nevus.mediabridge.R
import com.nevus.mediabridge.download.FloatingBubbleService

/** Detected-but-not-yet-downloaded media URLs — tap a row to open [DownloadOptionsDialog]. */
class PendingAdapter(
    private val onTap: (FloatingBubbleService.Detection) -> Unit,
) : RecyclerView.Adapter<PendingAdapter.ViewHolder>() {

    private var items: List<FloatingBubbleService.Detection> = emptyList()

    fun submit(newItems: List<FloatingBubbleService.Detection>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pending, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], onTap)

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val glyph: TextView = itemView.findViewById(R.id.pendingGlyph)
        private val url: TextView = itemView.findViewById(R.id.pendingUrl)
        private val downloadBtn: android.view.View = itemView.findViewById(R.id.pendingDownloadBtn)

        fun bind(detection: FloatingBubbleService.Detection, onTap: (FloatingBubbleService.Detection) -> Unit) {
            glyph.text = detection.kind.glyph
            url.text = detection.url
            val click = { onTap(detection) }
            itemView.setOnClickListener { click() }
            downloadBtn.setOnClickListener { click() }
        }
    }
}
