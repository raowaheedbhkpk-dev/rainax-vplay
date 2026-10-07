package com.rainax.vplay

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

object VideoRepository {

    fun load(context: Context): List<VideoItem> {
        val list = ArrayList<VideoItem>()

        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    list.add(
                        VideoItem(
                            id = id,
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                id
                            ),
                            title = c.getString(nameCol) ?: "Video $id",
                            durationMs = c.getLong(durCol),
                            sizeBytes = c.getLong(sizeCol),
                            folder = c.getString(bucketCol) ?: "Unknown",
                            dateAdded = c.getLong(dateCol),
                            width = c.getInt(wCol),
                            height = c.getInt(hCol)
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Permission revoked while loading; return what we have.
        }

        return list
    }
}
