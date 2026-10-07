package com.rainax.vplay

import android.net.Uri

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String,
    val dateAdded: Long,
    val width: Int,
    val height: Int
)

sealed class Row {
    data class Folder(val name: String, val count: Int, val thumb: Uri) : Row()
    data class Video(val item: VideoItem) : Row()
}

enum class SortMode { NAME, DATE, SIZE, DURATION }
