package com.rainax.vplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Favourites, watch history and playlists. Videos are referenced by their uri string. */
class LibraryStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("library", Context.MODE_PRIVATE)

    // ---- favourites

    fun favourites(): Set<String> = sp.getStringSet("fav", emptySet()) ?: emptySet()

    fun isFavourite(uri: String): Boolean = favourites().contains(uri)

    /** Returns true if the video is now a favourite. */
    fun toggleFavourite(uri: String): Boolean {
        val set = HashSet<String>(favourites())
        val added: Boolean
        if (set.contains(uri)) {
            set.remove(uri)
            added = false
        } else {
            set.add(uri)
            added = true
        }
        sp.edit().putStringSet("fav", set).apply()
        return added
    }

    // ---- history (most recent first)

    fun history(): List<String> {
        val raw = sp.getString("history", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(uri: String) {
        val list = ArrayList<String>(history())
        list.remove(uri)
        list.add(0, uri)
        while (list.size > 100) list.removeAt(list.size - 1)
        sp.edit().putString("history", JSONArray(list).toString()).apply()
    }

    fun clearHistory() {
        sp.edit().putString("history", "[]").apply()
    }

    // ---- playlists

    fun playlists(): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        try {
            val obj = JSONObject(sp.getString("playlists", "{}") ?: "{}")
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val arr = obj.getJSONArray(name)
                val list = ArrayList<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                out[name] = list
            }
        } catch (e: Exception) {
            // corrupted data: start fresh
        }
        return out
    }

    private fun savePlaylists(map: Map<String, List<String>>) {
        val obj = JSONObject()
        for ((name, list) in map) obj.put(name, JSONArray(list))
        sp.edit().putString("playlists", obj.toString()).apply()
    }

    fun createPlaylist(name: String): Boolean {
        val clean = name.trim()
        val map = playlists()
        if (clean.isEmpty() || map.containsKey(clean)) return false
        map[clean] = ArrayList()
        savePlaylists(map)
        return true
    }

    fun deletePlaylist(name: String) {
        val map = playlists()
        map.remove(name)
        savePlaylists(map)
    }

    fun addToPlaylist(name: String, uri: String) {
        val map = playlists()
        val list = map[name] ?: return
        if (!list.contains(uri)) list.add(uri)
        savePlaylists(map)
    }

    fun removeFromPlaylist(name: String, uri: String) {
        val map = playlists()
        val list = map[name] ?: return
        list.remove(uri)
        savePlaylists(map)
    }
}
