package com.rainax.vplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var lib: LibraryStore
    private lateinit var store: PositionStore
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var recycler: RecyclerView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var grantButton: Button
    private lateinit var adapter: VideoAdapter

    private var appliedTheme = ""
    private var all: List<VideoItem> = emptyList()
    private var byUri: Map<String, VideoItem> = emptyMap()
    private var shown: List<VideoItem> = emptyList()

    private var tab = TAB_FOLDERS
    private var folder: String? = null
    private var playlist: String? = null
    private var query = ""
    private var sort = SortMode.DATE

    private val handler = Handler(Looper.getMainLooper())
    private val reload = Runnable { if (hasPermission()) loadVideos() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 800)
        }
    }

    private val storagePermission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadVideos() else rebuild()
            maybeAskNotifications()
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) playExternal(uri)
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            folder = null
            playlist = null
            rebuild()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        appliedTheme = prefs.theme
        setTheme(prefs.themeRes())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lib = LibraryStore(this)
        store = PositionStore(this)
        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        recycler = findViewById(R.id.recycler)
        emptyBox = findViewById(R.id.emptyBox)
        emptyText = findViewById(R.id.emptyText)
        grantButton = findViewById(R.id.grantButton)

        adapter = VideoAdapter(
            onFolder = { f ->
                folder = f.name
                rebuild()
            },
            onPlaylist = { p ->
                playlist = p.name
                rebuild()
            },
            onPlaylistLong = { p -> showPlaylistMenu(p) },
            onVideo = { item -> openPlayer(item) },
            onVideoLong = { item -> showVideoMenu(item) },
            progressOf = { v ->
                if (v.durationMs > 0) {
                    ((store.get(v.uri.toString()) * 100) / v.durationMs).toInt().coerceIn(0, 100)
                } else 0
            },
            isFavourite = { v -> lib.isFavourite(v.uri.toString()) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        onBackPressedDispatcher.addCallback(this, backCallback)

        for (label in resources.getStringArray(R.array.tabs)) {
            tabs.addTab(tabs.newTab().setText(label))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = t.position
                folder = null
                playlist = null
                rebuild()
            }

            override fun onTabUnselected(t: TabLayout.Tab) {}

            override fun onTabReselected(t: TabLayout.Tab) {
                folder = null
                playlist = null
                rebuild()
            }
        })

        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setNavigationOnClickListener {
            folder = null
            playlist = null
            rebuild()
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> showSortDialog()
                R.id.action_open -> openFileLauncher.launch(arrayOf("video/*", "audio/*"))
                R.id.action_stream -> showStreamDialog()
                R.id.action_new_playlist -> promptName(R.string.new_playlist) { name ->
                    if (!lib.createPlaylist(name)) {
                        Toast.makeText(this, R.string.playlist_exists, Toast.LENGTH_SHORT).show()
                    }
                    rebuild()
                }
                R.id.action_clear_history -> {
                    lib.clearHistory()
                    rebuild()
                }
                R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.action_about -> showAboutDialog()
            }
            true
        }

        val searchItem = toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                query = text.orEmpty()
                rebuild()
                return true
            }

            override fun onQueryTextChange(text: String?): Boolean {
                query = text.orEmpty()
                rebuild()
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                query = ""
                rebuild()
                return true
            }
        })

        findViewById<FloatingActionButton>(R.id.fabStream).setOnClickListener { showStreamDialog() }
        grantButton.setOnClickListener { permissionLauncher.launch(storagePermission) }

        if (hasPermission()) {
            maybeAskNotifications()
        } else {
            permissionLauncher.launch(storagePermission)
        }
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(reload)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.theme != appliedTheme) {
            recreate()
            return
        }
        if (hasPermission()) loadVideos() else rebuild()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, storagePermission) ==
            PackageManager.PERMISSION_GRANTED

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.sp.getBoolean("asked_notifications", false)) {
            prefs.sp.edit().putBoolean("asked_notifications", true).apply()
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadVideos() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { VideoRepository.load(this@MainActivity) }
            all = list
            byUri = list.associateBy { it.uri.toString() }
            rebuild()
        }
    }

    private fun sorted(list: List<VideoItem>): List<VideoItem> = when (sort) {
        SortMode.NAME -> list.sortedBy { it.title.lowercase() }
        SortMode.DATE -> list.sortedByDescending { it.dateAdded }
        SortMode.SIZE -> list.sortedByDescending { it.sizeBytes }
        SortMode.DURATION -> list.sortedByDescending { it.durationMs }
    }

    private fun rebuild() {
        val q = query.trim()
        val rows = ArrayList<Row>()
        val fo = folder
        val pl = playlist
        var inside = false

        when {
            q.isNotEmpty() -> {
                shown = sorted(all.filter { it.title.contains(q, ignoreCase = true) })
            }
            tab == TAB_FOLDERS && fo == null -> {
                shown = emptyList()
                all.groupBy { it.folder }
                    .map { (name, vids) ->
                        Row.Folder(
                            name = name,
                            count = vids.size,
                            thumb = vids.maxByOrNull { it.dateAdded }!!.uri
                        )
                    }
                    .sortedBy { it.name.lowercase() }
                    .forEach { rows.add(it) }
            }
            tab == TAB_FOLDERS -> {
                inside = true
                shown = sorted(all.filter { it.folder == fo })
            }
            tab == TAB_ALL -> {
                shown = sorted(all)
            }
            tab == TAB_FAVOURITES -> {
                val fav = lib.favourites()
                shown = sorted(all.filter { fav.contains(it.uri.toString()) })
            }
            tab == TAB_HISTORY -> {
                shown = lib.history().mapNotNull { byUri[it] }
            }
            pl == null -> {
                shown = emptyList()
                for ((name, uris) in lib.playlists()) {
                    val vids = uris.mapNotNull { byUri[it] }
                    rows.add(Row.Playlist(name, vids.size, vids.firstOrNull()?.uri))
                }
            }
            else -> {
                inside = true
                val uris: List<String> = lib.playlists()[pl ?: ""] ?: emptyList()
                shown = uris.mapNotNull { byUri[it] }
            }
        }
        shown.forEach { rows.add(Row.Video(it)) }

        toolbar.title = when {
            inside && tab == TAB_FOLDERS && fo != null -> fo
            inside && tab == TAB_PLAYLISTS && pl != null -> pl
            else -> getString(R.string.app_name)
        }
        if (inside) {
            toolbar.setNavigationIcon(R.drawable.ic_back)
        } else {
            toolbar.navigationIcon = null
        }
        backCallback.isEnabled = inside

        toolbar.menu.findItem(R.id.action_new_playlist)?.isVisible =
            tab == TAB_PLAYLISTS && pl == null && q.isEmpty()
        toolbar.menu.findItem(R.id.action_clear_history)?.isVisible =
            tab == TAB_HISTORY && q.isEmpty()

        val granted = hasPermission()
        emptyBox.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        emptyText.setText(
            when {
                !granted -> R.string.permission_needed
                q.isEmpty() && tab == TAB_FAVOURITES -> R.string.no_favourites
                q.isEmpty() && tab == TAB_HISTORY -> R.string.no_history
                q.isEmpty() && tab == TAB_PLAYLISTS && pl == null -> R.string.no_playlists
                else -> R.string.no_videos
            }
        )
        grantButton.visibility = if (granted) View.GONE else View.VISIBLE

        adapter.submit(rows)
    }

    // ---------------------------------------------------------------- actions

    private fun openPlayer(item: VideoItem) {
        var list = shown
        var index = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)

        // Keep the intent small for very large lists.
        val maxItems = 500
        if (list.size > maxItems) {
            val from = (index - maxItems / 2).coerceAtLeast(0)
            val to = (from + maxItems).coerceAtMost(list.size)
            list = list.subList(from, to)
            index -= from
        }

        val intent = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(
                PlayerActivity.EXTRA_URIS,
                ArrayList(list.map { it.uri.toString() })
            )
            .putStringArrayListExtra(
                PlayerActivity.EXTRA_TITLES,
                ArrayList(list.map { it.title })
            )
            .putExtra(PlayerActivity.EXTRA_INDEX, index)
        startActivity(intent)
    }

    private fun playExternal(uri: Uri) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    private class Act(val label: String, val run: () -> Unit)

    private fun showVideoMenu(item: VideoItem) {
        val uri = item.uri.toString()
        val pl = playlist
        val acts = ArrayList<Act>()

        acts.add(Act(getString(R.string.play)) { openPlayer(item) })
        acts.add(
            Act(
                getString(
                    if (lib.isFavourite(uri)) R.string.fav_remove else R.string.fav_add
                )
            ) {
                lib.toggleFavourite(uri)
                rebuild()
            }
        )
        acts.add(Act(getString(R.string.add_to_playlist)) { pickPlaylist(uri) })
        if (tab == TAB_PLAYLISTS && pl != null) {
            acts.add(Act(getString(R.string.remove_from_playlist)) {
                lib.removeFromPlaylist(pl, uri)
                rebuild()
            })
        }
        acts.add(Act(getString(R.string.menu_info)) { showVideoInfo(item) })

        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(acts.map { it.label }.toTypedArray()) { _, which -> acts[which].run() }
            .show()
    }

    private fun showVideoInfo(item: VideoItem) {
        val date = DateFormat.getDateTimeInstance()
            .format(Date(item.dateAdded * 1000L))
        val resolution = if (item.width > 0) "${item.width}×${item.height}" else "-"
        val text = item.title + "\n\n" +
            getString(R.string.info_folder) + ": " + item.folder + "\n" +
            getString(R.string.info_duration) + ": " + Fmt.time(item.durationMs) + "\n" +
            getString(R.string.info_size) + ": " + Fmt.size(item.sizeBytes) + "\n" +
            getString(R.string.info_resolution) + ": " + resolution + "\n" +
            getString(R.string.info_added) + ": " + date
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_info)
            .setMessage(text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun pickPlaylist(uri: String) {
        val names = lib.playlists().keys.toList()
        val labels = ArrayList<String>(names)
        labels.add(getString(R.string.new_playlist))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < names.size) {
                    lib.addToPlaylist(names[which], uri)
                    Toast.makeText(this, R.string.added_to_playlist, Toast.LENGTH_SHORT).show()
                } else {
                    promptName(R.string.new_playlist) { name ->
                        if (lib.createPlaylist(name)) {
                            lib.addToPlaylist(name.trim(), uri)
                            Toast.makeText(this, R.string.added_to_playlist, Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            Toast.makeText(this, R.string.playlist_exists, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPlaylistMenu(p: Row.Playlist) {
        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setItems(arrayOf(getString(R.string.delete_playlist))) { _, _ ->
                lib.deletePlaylist(p.name)
                rebuild()
            }
            .show()
    }

    private fun promptName(titleRes: Int, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSortDialog() {
        val labels = resources.getStringArray(R.array.sort_options)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(labels, sort.ordinal) { dialog, which ->
                sort = SortMode.values()[which]
                rebuild()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStreamDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.stream_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stream_title)
            .setView(container)
            .setPositiveButton(R.string.play) { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.contains("://")) url = "http://$url"
                    val intent = Intent(this, PlayerActivity::class.java)
                    intent.data = Uri.parse(url)
                    startActivity(intent)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.about_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private companion object {
        const val TAB_FOLDERS = 0
        const val TAB_ALL = 1
        const val TAB_FAVOURITES = 2
        const val TAB_HISTORY = 3
        const val TAB_PLAYLISTS = 4
    }
}
