package com.rainax.vplay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class VideoAdapter(
    private val onFolder: (Row.Folder) -> Unit,
    private val onVideo: (VideoItem) -> Unit,
    private val progressOf: (VideoItem) -> Int
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Folder) TYPE_FOLDER else TYPE_VIDEO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            FolderHolder(inflater.inflate(R.layout.item_folder, parent, false))
        } else {
            VideoHolder(inflater.inflate(R.layout.item_video, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Folder -> (holder as FolderHolder).bind(row)
            is Row.Video -> (holder as VideoHolder).bind(row.item)
        }
    }

    inner class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val duration: TextView = view.findViewById(R.id.duration)
        private val progress: ProgressBar = view.findViewById(R.id.progress)
        private val title: TextView = view.findViewById(R.id.title)
        private val meta: TextView = view.findViewById(R.id.meta)

        fun bind(item: VideoItem) {
            title.text = item.title
            duration.text = Fmt.time(item.durationMs)

            val resolution = if (item.width > 0 && item.height > 0) {
                "  •  ${item.width}×${item.height}"
            } else ""
            meta.text = Fmt.size(item.sizeBytes) + resolution

            val pct = progressOf(item)
            if (pct in 1..99) {
                progress.progress = pct
                progress.visibility = View.VISIBLE
            } else {
                progress.visibility = View.GONE
            }

            Glide.with(thumb).load(item.uri).centerCrop().into(thumb)
            itemView.setOnClickListener { onVideo(item) }
        }
    }

    inner class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.folderThumb)
        private val name: TextView = view.findViewById(R.id.folderName)
        private val count: TextView = view.findViewById(R.id.folderCount)

        fun bind(folder: Row.Folder) {
            name.text = folder.name
            count.text = itemView.context.resources
                .getQuantityString(R.plurals.videos_count, folder.count, folder.count)
            Glide.with(thumb).load(folder.thumb).centerCrop().into(thumb)
            itemView.setOnClickListener { onFolder(folder) }
        }
    }

    private companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_VIDEO = 1
    }
}
