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
    private val onPlaylist: (Row.Playlist) -> Unit,
    private val onPlaylistLong: (Row.Playlist) -> Unit,
    private val onVideo: (VideoItem) -> Unit,
    private val onVideoLong: (VideoItem) -> Unit,
    private val progressOf: (VideoItem) -> Int,
    private val isFavourite: (VideoItem) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Folder -> TYPE_FOLDER
        is Row.Playlist -> TYPE_PLAYLIST
        is Row.Video -> TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            VideoHolder(inflater.inflate(R.layout.item_video, parent, false))
        } else {
            FolderHolder(inflater.inflate(R.layout.item_folder, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Folder -> (holder as FolderHolder).bindFolder(row)
            is Row.Playlist -> (holder as FolderHolder).bindPlaylist(row)
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
            val star = if (isFavourite(item)) "★  " else ""
            meta.text = star + Fmt.size(item.sizeBytes) + resolution

            val pct = progressOf(item)
            if (pct in 1..99) {
                progress.progress = pct
                progress.visibility = View.VISIBLE
            } else {
                progress.visibility = View.GONE
            }

            Glide.with(thumb).load(item.uri).centerCrop().into(thumb)
            itemView.setOnClickListener { onVideo(item) }
            itemView.setOnLongClickListener {
                onVideoLong(item)
                true
            }
        }
    }

    inner class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.folderThumb)
        private val icon: ImageView = view.findViewById(R.id.folderIcon)
        private val name: TextView = view.findViewById(R.id.folderName)
        private val count: TextView = view.findViewById(R.id.folderCount)

        fun bindFolder(folder: Row.Folder) {
            name.text = folder.name
            count.text = itemView.context.resources
                .getQuantityString(R.plurals.videos_count, folder.count, folder.count)
            icon.setImageResource(R.drawable.ic_folder)
            Glide.with(thumb).load(folder.thumb).centerCrop().into(thumb)
            itemView.setOnClickListener { onFolder(folder) }
            itemView.setOnLongClickListener(null)
            itemView.isLongClickable = false
        }

        fun bindPlaylist(playlist: Row.Playlist) {
            name.text = playlist.name
            count.text = itemView.context.resources
                .getQuantityString(R.plurals.videos_count, playlist.count, playlist.count)
            icon.setImageResource(R.drawable.ic_playlist)
            if (playlist.thumb != null) {
                Glide.with(thumb).load(playlist.thumb).centerCrop().into(thumb)
            } else {
                Glide.with(thumb).clear(thumb)
                thumb.setImageDrawable(null)
            }
            itemView.setOnClickListener { onPlaylist(playlist) }
            itemView.setOnLongClickListener {
                onPlaylistLong(playlist)
                true
            }
        }
    }

    private companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_VIDEO = 1
        const val TYPE_PLAYLIST = 2
    }
}
