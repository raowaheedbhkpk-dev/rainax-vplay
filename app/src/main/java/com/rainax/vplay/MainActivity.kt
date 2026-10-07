package com.rainax.vplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recycler: RecyclerView
    private lateinit var emptyBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var grantButton: Button
    private lateinit var adapter: VideoAdapter
    private lateinit var store: PositionStore

    private var all: List<VideoItem> = emptyList()
    private var shown: List<VideoItem> = emptyList()
    private var selectedFolder: String? = null
    private var showAll = false
    private var query = ""
    private var sort = SortMode.DATE

    private val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadVideos() else rebuild()
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectedFolder = null
            rebuild()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = PositionStore(this)
        toolbar = findViewById(R.id.toolbar)
        recycler = findViewById(R.id.recycler)
        emptyBox = findViewById(R.id.emptyBox)
        emptyText = findViewById(R.id.emptyText)
        grantButton = findViewById(R.id.grantButton)

        adapter = VideoAdapter(
            onFolder = { folder ->
                selectedFolder = folder.name
                rebuild()
            },
            onVideo = { item -> openPlayer(item) },
            progressOf = { v ->
                if (v.durationMs > 0) {
                    ((store.get(v.uri.toString()) * 100) / v.durationMs).toInt().coerceIn(0, 100)
                } else 0
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        onBackPressedDispatcher.addCallback(this, backCallback)

        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setNavigationOnClickListener {
            selectedFolder = null
            rebuild()
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> showSortDialog()
                R.id.action_view -> {
                    showAll = !showAll
                    selectedFolder = null
                    rebuild()
                }
                R.id.action_stream -> showStreamDialog()
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

        findViewById<FloatingActionButton>(R.id.fabStream).setOnClickListener { showStreamDialog() }
        grantButton.setOnClickListener { permissionLauncher.launch(permission) }

        if (!hasPermission()) {
            permissionLauncher.launch(permission)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) loadVideos() else rebuild()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun loadVideos() {
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { VideoRepository.load(this@MainActivity) }
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
        val folder = selectedFolder

        when {
            q.isNotEmpty() -> {
                shown = sorted(all.filter { it.title.contains(q, ignoreCase = true) })
                shown.forEach { rows.add(Row.Video(it)) }
            }
            showAll -> {
                shown = sorted(all)
                shown.forEach { rows.add(Row.Video(it)) }
            }
            folder != null -> {
                shown = sorted(all.filter { it.folder == folder })
                shown.forEach { rows.add(Row.Video(it)) }
            }
            else -> {
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
        }

        toolbar.title = when {
            folder != null && q.isEmpty() && !showAll -> folder
            showAll && q.isEmpty() -> getString(R.string.show_all)
            else -> getString(R.string.app_name)
        }
        if (folder != null && !showAll && q.isEmpty()) {
            toolbar.setNavigationIcon(R.drawable.ic_back)
        } else {
            toolbar.navigationIcon = null
        }
        backCallback.isEnabled = folder != null && !showAll && q.isEmpty()

        toolbar.menu.findItem(R.id.action_view)?.title =
            getString(if (showAll) R.string.show_folders else R.string.show_all)

        val granted = hasPermission()
        emptyBox.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        emptyText.setText(if (granted) R.string.no_videos else R.string.permission_needed)
        grantButton.visibility = if (granted) View.GONE else View.VISIBLE

        adapter.submit(rows)
    }

    private fun openPlayer(item: VideoItem) {
        var list = shown
        var index = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)

        // Keep the intent small for very large folders.
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
}
