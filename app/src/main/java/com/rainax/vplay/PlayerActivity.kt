package com.rainax.vplay

import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "rainax.uris"
        const val EXTRA_TITLES = "rainax.titles"
        const val EXTRA_INDEX = "rainax.index"
        const val EXTRA_RESUME = "rainax.resume"

        private const val FULL_WIDTH_SEEK_MS = 120_000L
    }

    private lateinit var player: ExoPlayer
    private lateinit var prefs: Prefs
    private lateinit var lib: LibraryStore
    private lateinit var store: PositionStore
    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var indicator: TextView
    private lateinit var unlockButton: ImageButton
    private lateinit var audioManager: AudioManager
    private lateinit var gestureHandler: GestureHandler
    private lateinit var gestureDetector: GestureDetector

    private val handler = Handler(Looper.getMainLooper())
    private val hideIndicator = Runnable { indicator.visibility = View.GONE }
    private val hideUnlock = Runnable { unlockButton.visibility = View.GONE }

    private var playerListener: Player.Listener? = null
    private var locked = false
    private var inPip = false
    private var manualRotation = false
    private var currentUri: String? = null
    private var resizeIndex = 0

    // A-B repeat
    private var abA = -1L
    private var abB = -1L
    private val abRunnable = object : Runnable {
        override fun run() {
            if (abA >= 0 && abB > abA && player.currentPosition >= abB) {
                player.seekTo(abA)
            }
            handler.postDelayed(this, 200)
        }
    }

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeLabels = intArrayOf(
        R.string.aspect_fit,
        R.string.aspect_fill,
        R.string.aspect_zoom
    )

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addSubtitle(uri)
        }

    private val lockBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showUnlockButton()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = Prefs(this)
        lib = LibraryStore(this)
        store = PositionStore(this)

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        titleView = findViewById(R.id.titleView)
        indicator = findViewById(R.id.indicator)
        unlockButton = findViewById(R.id.unlockButton)

        onBackPressedDispatcher.addCallback(this, lockBackCallback)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val i = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.setPadding(i.left + dp(12), i.top + dp(8), i.right + dp(12), dp(8))
            insets
        }

        // The service keeps the player alive for background audio.
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (e: Exception) {
            // Playback still works without the notification.
        }
        player = PlayerHolder.get(this)
        playerView.player = player
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                topBar.visibility = if (locked || inPip) View.GONE else visibility
            }
        )

        setupButtons()
        setupGestures()
        setupPlayerListener()
        applySubtitleStyle()

        loadFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        setVideoDisabled(false)
        applySubtitleStyle()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.autoPip && player.isPlaying && !inPip) enterPip()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) return
        savePosition()
        if (!inPip) {
            if (prefs.backgroundAudio) {
                // Keep the sound going but stop decoding video while hidden.
                setVideoDisabled(true)
            } else {
                player.pause()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        playerListener?.let { player.removeListener(it) }
        playerView.player = null
        if (isFinishing) {
            savePosition()
            player.stop()
            stopService(Intent(this, PlaybackService::class.java))
        }
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerView.useController = false
            topBar.visibility = View.GONE
            indicator.visibility = View.GONE
            unlockButton.visibility = View.GONE
        } else if (!locked) {
            playerView.useController = true
            playerView.showController()
        }
    }

    // ---------------------------------------------------------------- setup

    private fun setupButtons() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnAspect).setOnClickListener { cycleAspect() }
        findViewById<ImageButton>(R.id.btnRotate).setOnClickListener { toggleRotation() }
        findViewById<ImageButton>(R.id.btnLock).setOnClickListener { setLocked(true) }
        findViewById<ImageButton>(R.id.btnMore).setOnClickListener { showMoreMenu() }
        unlockButton.setOnClickListener { setLocked(false) }
    }

    private fun setupPlayerListener() {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentUri = mediaItem?.mediaId
                titleView.text = mediaItem?.mediaMetadata?.title ?: ""
                currentUri?.let { lib.addHistory(it) }
                resetAb()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentUri?.let { store.clear(it) }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyAutoOrientation(videoSize)
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.cannot_play) + " (" + error.errorCodeName + ")",
                    Toast.LENGTH_LONG
                ).show()
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        }
        playerListener = l
        player.addListener(l)
    }

    private fun setupGestures() {
        gestureHandler = GestureHandler()
        gestureDetector = GestureDetector(this, gestureHandler)
        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureHandler.onUp()
            }
            true
        }
    }

    private fun applySubtitleStyle() {
        val sv = playerView.subtitleView ?: return
        sv.setApplyEmbeddedStyles(false)
        sv.setApplyEmbeddedFontSizes(false)
        sv.setFractionalTextSize(prefs.subSize)
        sv.setBottomPaddingFraction(prefs.subPosition)

        val bg = prefs.subBackground
        val background = if (bg == "box") 0xAA000000.toInt() else Color.TRANSPARENT
        val edge = when (bg) {
            "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            "box" -> CaptionStyleCompat.EDGE_TYPE_NONE
            else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
        sv.setStyle(
            CaptionStyleCompat(
                prefs.subColor,
                background,
                Color.TRANSPARENT,
                edge,
                Color.BLACK,
                Typeface.DEFAULT_BOLD
            )
        )
    }

    // ---------------------------------------------------------------- media items

    private fun loadFromIntent(i: Intent) {
        val (items, startIndex) = buildItems(i)
        if (items.isEmpty()) {
            if (i.getBooleanExtra(EXTRA_RESUME, false) && player.mediaItemCount > 0) {
                // Re-opened from the notification: keep what is already playing.
                currentUri = player.currentMediaItem?.mediaId
                titleView.text = player.currentMediaItem?.mediaMetadata?.title ?: ""
                return
            }
            Toast.makeText(this, R.string.nothing_to_play, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        player.pauseAtEndOfMediaItems = !prefs.autoplayNext
        player.setPlaybackSpeed(prefs.defaultSpeed)

        val startKey = items[startIndex].mediaId
        val saved = if (prefs.rememberPosition) store.get(startKey) else 0L
        val startPos = if (saved > 5_000L) saved - 1_000L else 0L

        currentUri = startKey
        titleView.text = items[startIndex].mediaMetadata.title ?: ""
        resetAb()

        player.setMediaItems(items, startIndex, startPos)
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildItems(i: Intent): Pair<List<MediaItem>, Int> {
        val uris = i.getStringArrayListExtra(EXTRA_URIS)
        if (uris != null && uris.isNotEmpty()) {
            val titles = i.getStringArrayListExtra(EXTRA_TITLES)
            val items = uris.mapIndexed { index, u -> mediaItem(u, titles?.getOrNull(index)) }
            val index = i.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.lastIndex)
            return Pair(items, index)
        }
        val data = i.data
        if (data != null) {
            return Pair(listOf(mediaItem(data.toString(), queryName(data))), 0)
        }
        return Pair(emptyList(), 0)
    }

    private fun mediaItem(uri: String, title: String?): MediaItem {
        val name = title ?: Uri.parse(uri).lastPathSegment ?: "Video"
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
    }

    private fun queryName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) return c.getString(idx)
                        }
                    }
            } catch (e: Exception) {
                // fall through
            }
        }
        return uri.lastPathSegment
    }

    // ---------------------------------------------------------------- subtitles

    private fun addSubtitle(uri: Uri) {
        val name = (queryName(uri) ?: "").lowercase()
        val mime = when {
            name.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") || name.endsWith(".dfxp") ->
                MimeTypes.APPLICATION_TTML
            else -> null
        }
        if (mime == null) {
            Toast.makeText(this, R.string.subtitle_unsupported, Toast.LENGTH_LONG).show()
            return
        }

        val current = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady

        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val updated = current.buildUpon()
            .setSubtitleConfigurations(listOf(subtitle))
            .build()

        player.replaceMediaItem(index, updated)
        player.seekTo(index, position)
        player.playWhenReady = wasPlaying
        Toast.makeText(this, R.string.subtitle_added, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- more menu

    private class Act(val label: String, val run: () -> Unit)

    private fun showMoreMenu() {
        val acts = ArrayList<Act>()

        acts.add(Act(getString(R.string.menu_audio)) { showAudioDialog() })
        acts.add(Act(getString(R.string.menu_load_subtitle)) {
            subtitlePicker.launch(arrayOf("*/*"))
        })

        val sleepLeft = PlayerHolder.sleepRemainingMinutes()
        val sleepLabel = if (sleepLeft > 0) {
            getString(R.string.menu_sleep_on, sleepLeft)
        } else getString(R.string.menu_sleep)
        acts.add(Act(sleepLabel) { showSleepDialog() })

        val abLabel = when {
            abA < 0 -> getString(R.string.menu_ab_set_a)
            abB < 0 -> getString(R.string.menu_ab_set_b, Fmt.time(abA))
            else -> getString(R.string.menu_ab_clear, Fmt.time(abA), Fmt.time(abB))
        }
        acts.add(Act(abLabel) { abClick() })

        acts.add(Act(getString(R.string.menu_prev_frame)) { stepFrame(-1) })
        acts.add(Act(getString(R.string.menu_next_frame)) { stepFrame(1) })

        val repeatName = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> getString(R.string.repeat_one)
            Player.REPEAT_MODE_ALL -> getString(R.string.repeat_all)
            else -> getString(R.string.repeat_off)
        }
        acts.add(Act(getString(R.string.menu_repeat, repeatName)) { cycleRepeat() })

        val shuffleName = getString(
            if (player.shuffleModeEnabled) R.string.on else R.string.off
        )
        acts.add(Act(getString(R.string.menu_shuffle, shuffleName)) {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            showIndicator(
                getString(
                    R.string.menu_shuffle,
                    getString(if (player.shuffleModeEnabled) R.string.on else R.string.off)
                )
            )
        })

        val uri = currentUri
        if (uri != null) {
            val fav = lib.isFavourite(uri)
            acts.add(
                Act(getString(if (fav) R.string.fav_remove else R.string.fav_add)) {
                    val now = lib.toggleFavourite(uri)
                    showIndicator(getString(if (now) R.string.fav_added else R.string.fav_removed))
                }
            )
        }

        acts.add(Act(getString(R.string.menu_screenshot)) { takeScreenshot() })
        acts.add(Act(getString(R.string.menu_info)) { showInfoDialog() })
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            acts.add(Act(getString(R.string.pip)) { enterPip() })
        }

        MaterialAlertDialogBuilder(this)
            .setItems(acts.map { it.label }.toTypedArray()) { _, which -> acts[which].run() }
            .show()
    }

    private fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        val name = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> getString(R.string.repeat_one)
            Player.REPEAT_MODE_ALL -> getString(R.string.repeat_all)
            else -> getString(R.string.repeat_off)
        }
        showIndicator(getString(R.string.menu_repeat, name))
    }

    // ---------------------------------------------------------------- A-B repeat / frames / sleep

    private fun abClick() {
        when {
            abA < 0 -> {
                abA = player.currentPosition
                showIndicator(getString(R.string.ab_a_set, Fmt.time(abA)))
            }
            abB < 0 -> {
                val pos = player.currentPosition
                if (pos <= abA + 500) {
                    Toast.makeText(this, R.string.ab_invalid, Toast.LENGTH_SHORT).show()
                    return
                }
                abB = pos
                handler.removeCallbacks(abRunnable)
                handler.post(abRunnable)
                showIndicator(getString(R.string.ab_active, Fmt.time(abA), Fmt.time(abB)))
            }
            else -> {
                resetAb()
                showIndicator(getString(R.string.ab_cleared))
            }
        }
    }

    private fun resetAb() {
        abA = -1L
        abB = -1L
        handler.removeCallbacks(abRunnable)
    }

    private fun stepFrame(direction: Int) {
        player.pause()
        val fps = player.videoFormat?.frameRate ?: -1f
        val stepMs = if (fps > 0f) (1000f / fps).roundToLong() else 40L
        val target = (player.currentPosition + direction.toLong() * stepMs).coerceAtLeast(0L)
        player.seekTo(target)
        showIndicator(Fmt.time(target))
    }

    private fun showSleepDialog() {
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)
        val labels = arrayOf(
            getString(R.string.off),
            getString(R.string.minutes, 15),
            getString(R.string.minutes, 30),
            getString(R.string.minutes, 45),
            getString(R.string.minutes, 60),
            getString(R.string.minutes, 90)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_sleep)
            .setItems(labels) { _, which ->
                PlayerHolder.setSleep(minutes[which])
                showIndicator(
                    if (minutes[which] == 0) getString(R.string.sleep_off)
                    else getString(R.string.sleep_set, minutes[which])
                )
            }
            .show()
    }

    // ---------------------------------------------------------------- audio dialog

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun showAudioDialog() {
        val fx = PlayerHolder.fx
        val sp = prefs.sp
        val pad = dp(20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }

        // Volume boost
        val boostLabel = TextView(this)
        val boostBar = SeekBar(this)
        boostBar.max = 200
        boostBar.progress = (sp.getInt("boost_percent", 100) - 100).coerceIn(0, 200)
        boostLabel.text = getString(R.string.boost_label, 100 + boostBar.progress)
        boostBar.setOnSeekBarChangeListener(seekListener { p ->
            sp.edit().putInt("boost_percent", 100 + p).apply()
            boostLabel.text = getString(R.string.boost_label, 100 + p)
            fx.refresh(sp)
        })
        root.addView(boostLabel)
        root.addView(boostBar)

        // Equalizer
        val eqSwitch = MaterialSwitch(this)
        eqSwitch.text = getString(R.string.equalizer)
        eqSwitch.isChecked = sp.getBoolean("eq_enabled", false)
        eqSwitch.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("eq_enabled", checked).apply()
            fx.refresh(sp)
        }
        val switchLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        switchLp.topMargin = dp(16)
        root.addView(eqSwitch, switchLp)

        val bands = fx.bandCount()
        if (!fx.equalizerAvailable || bands == 0) {
            val none = TextView(this)
            none.text = getString(R.string.eq_unavailable)
            root.addView(none)
        } else {
            val min = fx.minLevel()
            val max = fx.maxLevel()
            val saved = (sp.getString("eq_levels", "") ?: "")
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
            val levels = IntArray(bands) { saved.getOrNull(it) ?: 0 }
            val bars = ArrayList<SeekBar>()
            val labels = ArrayList<TextView>()

            fun freqText(band: Int): String {
                val hz = fx.centerFreqHz(band)
                val name = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
                return name + "   " + String.format("%+.1f dB", levels[band] / 100f)
            }

            for (b in 0 until bands) {
                val label = TextView(this)
                label.text = freqText(b)
                val bar = SeekBar(this)
                bar.max = max - min
                bar.progress = (levels[b] - min).coerceIn(0, max - min)
                bar.setOnSeekBarChangeListener(seekListener { p ->
                    levels[b] = p + min
                    label.text = freqText(b)
                    sp.edit().putString("eq_levels", levels.joinToString(",")).apply()
                    fx.refresh(sp)
                })
                labels.add(label)
                bars.add(bar)
                root.addView(label)
                root.addView(bar)
            }

            val reset = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            reset.text = getString(R.string.eq_reset)
            reset.setOnClickListener {
                for (b in 0 until bands) {
                    levels[b] = 0
                    bars[b].progress = -min
                    labels[b].text = freqText(b)
                }
                sp.edit().putString("eq_levels", levels.joinToString(",")).apply()
                fx.refresh(sp)
            }
            val resetLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            resetLp.topMargin = dp(8)
            root.addView(reset, resetLp)
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_audio)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------------------------------------------------------------- info / screenshot

    private fun showInfoDialog() {
        val sb = StringBuilder()
        sb.append(titleView.text).append("\n\n")

        val v = player.videoFormat
        if (v != null) {
            sb.append(getString(R.string.info_video)).append(": ")
            sb.append(v.sampleMimeType ?: "?")
            if (v.codecs != null) sb.append(" (").append(v.codecs).append(")")
            if (v.width > 0) sb.append("\n").append(v.width).append("×").append(v.height)
            if (v.frameRate > 0f) {
                sb.append("  ").append(String.format("%.2f fps", v.frameRate))
            }
            if (v.bitrate > 0) sb.append("  ").append(v.bitrate / 1000).append(" kb/s")
            sb.append("\n\n")
        }
        val a = player.audioFormat
        if (a != null) {
            sb.append(getString(R.string.info_audio)).append(": ")
            sb.append(a.sampleMimeType ?: "?")
            if (a.channelCount > 0) sb.append("  ").append(a.channelCount).append(" ch")
            if (a.sampleRate > 0) sb.append("  ").append(a.sampleRate).append(" Hz")
            if (a.bitrate > 0) sb.append("  ").append(a.bitrate / 1000).append(" kb/s")
            sb.append("\n\n")
        }

        var textTracks = 0
        for (group in player.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) textTracks += group.length
        }
        sb.append(getString(R.string.info_subtitles)).append(": ").append(textTracks).append("\n")
        if (player.duration != C.TIME_UNSET) {
            sb.append(getString(R.string.info_duration)).append(": ")
                .append(Fmt.time(player.duration)).append("\n")
        }
        sb.append(getString(R.string.info_speed)).append(": ")
            .append(player.playbackParameters.speed).append("x")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_info)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, R.string.screenshot_old_android, Toast.LENGTH_SHORT).show()
            return
        }
        val surface = playerView.videoSurfaceView as? SurfaceView
        if (surface == null || surface.width <= 0 || surface.height <= 0) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) saveScreenshot(bitmap)
                else Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
            }, handler)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Rainax_" + System.currentTimeMillis() + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RainaxVplay")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Toast.makeText(this, R.string.screenshot_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- controls

    private fun cycleAspect() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        playerView.resizeMode = resizeModes[resizeIndex]
        showIndicator(getString(resizeLabels[resizeIndex]))
    }

    private fun toggleRotation() {
        manualRotation = true
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun applyAutoOrientation(size: VideoSize) {
        if (!prefs.autoRotate || manualRotation || inPip || size.width <= 0 || size.height <= 0) {
            return
        }
        requestedOrientation = if (size.width >= size.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun enterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val size = player.videoSize
        val w = if (size.width > 0) size.width else 16
        val h = if (size.height > 0) size.height else 9
        val ratio = w.toFloat() / h.toFloat()
        val rational = when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 0.42f -> Rational(42, 100)
            else -> Rational(w, h)
        }
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(rational).build()
            )
        } catch (e: IllegalStateException) {
            // PiP not allowed right now.
        }
    }

    private fun setVideoDisabled(disabled: Boolean) {
        if (!::player.isInitialized) return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    private fun setLocked(value: Boolean) {
        locked = value
        lockBackCallback.isEnabled = value
        if (value) {
            playerView.useController = false
            topBar.visibility = View.GONE
            showUnlockButton()
            showIndicator(getString(R.string.locked))
        } else {
            handler.removeCallbacks(hideUnlock)
            unlockButton.visibility = View.GONE
            playerView.useController = true
            playerView.showController()
            showIndicator(getString(R.string.unlocked))
        }
    }

    private fun showUnlockButton() {
        handler.removeCallbacks(hideUnlock)
        unlockButton.visibility = View.VISIBLE
        handler.postDelayed(hideUnlock, 2500)
    }

    private fun showIndicator(text: String, autoHideMs: Long = 900L) {
        indicator.text = text
        indicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicator)
        if (autoHideMs > 0) handler.postDelayed(hideIndicator, autoHideMs)
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration
        var target = player.currentPosition + deltaMs
        target = if (duration != C.TIME_UNSET && duration > 0) {
            target.coerceIn(0L, duration)
        } else {
            target.coerceAtLeast(0L)
        }
        player.seekTo(target)
        val secs = abs(deltaMs) / 1000
        showIndicator(if (deltaMs > 0) "+${secs}s" else "−${secs}s", 600L)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun savePosition() {
        if (!::player.isInitialized) return
        val uri = currentUri ?: return
        if (!prefs.rememberPosition) return
        val position = player.currentPosition
        val duration = player.duration
        if (duration != C.TIME_UNSET && position > 5_000L && position < duration - 10_000L) {
            store.put(uri, position)
        } else {
            store.clear(uri)
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun setBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
    }

    // ---------------------------------------------------------------- gestures

    private inner class GestureHandler : GestureDetector.SimpleOnGestureListener() {

        private val modeNone = 0
        private val modeSeek = 1
        private val modeBrightness = 2
        private val modeVolume = 3
        private val modeIgnore = 4

        private var mode = modeNone
        private var startBrightness = 0.5f
        private var startVolume = 0
        private var startPosition = 0L
        private var seekTarget = -1L
        private var longPressActive = false
        private var speedBeforeLongPress = 1f

        override fun onDown(e: MotionEvent): Boolean {
            mode = modeNone
            seekTarget = -1L
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (inPip) return true
            if (locked) {
                showUnlockButton()
            } else if (playerView.isControllerFullyVisible) {
                playerView.hideController()
            } else {
                playerView.showController()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (locked || inPip || !prefs.gestureDoubleTap) return true
            val w = playerView.width.toFloat()
            val step = prefs.seekStepMs
            when {
                e.x < w / 3f -> seekBy(-step)
                e.x > w * 2f / 3f -> seekBy(step)
                else -> togglePlay()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (locked || inPip || mode != modeNone || !prefs.gestureLongPress) return
            longPressActive = true
            speedBeforeLongPress = player.playbackParameters.speed
            player.setPlaybackSpeed(2f)
            showIndicator(getString(R.string.speed_2x), 0L)
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (locked || inPip || longPressActive || e1 == null) return false
            val w = playerView.width.toFloat()
            val h = playerView.height.toFloat()
            if (w <= 0f || h <= 0f) return false

            if (mode == modeNone) {
                val horizontal = abs(e2.x - e1.x) > abs(e2.y - e1.y)
                mode = if (horizontal) {
                    if (prefs.gestureSeek) modeSeek else modeIgnore
                } else if (!prefs.gestureBrightnessVolume) {
                    modeIgnore
                } else if (e1.x < w / 2f) {
                    modeBrightness
                } else {
                    modeVolume
                }
                when (mode) {
                    modeSeek -> startPosition = player.currentPosition
                    modeBrightness -> startBrightness = currentBrightness()
                    modeVolume -> startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
            }

            when (mode) {
                modeSeek -> {
                    val duration = player.duration
                    if (duration == C.TIME_UNSET || duration <= 0L) return true
                    val delta = ((e2.x - e1.x) / w * FULL_WIDTH_SEEK_MS).toLong()
                    seekTarget = (startPosition + delta).coerceIn(0L, duration)
                    val sign = if (delta >= 0) "+" else "−"
                    showIndicator(
                        Fmt.time(seekTarget) + "  (" + sign + Fmt.time(abs(delta)) + ")",
                        0L
                    )
                }
                modeBrightness -> {
                    val value = (startBrightness + (e1.y - e2.y) / h * 1.3f).coerceIn(0.02f, 1f)
                    setBrightness(value)
                    showIndicator(getString(R.string.brightness, (value * 100).roundToInt()), 0L)
                }
                modeVolume -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val value = (startVolume + (e1.y - e2.y) / h * max * 1.3f)
                        .roundToInt().coerceIn(0, max)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                    val percent = if (max > 0) value * 100 / max else 0
                    showIndicator(getString(R.string.volume, percent), 0L)
                }
            }
            return true
        }

        fun onUp() {
            if (mode == modeSeek && seekTarget >= 0L) {
                player.seekTo(seekTarget)
            }
            if (longPressActive) {
                player.setPlaybackSpeed(speedBeforeLongPress)
                longPressActive = false
            }
            if (indicator.visibility == View.VISIBLE) {
                handler.removeCallbacks(hideIndicator)
                handler.postDelayed(hideIndicator, 500)
            }
            mode = modeNone
            seekTarget = -1L
        }
    }
}
