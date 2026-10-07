package com.rainax.vplay

import android.content.Context
import java.util.Locale

object Fmt {
    fun time(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}

/** Remembers where the user stopped watching each video. */
class PositionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun get(key: String): Long = prefs.getLong(key, 0L)

    fun put(key: String, positionMs: Long) {
        prefs.edit().putLong(key, positionMs).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }
}
